# Plan 模式：四阶段施工流程映射

## Context

参考真实高速公路建设的四阶段流程（可研→勘察设计→施工→验收），把当前 Steve AI 的"LLM 一句话 → 直接施工"流程升级为**显式四阶段**，每个阶段都向玩家/系统暴露可检查的中间产物。

**目标**：
- LLM 不再"黑盒"决定建造，每次施工**前都有可审阅的计划**
- 玩家在关键节点（设计完成、施工开始）有检查/取消权
- 失败 / 取消可以回退到上一阶段，而不是从头开始
- 阶段产物（计划书、施工日志、验收报告）全部沉淀到 mempalace，可追溯

**不做** GUI（用户明确要求）——所有交互走聊天栏 + `/steve` 子命令。

## 真实工程 → Steve AI 映射

| 阶段 | 真实工程 | Steve AI 落地 | 玩家可见产物 |
|------|---------|--------------|------------|
| **一·可研** | 必要性 / 技术 / 经济论证 | LLM 通过 `mcp mempalace_list_drawers` 查模板 → 选最匹配 → 计算材料成本 | 聊天栏："候选模板：house_1, house_2, castle，已选 house_1（理由：与'建个小屋'最匹配）" |
| **二·设计** | 初设→施工图 | 加载 NBT → 计算 footprint、占地区域、所需材料、协同分区 | 聊天栏：完整**设计图纸**（见下文 4.1），等待 `/steve approve` |
| **三·施工** | 清表→骨架→血肉 | `PlanBuildAction.runConstruction` 走 `placeNextBlock`：每 `BUILD_TICK_DELAY` tick 放一块，Steve 走不到就 `getNavigation().moveTo`，被占用就跳过 | 进度行 `plan.log` 事件 "Construction progress: N/total"，可 `/steve halt` 暂停（已放置方块不撤回） |

## 架构

### 1. 新数据模型

**`BuildPhase` 枚举**（`llm/react/BuildPhase.java` 新增）：

```java
public enum BuildPhase {
    FEASIBILITY,             // 阶段一：选模板
    DESIGN,                  // 阶段二：出图纸
    AWAITING_DESIGN_APPROVAL,// 阶段二末尾：等玩家 /steve approve
    CONSTRUCTION,            // 阶段三：施工（前端 approve 后直接进入，无二次确认）
    AWAITING_ACCEPTANCE,     // 保留枚举值以保持源码兼容；当前流程不再进入
    COMPLETED,               // 全部完成
    FAILED                   // 任意阶段失败
}
```

**`BuildProject` 数据类**（`action/BuildProject.java` 新增）——一个建造项目的全部上下文：

```java
public class BuildProject {
    String id;                       // UUID, 用于多 Steve 隔离
    SteveEntity steve;
    String command;                  // 玩家原始指令
    String selectedTemplate;         // 阶段一选定
    List<LoadedTemplate> templates;  // 阶段二加载（多模板拼接）
    BlockPos originPos;              // 施工原点
    Map<Block,Integer> materials;    // 阶段二计算
    BuildPhase phase;                // 当前阶段
    BuildPhase lastApproved;         // 玩家最后 approve 的阶段
    int blocksPlaced;                // 阶段三进度
    int totalBlocks;                 // 阶段二累加
    int nextBlockIndex;              // 阶段三施工游标：扁平化后下一个方块的索引
    long phaseDeadlineMs;            // 当前阶段超时时间
}
```

**`ActionResult` 扩展**（`action/ActionResult.java` 改一处）——加一个 status 字段，**不破坏**现有 `isSuccess()` 调用方：

```java
public enum Status { SUCCESS, FAILURE, PHASE_TRANSITION, AWAITING_APPROVAL }
private final Status status;        // 新增
public boolean isAwaitingApproval() { return status == Status.AWAITING_APPROVAL; }
public Status getStatus() { return status; }
// 旧工厂方法保持不变
public static ActionResult awaitingApproval(String msg) {
    return new ActionResult(false, msg, false, Status.AWAITING_APPROVAL);
}
```

### 2. 新 Action 类

**`PlanBuildAction.java`**（`action/actions/PlanBuildAction.java` 新增）——核心驱动 Action，**取代** LLM 直接发 `build` 时的拦截器。`extends BaseAction`。

