# Steve AI - Minecraft AI Agent Mod

## 项目概述

**Steve AI** 是一个 Minecraft Forge 1.20.1 模组，将 AI 驱动的自主 agents（称为 "Steves"）引入游戏世界。用户可以通过命令生成 Steves 并给予自然语言指令，如"开采 20 铁矿石"或"在我附近建一座房子"。Steves 使用大语言模型（OpenAI GPT、Groq 或 Google Gemini）来理解指令、规划行动并在 Minecraft 世界中执行。

**版本**: 1.0.0
**Minecraft 版本**: 1.20.1
**Java 版本**: 17

## 快速开始

### 命令

| 命令 | 功能 |
|------|------|
| `/steve spawn <name>` | 生成新的 Steve |
| `/steve remove <name>` | 移除 Steve |
| `/steve list` | 列出所有活跃的 Steves |
| `/steve stop <name>` | 停止当前动作 |
| `/steve tell <name> <command>` | 发送自然语言指令 |
| `/steve plan <description>` | 进入 plan mode (LLM 选 NBT 模板、出设计书、等 approve) |
| `/steve approve` | 批准当前设计，直接进入 CONSTRUCTION 阶段施工 |
| `/steve halt [reason]` | 中止当前 build，已放置方块不撤回 |
| `/steve status` | 输出当前 BuildProject 的所有阶段状态（debug） |
| `/steve dashboard [/stop]` | 启动/停止外部 plan UI HTTP server (默认 127.0.0.1:8765) |

### 示例

```
/steve spawn miner1
/steve tell miner1 开采 20 铁矿石
/steve tell miner1 在我附近建一座房子
/steve tell miner1 保护我免受僵尸攻击
```

### GUI

按 **K** 打开右侧滑出面板，可滚动消息历史，支持命令历史（上下箭头）。

颜色区分:
- 🟢 绿色: 用户消息
- 🔵 蓝色: Steve 响应
- 🟠 橙色: 系统消息

## ReAct 模式（Reason + Act）

Steve 不是"想完再干"，而是"想一步干一步"：

1. LLM 看到命令 → 决定一个 Action（带 Thought 说明）
2. Steve 执行 Action → 把结果（`ActionResult`）作为 Observation 反馈给 LLM
3. LLM 根据 Observation 决定下一步
4. 直到 LLM 输出 `is_final: true` 或达到 `maxSteps`

这样 LLM 可以先调 MCP 工具查信息（如 `mempalace_list_drawers` 查可用模板），看到结果后再决定下一步该建造什么。命令排队：玩家在 ReAct 进行中发新指令会入队，当前 ReAct 完成后自动处理。

详见 [docs/01-architecture.md](01-architecture.md) §5、§6 和 `llm/react/ReActAgent.java`。

## Mempalace / MCP 集成

- **mempalace**（默认 `http://localhost:6060`）是外部 MCP 服务，存结构模板元信息和已建建筑位置
- **启动时** `StructureTemplateLoader` 扫描 `config/steve/structures/*.nbt`，注册到 mempalace
- **运行时** LLM 通过 `action="mcp"` 调 mempalace 工具查模板
- **建造完成** 写位置到 `wing=built_structures`
- **长期记忆** `SteveMemory.queryLongTermMemory()` 也走 mempalace

详见 `docs/hackathon/03-mempalace-integration.md` 和 [docs/06-llm.md](06-llm.md) §5。
