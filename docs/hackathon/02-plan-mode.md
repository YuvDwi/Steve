# Plan 模式：四阶段施工流程映射

## Context

参考真实高速公路建设的四阶段流程（可研→勘察设计→施工→验收），把当前 Steve AI 的"LLM 一句话 → 直接施工"流程升级为**显式四阶段**，每个阶段都向玩家/系统暴露可检查的中间产物。

**目标**：
- LLM 不再"黑盒"决定建造，每次施工**前都有可审阅的计划**
- 玩家在关键节点（设计完成、施工开始、验收通过）有检查/取消权
- 失败 / 取消可以回退到上一阶段，而不是从头开始
- 阶段产物（计划书、施工日志、验收报告）全部沉淀到 mempalace，可追溯

**不做** GUI（用户明确要求）——所有交互走聊天栏 + `/steve` 子命令。

## 真实工程 → Steve AI 映射

| 阶段 | 真实工程 | Steve AI 落地 | 玩家可见产物 |
|------|---------|--------------|------------|
| **一·可研** | 必要性 / 技术 / 经济论证 | LLM 通过 `mcp mempalace_list_drawers` 查模板 → 选最匹配 → 计算材料成本 | 聊天栏："候选模板：house_1, house_2, castle，已选 house_1（理由：与'建个小屋'最匹配）" |
| **二·设计** | 初设→施工图 | 加载 NBT → 计算 footprint、占地区域、所需材料、协同分区 | 聊天栏：完整**设计图纸**（见下文 4.1），等待 `/steve approve` |
| **三·施工** | 清表→骨架→血肉 | 拆三子阶段：① 清表 ② 主体 ③ 装饰 | 进度行 `[施工] 阶段3.1/3 清表 0/120 blocks`，可 `/steve halt` 暂停 |
| **四·验收** | 交工→竣工 | 自检：占地区域方块完整？无悬空？方块类型匹配设计？ | 聊天栏验收报告：`✓ 243/243 blocks placed, ✓ 9x6x9 footprint, ✓ no floating blocks`，等待 `/steve accept` |

## 架构

### 1. 新数据模型

**`BuildPhase` 枚举**（`llm/react/BuildPhase.java` 新增）：

```java
public enum BuildPhase {
    FEASIBILITY,    // 阶段一：选模板
    DESIGN,         // 阶段二：出图纸
    CONSTRUCTION,   // 阶段三：施工
    ACCEPTANCE,     // 阶段四：验收
    COMPLETED,      // 全部完成
    FAILED          // 任意阶段失败
}
```

**`BuildProject` 数据类**（`action/BuildProject.java` 新增）——一个建造项目的全部上下文：

```java
public class BuildProject {
    String id;                       // UUID, 用于多 Steve 隔离
    SteveEntity steve;
    String command;                  // 玩家原始指令
    String selectedTemplate;         // 阶段一选定
    LoadedTemplate template;         // 阶段二加载
    BlockPos originPos;              // 施工原点
    Map<Block,Integer> materials;    // 阶段二计算
    BuildPhase phase;                // 当前阶段
    BuildPhase lastApproved;         // 玩家最后 approve 的阶段
    int blocksPlaced;                // 阶段三进度
    List<String> acceptanceLog;      // 阶段四自检项
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
FEASIBILITY (选模板) → DESIGN (出图纸) → AWAITING_DESIGN_APPROVAL → CONSTRUCTION → AWAITING_ACCEPTANCE → COMPLETED
                                          ↑ halt                          ↑ halt
                                          |                                |
                                          └────────── FAILED ←──────────────┘
```