`PlanBuildAction` 内部状态机：

```
FEASIBILITY (选模板) → DESIGN (出图纸) → AWAITING_DESIGN_APPROVAL → CONSTRUCTION → COMPLETED
                                          ↑ halt                          ↑ halt
                                          |                                |
                                          └────────── FAILED ←──────────────┘
```

每个阶段都是 `onTick` 里的一个 `switch (project.phase)`，推进条件：
- FEASIBILITY → LLM 通过 ReAct 已给定的 `template` / `structures` 参数驱动选定（不再走 `MCPAction` 查 mempalace，模板列表由 `StructureTemplateLoader.getAvailableStructures()` 提供）
- DESIGN → 加载 NBT + 计算 footprint / 占地区域 / 材料 / 协同分区 + 输出设计书，**写入 mempalace** `wing=build_designs, room=<projectId>`，转 AWAITING_DESIGN_APPROVAL
- AWAITING_DESIGN_APPROVAL → 等玩家 `/steve approve`（**无超时**，玩家需手动 `/steve approve` 或 `/steve halt`；设计书保留在 mempalace）
- CONSTRUCTION → `PlanBuildAction.runConstruction` 每 `BUILD_TICK_DELAY` tick 调一次 `placeNextBlock`：Steve 离目标 > 6 格时 `getNavigation().moveTo`，到了就 `level.setBlock(pos, state, 3)`，游标 +1。`nextBlockIndex >= totalBlocks` 时 transition 到 COMPLETED。
- COMPLETED → `result = ActionResult.success("Built N/total blocks for project #<id>")`，写 `built_structures` drawer

**`halt(reason)`**：玩家 `/steve halt` → 当前 stage 写到 mempalace，phase 转 FAILED，让 ReAct 决定下一步。

### 3. 拦截点

`ActionExecutor.executeTask`（line 258）：

```java
private void executeTask(Task task) {
    if ("build".equals(task.getAction())) {
        // 把"build"升级为"plan_build"，所有元信息塞进 Task.parameters
        Task planTask = new Task("plan_build", task.getParameters());
        currentAction = createAction(planTask);
    } else {
        currentAction = createAction(task);
    }
    // ...原 start() 逻辑...
}
```

LLM 无感知——它继续输出 `{"action": "build", "structure": "house_1"}`。

### 4. 聊天栏产物

#### 4.1 设计书（阶段二完成时）

发给最近玩家（`player.sendSystemMessage`）：

```
========== Steve-1 设计图 #abc123 ==========
项目: 玩家指令"建个小屋"
模板: house_1
尺寸: 9 × 6 × 9 (长 × 高 × 深)
占地: 81 平方米
方块总数: 243
材料清单:
  oak_planks    × 180 (74%)
  glass         ×  24 (10%)
  cobblestone   ×  39 (16%)
原点坐标: (123, 64, -456)
协同分区: 4 个象限, 单 Steve 承担全部
预计耗时: 约 1215 tick (≈ 60 秒)
--------------------------------------------
输入 /steve approve 开始施工, /steve halt 放弃
已归档到 mempalace: wing=build_designs/room=abc123
============================================
```

#### 4.2 施工进度（阶段三，每 50 块一次）

`plan.log` 事件，dashboard 镜像到历史面板；聊天栏不刷屏。

```
[INFO] Construction progress: 50/243
[INFO] Construction progress: 100/243
...
[INFO] Construction complete: 243/243
```

### 5. `/steve` 子命令扩展

挂到 `SteveCommands.java` 现有 `steve` 根命令下：

| 命令 | 作用 | 触发阶段 |
|------|------|---------|
| `/steve approve` | 批准当前阶段，进入下一阶段（当前是 DESIGN→CONSTRUCTION） | AWAITING_DESIGN_APPROVAL |
| `/steve halt [reason]` | 立即停止，已放置方块不撤回 | 任意 |
| `/steve status` | 输出当前 BuildProject 的所有阶段状态 | 任意（debug） |

`findTargetSteve(player)` 抽成静态复用（line 113 抽方法），优先级：先找有活跃 BuildProject 的最近 Steve，否则原 tellSteve 行为。

### 6. ReAct 反馈

