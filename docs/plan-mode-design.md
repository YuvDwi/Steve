# Plan-Build 工作流：PlanBuildAction 设计

## 1. 概述

`PlanBuildAction` 是 Steve 的"先规划、后施工"工作流入口。LLM 通过 `action=build` 触发，本类接管状态机，把 NBT 模板加载、玩家审批、方块放置和 mempalace 归档串成一个完整的工程流程。

设计核心：**编排器瘦身后委托给 4 个专职协作类**。`PlanBuildAction` 本身只负责状态转换和玩家命令。

## 2. 类结构与职责

```
com.steve.ai.action.plan
├── PlanBuildAction         状态机编排器
├── BuildProject            项目状态数据
├── BuildModuleSpecParser   静态工具：解析 LLM 输入的 module specs
├── ProjectArchiveService   静态工具：mempalace 归档 + JSON 序列化
├── BuildDesignGenerator    DESIGN 阶段：NBT 加载 + 位置解析
└── BuildConstructionLoop   CONSTRUCTION 阶段：tick 节奏方块放置
```

| 类 | 职责 | 关键方法 |
|---|---|---|
| `PlanBuildAction` | 状态机、玩家命令、事件总线 | `onStart` / `onTick` / `approve` / `halt` |
| `BuildProject` | 项目运行时状态（placedModules、materials、进度） | `findNearestPlayer` / `countMaterials` |
| `BuildModuleSpecParser` | 解析 `{name,dx,dy,dz,facing}[]` 协议 + 兄弟展开 | `parse` / `expandSingleStructureFallback` |
| `ProjectArchiveService` | mempalace `add_drawer` + 完整 JSON 序列化 | `archive` / `serialize` |
| `BuildDesignGenerator` | NBT 加载、位置解析、聊天/dashboard 推送 | `loadAndPlace` / `publishDesign` |
| `BuildConstructionLoop` | tick 节流 + `BlockPlacer` 委托 + 日志 | `tick` |

## 3. 状态机

```
FEASIBILITY → DESIGN → AWAITING_DESIGN_APPROVAL → CONSTRUCTION → COMPLETED
                ↓                 ↓
              FAILED ←─────── FAILED (halt)
```

| 阶段 | 触发条件 | 退出条件 |
|---|---|---|
| FEASIBILITY | 构造器完成 | 立即进入 DESIGN |
| DESIGN | onStart | `loadAndPlace` 成功 + 推完事件 → AWAITING_DESIGN_APPROVAL |
| AWAITING_DESIGN_APPROVAL | DESIGN 退出 | 玩家 `/steve approve` → CONSTRUCTION；`/steve halt` → FAILED |
| CONSTRUCTION | approve | `nextBlockIndex >= totalBlocks` → COMPLETED |
| COMPLETED / FAILED | 终态 | 无 |

`AWAITING_ACCEPTANCE` 枚举值保留以兼容旧代码，但当前流程不进入。

## 4. 完整流程

### 4.1 入口：构造器（构造时）

```java
public PlanBuildAction(SteveEntity steve, Task task, ActionExecutor executor) {
    super(steve, task);
    List<Map<String, Object>> moduleList = BuildModuleSpecParser.parse(task);
    List<String> names = BuildModuleSpecParser.extractNames(moduleList);
    String label = names.isEmpty() ? "unknown" : names.get(0);
    this.project = new BuildProject(steve, label, names);
    this.designGenerator = new BuildDesignGenerator(steve, project, moduleList);
    publishEvent(new PlanCreatedEvent(...));
}
```

LLM 通过 `parameters.structure` 或 `parameters.structures[]` 提供模板名。`BuildModuleSpecParser.parse` 处理：
- `structure` 单模板 → 包装为 1 元素数组
- 兄弟展开（plan-mode 兜底）：单元素时尝试从同类型 NBT 自动补全到 ≥2 entries
- cap 截断到 `MAX_TEMPLATES_PER_PLAN`

### 4.2 DESIGN：`runDesign()`

```java
private void runDesign() {
    if (!designGenerator.loadAndPlace(serverLevel)) { result = failure(...); return; }
    designGenerator.publishDesign();
    String ref = ProjectArchiveService.archive(project, DESIGN, "design",
        ProjectArchiveService.serialize(project, steveName, DESIGN, null));
    if (ref != null) project.mempalaceRefs.put(DESIGN, ref);
    transitionTo(AWAITING_DESIGN_APPROVAL);
}
```