每个阶段都是 `onTick` 里的一个 `switch (project.phase)`，推进条件：
- FEASIBILITY → 调用 `MCPAction` 查 mempalace，LLM 给的 `template` 参数驱动选定
- DESIGN → 加载 NBT + 计算 + 输出设计书，**写入 mempalace** `wing=build_designs, room=<projectId>`，转 AWAITING_DESIGN_APPROVAL
- AWAITING_DESIGN_APPROVAL → 等玩家 `/steve approve`（30s 超时则自动 cancel）
- CONSTRUCTION → 调 `CollaborativeBuildManager`，每 tick 放 N 块，统计进度
- AWAITING_ACCEPTANCE → 跑自检（占地区域方块数量、类型匹配、悬空检查），写验收报告到 mempalace `wing=build_acceptance, room=<projectId>`，等玩家 `/steve accept`
- COMPLETED → `result = ActionResult.success("Build completed, design archived to mempalace")`，写 `built_structures` drawer

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
30 秒内输入 /steve approve 开始施工, /steve halt 放弃
已归档到 mempalace: wing=build_designs/room=abc123
============================================
```

#### 4.2 施工进度（阶段三，每 5 秒一次）

```
[施工进度] abc123 阶段 3/4 主体建造 156/243 blocks (64%)
```

#### 4.3 验收报告（阶段四完成时）

```
========== 验收报告 #abc123 ==========
[✓] 方块完整: 243/243 placed
[✓] 占地区域匹配: 9 × 6 × 9 footprint
[✓] 方块类型校验: 243/243 匹配设计 (容差 0)
[✗] 悬空检查: 2 个 oak_planks 悬空 at (125,71,-456), (125,71,-457)
[✓] 协同一致性: 单 Steve 施工, 无冲突
----------------------------------------
输入 /steve accept 正式交付, /steve halt 视为失败
已归档: wing=build_acceptance/room=abc123
======================================
```

### 5. `/steve` 子命令扩展

挂到 `SteveCommands.java` 现有 `steve` 根命令下：

| 命令 | 作用 | 触发阶段 |
|------|------|---------|
| `/steve approve` | 批准当前阶段，进入下一阶段 | AWAITING_DESIGN_APPROVAL, AWAITING_ACCEPTANCE |
| `/steve halt [reason]` | 立即停止，回退到上一稳定阶段 | 任意 |
| `/steve status` | 输出当前 BuildProject 的所有阶段状态 | 任意（debug） |
| `/steve accept` | 验收通过，触发最终归档 | AWAITING_ACCEPTANCE |

`findTargetSteve(player)` 抽成静态复用（line 113 抽方法），优先级：先找有活跃 BuildProject 的最近 Steve，否则原 tellSteve 行为。

### 6. ReAct 反馈

| 阶段结果 | ActionResult | ReAct scratchpad |
|---------|-------------|------------------|
| 玩家 `/steve approve` 后施工完成 | `success("Build completed")` | `[OK] Build completed, house_1 at [123,64,-456]` |
| 玩家 `/steve halt` | `failure("Halted by player at phase 3, 156/243 placed")` | `[FAIL] Halted at construction, 156/243 blocks. Design archived. Re-plan?` |
| 30s 超时未 approve | `failure("Phase 2 design approval timeout (30s), design archived for review")` | `[FAIL] Design not approved in 30s. Design still in mempalace wing=build_designs/room=abc123. Continue?` |
| 验收失败（如悬空） | `failure("Acceptance failed: 2 floating blocks detected")` | `[FAIL] Build has 2 floating blocks, fix or accept?` |

**关键设计**：halt / timeout 后，**设计书留在 mempalace**（不删），LLM 下次能 query 到，玩家 `/steve status` 也能看到。这意味着 LLM 可以自主说"那个有悬空问题的小屋"——**记忆连续**。

## 关键文件改动

### 新增（4 个）

- `src/main/java/com/steve/ai/llm/react/BuildPhase.java` — 枚举
- `src/main/java/com/steve/ai/action/BuildProject.java` — 数据模型
- `src/main/java/com/steve/ai/action/actions/PlanBuildAction.java` — 核心状态机
- `src/main/java/com/steve/ai/llm/react/BuildDesignFormatter.java` — 纯静态，把 `BuildProject` 格式化成聊天栏文本（方便单测）

### 修改（4 个）

- `src/main/java/com/steve/ai/action/ActionResult.java` — 加 `Status` 枚举 + `awaitingApproval` 工厂方法（向后兼容）
- `src/main/java/com/steve/ai/action/ActionExecutor.java` — `executeTask` 拦截 build 升级为 plan_build，加 `approveCurrentBuild()` / `haltCurrentBuild()` / `acceptCurrentBuild()` 三个回调
- `src/main/java/com/steve/ai/command/SteveCommands.java` — 加 approve / halt / status / accept 子命令，`findTargetSteve` 抽静态
- `src/main/java/com/steve/ai/action/CollaborativeBuildManager.java` — 暴露 `getBuildProgress(projectId)`（给施工进度行用）

### 不修改

- `BuildStructureAction` —— `PlanBuildAction` 内部构造并驱动它
- `ReActAgent` / `PromptBuilder` —— LLM 继续输出 `build`，拦截器翻译
- `MCPClientWrapper` / `MCPToolRegistry` —— 阶段二/四的 mempalace 写入用现有 `mempalace_add_drawer` 工具
- `SteveMemory` —— 长期记忆自动覆盖

## 复用清单（不要重新实现）

| 已有代码 | 路径 | 怎么用 |
|---------|------|-------|
| `StructureTemplateLoader.loadFromNBT(name)` | `structure/StructureTemplateLoader.java` | 阶段二加载 |
| `LoadedTemplate.blocks` / `width/height/depth` | 同上 | 阶段二数据源 |
| `BlockPlacement.pos` / `BlockPlacement.block` | `structure/BlockPlacement.java` | 阶段三放置 |
| `BuildStructureAction.findNearestPlayer` | `action/actions/BuildStructureAction.java` | 抽 package-private 静态 |
| `BuildStructureAction.countMaterialsNeeded` | 同上 | 抽 package-private 静态 |
| `CollaborativeBuildManager.registerBuild` | `action/CollaborativeBuildManager.java` | 阶段三主体施工 |
| `CollaborativeBuildManager.getNextBlock` | 同上 | 阶段三每 tick 取一块 |
| `MCPToolRegistry.callTool("mempalace_add_drawer", ...)` | `mcp/MCPToolRegistry.java` | 阶段二/四归档设计书/验收报告 |
| `MCPToolRegistry.callTool("mempalace_query", ...)` | 同上 | 阶段一查模板时 LLM 通过 mcp action 用 |
| `ActionContext.publishEvent` | `execution/ActionContext.java` | 阶段转换时发 `StateTransitionEvent` |
| `player.sendSystemMessage(Component)` | vanilla | 阶段产物输出 |
| `mc.player.connection.sendCommand("steve approve")` | `client/SteveGUI.java` 现有 pattern | 玩家输入 |

## MemPalace 数据扩展

| Wing | Room | 写入时机 | 内容 |
|------|------|---------|------|
| `build_designs` | `<projectId>` | 阶段二完成 | 完整设计书 JSON（尺寸、材料、原点、分区、玩家指令） |
| `build_progress` | `<projectId>` | 阶段三每 30 秒 | 当前进度快照，方便断线恢复（可选） |
| `build_acceptance` | `<projectId>` | 阶段四完成 | 验收报告（含失败项） |
| `built_structures` | `<templateName>_<projectId>` | 阶段四 `/steve accept` 后 | 原 `BuildStructureAction` 已有的归档，复用 |

**回溯查询**：`/steve status` 查 mempalace `wing=build_acceptance, room=*` 列出最近 5 个项目。

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
   - 30 秒内 `/steve approve`
   - 期望日志 `phase: DESIGN -> CONSTRUCTION`
   - 期望方块开始放置

4. **阶段二 halt**：
   - 阶段二等待 approve 时 `/steve halt`
   - 期望日志 `BuildProject FAILED at phase DESIGN`
   - 期望 ReAct 收到 `[FAIL] Halted during design approval, design archived`
   - 期望 `mempalace` 里设计书**还在**（不删）

5. **阶段二 timeout**：
   - 30 秒不操作
   - 期望日志 `Design approval timeout (30s)`
   - 期望 ReAct 收到 timeout 失败

6. **阶段三施工**：
   - approve 后日志出现 `[施工进度] abc123 156/243 blocks (64%)`
   - 期望最终所有方块放置完成

7. **阶段四验收**：
   - 施工完成自动进入阶段四
   - 期望聊天栏验收报告（全部 ✓ 或部分 ✗）
   - 期望 `mempalace_add_drawer wing=build_acceptance` 成功

8. **阶段四 accept**：
   - 验收通过后 `/steve accept`
   - 期望 `built_structures` 写入（与现有行为一致）
   - 期望 ReAct 收到 `[OK] Build completed`

9. **halt at any time**：
   - 在阶段三施工中 `/steve halt`
   - 期望立刻停止放置，`BuildProject` 转 FAILED
   - 期望已放置方块**不撤回**（玩家想撤回用单独命令，本次不做）

10. **多 Steve 隔离**：
    - spawn 两个 Steve，同时下达 build
    - 期望每个有独立 `BuildProject.id`
    - 期望 `/steve approve` 只作用于"最近且有活跃 BuildProject 的 Steve"

### 端到端 demo（hackathon 演讲用）

1. 启动游戏，spawn `Steve-1`
2. `/steve tell Steve 在这建个房子`
3. **截图 1**：聊天栏出现设计书（评审立刻看到"哦它会先告诉我计划"）
4. `/steve approve`
5. **截图 2**：施工进度行实时更新
6. **截图 3**：验收报告
7. `/steve accept`
8. **截图 4**：`mempalace_query wing=built_structures` 列出 `house_1_abc123`
9. 重复 1–8，但这次**不 approve，等 30 秒**
10. **截图 5**：超时后 ReAct 自动说"玩家 30 秒没回应，是不喜欢这个位置吗？我换个近一点的地方"（这个能力由 ReAct 的失败反馈自然涌现）

## 落地顺序

1. 加 `BuildPhase` 枚举 + `BuildProject` 数据类
2. 扩展 `ActionResult` 加 `Status` 枚举（向后兼容）
3. 写 `BuildDesignFormatter`（纯函数，最容易单测）
4. 写 `PlanBuildAction` 状态机（先做 FEASIBILITY + DESIGN + approve + halt + timeout，**后做** CONSTRUCTION + ACCEPTANCE，分两 PR 稳一点）
5. `ActionExecutor` 拦截 build + 加回调
6. `SteveCommands` 加 approve / halt / status / accept
7. `CollaborativeBuildManager` 加 `getBuildProgress`
8. 单测 `BuildDesignFormatter` + 手工跑 10 个验证
9. **PR1**: Plan + Design + Approve/Halt/Timeout
10. **PR2**: Construction + Acceptance + Accept