| 阶段结果 | ActionResult | ReAct scratchpad |
|---------|-------------|------------------|
| 玩家 `/steve approve` 后施工完成 | `success("Built 243/243 blocks for project #abc123")` | `[OK] Build completed, house_1 at [123,64,-456]` |
| 玩家 `/steve halt` | `failure("Build halted at phase 3, 156/243 placed")` | `[FAIL] Halted at construction, 156/243 blocks. Design archived. Re-plan?` |
| 阶段一未选定可用模板 | `failure("None of the requested NBT templates could be loaded")` | `[FAIL] No usable template, try a different request?` |

**关键设计**：halt / timeout 后，**设计书留在 mempalace**（不删），LLM 下次能 query 到，玩家 `/steve status` 也能看到。这意味着 LLM 可以自主说"那个有悬空问题的小屋"——**记忆连续**。

## 关键文件改动

### 新增（4 个）

- `src/main/java/com/steve/ai/llm/react/BuildPhase.java` — 枚举
- `src/main/java/com/steve/ai/action/BuildProject.java` — 数据模型
- `src/main/java/com/steve/ai/action/actions/PlanBuildAction.java` — 核心状态机
- `src/main/java/com/steve/ai/llm/react/BuildDesignFormatter.java` — 纯静态，把 `BuildProject` 格式化成聊天栏文本（方便单测）

### 修改（3 个）

- `src/main/java/com/steve/ai/action/ActionResult.java` — 加 `Status` 枚举 + `awaitingApproval` 工厂方法（向后兼容）
- `src/main/java/com/steve/ai/action/ActionExecutor.java` — `executeTask` 拦截 build 升级为 plan_build，加 `approveCurrentBuild()` / `haltCurrentBuild()` 两个回调
- `src/main/java/com/steve/ai/command/SteveCommands.java` — 加 approve / halt / status 子命令，`findTargetSteve` 抽静态

### 不修改

- `BuildStructureAction` —— `PlanBuildAction` 内部构造并驱动它
- `ReActAgent` / `PromptBuilder` —— LLM 继续输出 `build`，拦截器翻译
- `MCPClientWrapper` / `MCPToolRegistry` —— 阶段二/四的 mempalace 写入用现有 `mempalace_add_drawer` 工具
- `SteveMemory` —— 长期记忆自动覆盖

## 复用清单（不要重新实现）

| 已有代码 | 路径 | 怎么用 |
|---------|------|-------|
| `StructureTemplateLoader.loadFromNBT(name)` | `structure/StructureTemplateLoader.java` | 阶段二加载 |
| `StructureTemplateLoader.getAvailableStructures()` | 同上 | 阶段一日志里打可用模板列表 |
| `LoadedTemplate.blocks` / `width/height/depth` / `origin` | 同上 | 阶段二数据源 + 阶段三 `placeNextBlock` 计算世界坐标 |
| `LoadedTemplate.BlockPlacement.relativePos` / `blockState` | 同上 | 阶段三 `placeNextBlock` 读取下一方块坐标和状态 |
| `SteveEntity.getNavigation().moveTo` | `entity/SteveEntity.java` | 阶段三 Steve 距离 > 6 格时走过去 |
| `ServerLevel.setBlock(pos, state, flags)` | vanilla | 阶段三 `placeNextBlock` 实际放方块 |
| `MCPToolRegistry.callTool("mempalace_add_drawer", ...)` | `mcp/MCPToolRegistry.java` | 阶段二归档设计书 + halt 时归档 `build_halted` |
| `SteveMod.getPlanEventBus().publish` | `SteveMod.java` | 阶段转换时发 `PlanPhaseChangedEvent` / `PlanLogEvent` |
| `player.sendSystemMessage(Component)` | vanilla | 阶段二设计书输出到聊天栏 |
| `/steve approve` (聊天) / dashboard Approve 按钮 | `SteveCommands` / `PlanDashboardServer` | 玩家在 AWAITING_DESIGN_APPROVAL 推进到 CONSTRUCTION |

## MemPalace 数据扩展

| Wing | Room | 写入时机 | 内容 |
|------|------|---------|------|
| `build_designs` | `<projectId>` | 阶段二完成 | 完整设计书 JSON（尺寸、材料、原点、分区、玩家指令） |
| `build_halted` | `<projectId>` | `/steve halt` 时 | halt 原因 + 阶段二设计书保留 |
| `built_structures` | `<templateName>_<projectId>` | CONSTRUCTION 完成（自动） | 原 `BuildStructureAction` 已有的归档，复用 |

