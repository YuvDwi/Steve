# 记忆系统

## 组件

### SteveMemory.java

管理对话与动作历史：
- 用户指令历史
- Steve 响应历史
- 最近动作列表（保留最后 20 条）
- 当前目标 (`currentGoal`)：ReAct 启动时设，停止时清

**长期记忆**：通过 `queryLongTermMemory(query)` 调 `mempalace:mempalace_list_drawers(wing=steve_memory, room={steveName}, query={...})` 查询。**不再使用 NBT 持久化**。

### WorldKnowledge.java

追踪世界状态：
- 已发现的资源位置
- 空间数据
- 结构信息

在 ReAct 模式下，`buildReActUserPrompt` 每步调用 `WorldKnowledge` 重新采样，作为 observation 的一部分反馈给 LLM。

## 上下文管理

1. **对话历史**: 保留最近的交互记录
2. **世界状态**: 追踪 Steve 周围的世界变化（每次 ReAct 步重新采样）
3. **动作历史**: 记录最近执行的动作，用于避免重复

## 持久化（已迁移到 mempalace）

**变更前**：记忆数据通过 NBT 持久化到 Minecraft 存档（`saveToNBT` / `loadFromNBT`）。已删除。

**变更后**：长期记忆走 mempalace：
- `SteveMemory.queryLongTermMemory(query)` 调 `MCPToolRegistry.callTool("mempalace:mempalace_list_drawers", Map.of("wing", "steve_memory", "room", steveName, "query", query))`
- 短期动作历史保留在内存（`addAction`），跨 ReAct 会话不持久化
- 跨世界数据独立于 Minecraft 存档（存在 mempalace 外部服务）