`BuildDesignGenerator.loadAndPlace` 是核心：
1. 找最近玩家；用其视线方向 +12 格作为 `groundPos`（fallback：Steve +2,+0,+2）
2. 遍历 specs：
   - `loadFromNBT` 加载模板
   - 用 `ModuleTransform.apply(localIn, prevExit, prevFacing)` 算世界坐标
   - 添加 `PlacedModule(tpl, worldIn, facing)` 到 `project.placedModules`
   - 累计 `materials` 和 `totalBlocks`
   - 推进游标到本模块的 worldExit
3. 回填 `selectedTemplates` 为 survivors

`BuildDesignGenerator.publishDesign`：
- 用 `BuildDesignFormatter.fullDesign(project)` 渲染人类可读设计文档
- 推给最近玩家的聊天栏（`sendSystemMessage`）
- 构造 `PlanDesignReadyEvent`（含所有方块世界坐标的扁平列表）发到 event bus → dashboard 3D 渲染

`ProjectArchiveService.archive`：
- room 名 = `project.id + "_design"`
- wing = `build_designs`
- 调 `mempalace_add_drawer {wing, room, content, added_by:"steve-ai"}`
- 成功返回 ref 字符串，存入 `project.mempalaceRefs`

### 4.3 AWAITING_DESIGN_APPROVAL

`runAwaitingApproval()` 是个空方法 — 无限期等待玩家命令。
- `/steve approve` → `PlanBuildAction.approve()` → transitionTo(CONSTRUCTION)
- `/steve halt` → `PlanBuildAction.halt(reason)` → 归档 halt 抽屉 → FAILED

### 4.4 CONSTRUCTION：`runConstruction()`

```java
private void runConstruction() {
    if (constructionLoop == null) constructionLoop = new BuildConstructionLoop(steve, project);
    if (constructionLoop.tick(serverLevel)) {
        transitionTo(COMPLETED);
        result = success("Built " + blocksPlaced + "/" + totalBlocks);
        publishLog(INFO, "Construction complete: ...");
    }
}
```

`BuildConstructionLoop.tick` 是每 tick 调用的状态推进：
1. `nextBlockIndex >= totalBlocks` → 全部完成，返回 true
2. `constructionCooldown > 0` → 倒计时，return false
3. `placeNextBlock` → 推进索引；成功则设 cooldown = `BUILD_TICK_DELAY`

`placeNextBlock`：
- 用与 dashboard 相同的扁平顺序找 `nextBlockIndex` 处的方块（placedModules 顺序 + blocks 顺序）
- 计算 `worldPos = ModuleTransform.apply(tb.relativePos, pm.worldOrigin, pm.facing)`
- 委托给 `BlockPlacer.tryPlace(steve, worldPos, state)`（寻路 + 材料 + 占用 + 挥手 + setBlock）
- 收到 `PlaceResult`：
  - `NAVIGATING` → return false（下 tick 重试，不消耗 cooldown）
  - `OCCUPIED` → 推进索引 + 计数 + WARN 日志
  - `NO_MATERIAL` → 推进索引 + 计数 + WARN 日志
  - `PLACED` → 推进索引 + 计数 + 每 50 块 INFO 进度

### 4.5 COMPLETED / FAILED

COMPLETED：tick 推进到 `nextBlockIndex >= totalBlocks` 时 transitionTo，并设 `result = success`。

FAILED：玩家 `/steve halt`：
```java
String ref = ProjectArchiveService.archive(project, FAILED, "halted",
    ProjectArchiveService.serialize(project, steveName, project.phase, reason));
publishEvent(new PlanHaltedEvent(...));
result = ActionResult.failure("Build halted at phase X: Y. Design archived: Z", true);
```

`halt` 时的 JSON 比 DESIGN 少了逐方块列表（节省 mempalace 空间），因为 DESIGN 抽屉已包含完整布局。

## 5. 关键设计决策

### 5.1 为何按 `placedModules` 顺序扁平化

3 个下游消费者用相同顺序遍历 `placedModules`：
- `BuildConstructionLoop.placeNextBlock` 找下一个要放的方块
- `PlanDesignReadyEvent` 构造 dashboard 3D blocks 列表
- `ProjectArchiveService.serialize` 写 mempalace