**回溯查询**：`/steve status` 查 mempalace `wing=build_halted, room=*` 列出最近 5 个被中止的项目；`wing=built_structures, room=*` 列出已建成的项目。

## 验证

### 单元层面

1. **阶段一选模板**：
   - `/steve tell Steve build a house`
   - 期望日志：ReAct step 1 `action=mcp mempalace_list_drawers`，step 2 `action=build structure=house_1`
   - 期望 PlanBuildAction 收到 `template=house_1`

2. **阶段二设计书**：
   - 阶段一选完后自动进入阶段二
   - 期望聊天栏收到完整设计书（含 243 blocks、9×6×9、材料表 3 行）
   - 期望 `mempalace_query wing=build_designs` 能查到 `room=abc123`

3. **阶段二 approve**：
   - 玩家在聊天栏输入 `/steve approve`（或在 dashboard 点 Approve 按钮）
   - 期望日志 `phase: AWAITING_DESIGN_APPROVAL -> CONSTRUCTION`
   - 期望方块开始放置（`plan.log` "Construction progress: 50/243"）

4. **阶段二 halt**：
   - 阶段二等待 approve 时 `/steve halt`
   - 期望日志 `BuildProject FAILED at phase AWAITING_DESIGN_APPROVAL`
   - 期望 ReAct 收到 `[FAIL] Halted during design approval, design archived`
   - 期望 `mempalace` 里设计书**还在**（不删）

5. **阶段三施工**：
   - approve 后日志出现 `plan.log` "Construction progress: N/total"（每 50 块一次）
   - 期望最终所有方块放置完成，转 COMPLETED，发 `[OK] Build completed`

6. **halt at any time**：
   - 在阶段三施工中 `/steve halt`
   - 期望立刻停止放置，`BuildProject` 转 FAILED
   - 期望已放置方块**不撤回**（玩家想撤回用单独命令，本次不做）

7. **多 Steve 隔离**：
   - spawn 两个 Steve，同时下达 build
   - 期望每个有独立 `BuildProject.id`
   - 期望 `/steve approve` 只作用于"最近且有活跃 BuildProject 的 Steve"

### 端到端 demo（hackathon 演讲用）

1. 启动游戏，spawn `Steve-1`
2. `/steve tell Steve 在这建个房子`
3. **截图 1**：聊天栏出现设计书（评审立刻看到"哦它会先告诉我计划"）
4. dashboard 点 Approve（或聊天栏 `/steve approve`）
5. **截图 2**：施工进度行实时更新（`plan.log` "Construction progress: N/total"）
6. **截图 3**：施工完成时 CONSTRUCTION → COMPLETED 阶段切换事件，方块全部到位
7. **截图 4**：`mempalace_query wing=built_structures` 列出 `house_1_abc123`
8. 重复 1–7，但这次**不 approve**，改用 `/steve halt` 中止
9. **截图 5**：halt 后 `BuildProject` 转 FAILED，聊天栏 `Build halted at phase AWAITING_DESIGN_APPROVAL`，`mempalace_query wing=build_designs` 仍能查到设计书

## 落地顺序

1. 加 `BuildPhase` 枚举 + `BuildProject` 数据类
2. 扩展 `ActionResult` 加 `Status` 枚举（向后兼容）
3. 写 `BuildDesignFormatter`（纯函数，最容易单测）
4. 写 `PlanBuildAction` 状态机：FEASIBILITY + DESIGN + AWAITING_DESIGN_APPROVAL + CONSTRUCTION + COMPLETED/FAILED，approve 后**直接**进 CONSTRUCTION 自治放方块，无二次确认
5. `ActionExecutor` 拦截 build + 加回调（`approveCurrentBuild` / `haltCurrentBuild`）
6. `SteveCommands` 加 approve / halt / status 子命令
7. `PlanDashboardServer` 把状态镜像到 127.0.0.1:8765 的 `/events` / `/command` / `/plan` / `/chat` 端点（React + Three.js 前端）
8. 单测 `BuildDesignFormatter` + `PlanEventJson` + 手工跑 7 个验证（见上文）
