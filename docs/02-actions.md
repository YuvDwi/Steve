# 动作系统

## 概述

动作系统是 Steve AI 的核心执行单元，负责在 Minecraft 世界中执行具体任务。

## 核心类

- `ActionExecutor.java` - 基于 tick 的动作队列处理器
- `Task.java` - 动作任务数据模型
- `CollaborativeBuildManager.java` - 多 Agent 协调

## 可用动作

| 动作 | 功能 |
|------|------|
| `PlanBuildAction` | 四阶段 plan-then-build 状态机（`BuildStructureAction` 已弃用，仅保留兼容） |
| `MineBlockAction` | 智能采矿，带路径规划 |
| `BuildStructureAction` | 程序化建筑和模板建筑（支持仓库自动补给，**已弃用**，仍被 `CoreActionsPlugin` 注册供旧路径调用） |
| `PlaceBlockAction` | 单方块放置（带验证） |
| `PathfindAction` | 导航到坐标 |
| `CombatAction` | 目标战斗 |
| `FollowPlayerAction` | 跟随玩家 |
| `CraftItemAction` | 物品合成 |
| `GatherResourceAction` | 资源采集 |
| `PlaceWarehouseAction` | 放置仓库箱子 |
| `WarehouseRefillHandler` | 建造缺材料时自动从仓库补给 |
| `MCPAction` | 调用 MCP 工具（参数 `tool="serverName:toolName"`, `args={...}`） |

`CoreActionsPlugin` 通过 `ActionRegistry` 注册了 8 个基础动作：`pathfind / mine / gather / place / build / craft / attack / follow`。
`build` action 在 `ActionExecutor.createActionLegacy` 里被**拦截**到 `PlanBuildAction`（`ActionExecutor.java:334`），所以 ReAct 模式下 `action="build"` 实际走的是 `PlanBuildAction.runDesign` + `runConstruction` 四阶段流程。

## 执行流程（ReAct 主循环）

1. 用户发送自然语言指令（如 `/steve tell miner1 开采 20 铁矿石`）
2. `ActionExecutor.processNaturalLanguageCommand` 把指令加入 `pendingCommands` 队列
3. 若 `reactAgent == null && currentAction == null`，调 `drainNextCommand()` 取出队首
4. 构造 `ReActAgent(steve, command, maxSteps, obsTruncate, maxConsecutiveFailures)`，调 `startAsync(client, params)` 发起首次 LLM 调用（非阻塞）
5. 每个游戏 tick：
   - 若 `currentAction.isComplete()`，取 `ActionResult` 调 `reactAgent.feedObservation(result, client, params)` —— ReActAgent 自动触发下一轮 LLM 调用
   - 若 `reactAgent.isReadyNextStep()`，调 `consumeNextStep()` 拿到 `Task`，`executeTask(task)` 创建并 `start()` `BaseAction`
   - 若 `reactAgent.isFinished()`，把 `finalAnswer` 发到 GUI，自动 `drainNextCommand` 处理下一条
   - 若 `reactAgent.failed()`，硬切（不回退到旧 Plan-and-Execute），发错误到 GUI
6. ReAct 循环直到 LLM 输出 `is_final: true` / `stepCount >= maxSteps` / 连续解析失败 ≥ `maxConsecutiveFailures`

**重要**：玩家在 ReAct 进行中发新指令时，新指令**入队不打断**，当前 ReAct 完成后顺序处理（`stopCurrentAction` 会清空队列）。

## BuildStructureAction 实现

> **已弃用**：当前 ReAct/plan 模式全部走 `PlanBuildAction.runDesign` + `runConstruction`。
> `BuildStructureAction` 仍存在但只被 `CoreActionsPlugin` 之外的老路径调用，保留向后兼容。
> 下面流程图描述的是该旧实现，新代码请参考 `PlanBuildAction` 源码。

### 完整流程

```
用户指令: "建造房子"
    ↓
BuildStructureAction.onStart()
    ↓
1. 解析材料、尺寸、位置（看向玩家的方向 12 格处找地面）
2. tryLoadFromTemplate() → 尝试加载 NBT 模板
   ↓ 失败（目前无 .nbt 文件）
3. generateBuildPlan() → 调用 StructureGenerators 程序化生成
    ↓
4. CollaborativeBuildManager.registerBuild() → 注册协作建造
    ↓
BuildStructureAction.onTick() 每 tick:
    ↓
5. getNextBlock() → 从协作管理器获取下一个方块
    ↓
6. 材料不足？→ WarehouseRefillHandler 自动去仓库取材料
    ↓
7. 放置方块 + 粒子 + 音效
```

### 关键阶段

| 阶段 | 说明 |
|------|------|
| **位置确定** | 优先在玩家视线方向 12 格处找地面；无玩家则在 Steve 附近 2 格处 |
| **地形检测** | `findGroundLevel()` 向下/上扫描找实体地面；`isAreaSuitable()` 检查地形平整度（高度差≤2）和上方空间 |
| **模板加载** | `tryLoadFromTemplate()` → `StructureTemplateLoader.loadFromNBT()` — 启动时已扫描 `config/steve/structures/*.nbt` 并注册到 mempalace，LLM 通过 `mempalace_list_drawers` 发现 |
| **程序化生成** | `StructureGenerators.generate()` — 8 种内置建筑类型（无 .nbt 时的回退） |
| **协作建造** | `CollaborativeBuildManager` 分象限分配方块，多 Steve 并行放置 |
| **仓库补给** | 材料不足时 `WarehouseRefillHandler` 自动去最近仓库取材料，取完返回继续建造 |
| **位置归档** | CONSTRUCTION 完成后 `PlanBuildAction` 在 `CONSTRUCTION → COMPLETED` 转换时自动调 `mempalace_add_drawer(wing=built_structures)` 写入 mempalace（不再由 ReAct agent 触发） |
| **飞行** | 建造时 Steve 启用飞行 (`steve.setFlying(true)`)，完成后关闭 |

## 插件架构

动作通过 `ActionRegistry` 动态注册，支持自定义扩展。

```java
// 注册新动作
ActionRegistry.register("custom_action", CustomAction.class);
```
