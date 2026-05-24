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
| `MineBlockAction` | 智能采矿，带路径规划 |
| `BuildStructureAction` | 程序化建筑和模板建筑（支持仓库自动补给） |
| `PlaceBlockAction` | 单方块放置（带验证） |
| `PathfindAction` | 导航到坐标 |
| `CombatAction` | 目标战斗 |
| `FollowPlayerAction` | 跟随玩家 |
| `CraftItemAction` | 物品合成 |
| `GatherResourceAction` | 资源采集 |
| `PlaceWarehouseAction` | 放置仓库箱子 |
| `WarehouseRefillHandler` | 建造缺材料时自动从仓库补给 |

## 执行流程

1. 用户发送自然语言指令（如 `/steve tell miner1 开采 20 铁矿石`）
2. `TaskPlanner` 调用 LLM 解析指令，生成动作序列
3. 动作被加入 `ActionExecutor` 的队列
4. 每个游戏 tick，`ActionExecutor` 处理队列中的动作
5. 动作执行结果通过 GUI 显示给用户

## BuildStructureAction 实现

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
| **模板加载** | `tryLoadFromTemplate()` → `StructureTemplateLoader.loadFromNBT()` — 目前无 `.nbt` 文件，始终返回 null |
| **程序化生成** | `StructureGenerators.generate()` — 8 种内置建筑类型 |
| **协作建造** | `CollaborativeBuildManager` 分象限分配方块，多 Steve 并行放置 |
| **仓库补给** | 材料不足时 `WarehouseRefillHandler` 自动去最近仓库取材料，取完返回继续建造 |
| **飞行** | 建造时 Steve 启用飞行 (`steve.setFlying(true)`)，完成后关闭 |

### 注意

当前**没有使用任何 NBT 模板**，完全依赖 `StructureGenerators` 的程序化生成。

## 插件架构

动作通过 `ActionRegistry` 动态注册，支持自定义扩展。

```java
// 注册新动作
ActionRegistry.register("custom_action", CustomAction.class);
```