顺序统一保证 **3D 预览和已放置世界不会偏离**。`ModuleTransform.apply` 是世界坐标旋转的唯一真实来源。

### 5.2 为何 `BlockPlacer` 抽到 util 包

`PlaceBlockAction`（LLM 单独发"放一块"任务）和 `BuildConstructionLoop`（plan 模式下批量放）逻辑完全相同：寻路 + 材料 + 占用 + 挥手 + setBlock。提取共享工具保证两条路径行为一致（plan 模式下也享受材料检查和动画）。

### 5.3 为何 PlanBuildAction 不直接调 LLM

施工阶段是**确定性的 tick 推进**——每 tick 放一个方块，不需要 LLM 决策。LLM 只在 plan 准备阶段参与（决定模板和组合），approve 后完全由 Minecraft tick 驱动。如果施工失败（如材料不足），可以选择 halt 但不重新问 LLM。

### 5.4 为何 `BuildModuleSpecParser` 用 static + 静态方法

它是无副作用的纯函数（LLM 输入 → specs 列表），且 3 个方法在测试中可独立调用而不需要 `SteveEntity` 或事件总线。把 `expandSingleStructureFallback` / `composeFromSiblings` 设计为 static 是为了将来测试覆盖。

### 5.5 mempalace 归档的 wing 约定

| Phase | Wing | Room suffix | 内容 |
|---|---|---|---|
| DESIGN | `build_designs` | `_design` | 完整 JSON（含 blocks 列表） |
| FAILED | `build_halted` | `_halted` | 元数据 + halted 对象，省略 blocks |
| COMPLETED | `built_structures` | （无，由 plan 决定） | 已建成结构的元数据 |
| （兜底） | `build_misc` | — | 其他 phase |
| AWAITING_ACCEPTANCE | `build_acceptance` | — | 保留兼容 |

## 6. 玩家命令接口

| 命令 | 调用 | 作用 |
|---|---|---|
| `/steve plan "<描述>"` | `ActionExecutor.startPlannedBuild` | 启动 LLM 规划（实际仍走 ReAct 循环） |
| `/steve approve` | `ActionExecutor.approveCurrentBuild` → `PlanBuildAction.approve` | 从 AWAITING_DESIGN_APPROVAL 推进到 CONSTRUCTION |
| `/steve halt` | `ActionExecutor.haltCurrentBuild` → `PlanBuildAction.halt` | 中止，归档 halt 抽屉 |

## 7. 事件协议

| 事件 | 触发点 | 关键字段 |
|---|---|---|
| `PlanCreatedEvent` | 构造器完成 | projectId, steveName, command, templates, phase |
| `PlanDesignReadyEvent` | DESIGN 完成 | design, materials, totalBlocks, **blocks** (x,y,z,blockId) |
| `PlanPhaseChangedEvent` | `transitionTo()` | prev, next, deadlineMs? |
| `PlanApprovedEvent` | `approve()` | phase, approvedBy |
| `PlanHaltedEvent` | `halt()` | reason, mempalaceRef, blocksPlaced, totalBlocks |
| `PlanLogEvent` | 关键日志 | severity, message |

`PlanDesignReadyEvent.blocks` 是 `(x, y, z, blockId)` 四元组的扁平世界坐标列表，dashboard 用 InstancedMesh 渲染。

## 8. 配置项

| 配置 | 默认 | 作用 |
|---|---|---|
| `MAX_TEMPLATES_PER_PLAN` | 3 | LLM 可选的最多模块数 |
| `BUILD_TICK_DELAY` | 20 | 两次方块放置之间的 tick 数（1 秒） |
| `CREATIVE_MODE` | false | 切换无限材料 / 正常消耗 |

## 9. 设计不变量

1. **3D 预览 = 已放置世界**：所有方块的世界坐标都通过 `ModuleTransform.apply` 解析，单一旋转来源。
2. **施工是纯 tick 驱动**：approve 后不调 LLM，方块放置由 Minecraft tick 推进。
3. **mempalace 是真相之源**：每阶段都归档，halt 不丢数据，replay 可还原。
4. **失败可恢复**：`halt` 后设计文档仍在 `build_designs` 抽屉，玩家可手动修复后重新 approve（用 `mempalace_get_drawer` 查 ref）。
