# 核心架构

## 目录结构

```
src/main/java/com/steve/ai/
├── SteveMod.java              # 模组主入口 (Forge mod)
├── action/                    # 动作执行系统
│   ├── ActionExecutor.java    # ReAct 调度器（命令排队 + 步骤分发）
│   ├── CollaborativeBuildManager.java  # 多 Agent 协调
│   ├── BuildProject.java      # 四阶段 plan-then-build 项目数据模型
│   ├── Task.java              # 动作任务数据模型
│   ├── ActionResult.java
│   └── actions/               # 独立动作实现 (含 MCPAction)
│       └── PlanBuildAction.java # 四阶段 plan 模式状态机 (FEASIBILITY → DESIGN → ... → COMPLETED)
├── client/                    # 客户端 GUI
│   ├── SteveGUI.java          # 滑出式面板 GUI (按 K 打开)
│   └── KeyBindings.java
├── command/                   # Minecraft 命令
│   └── SteveCommands.java     # /steve spawn/tell/plan/approve/halt/status/dashboard 等
├── config/                     # 配置处理
│   ├── SteveConfig.java       # ForgeConfigSpec, 含 [mcp]/[react]/[dashboard] 段　　　　
├── dashboard/                  # 外部 plan UI HTTP server
│   ├── PlanDashboardServer.java  # 127.0.0.1:8765, /events + /command + /chat + /plan
│   └── PlanEventJson.java     # PlanEvent → JSON 序列化
├── entity/                     # Minecraft 实体类
│   ├── SteveEntity.java       # 自定义实体 (PathfinderMob)
│   └── SteveManager.java      # 管理所有活跃的 Steves
├── event/                      # 事件总线系统
│   ├── EventBus.java, SimpleEventBus.java
│   └── plan/                  # PlanEvent 标记接口 + 7 个事件 POJO
│       ├── PlanEvent.java
│       ├── PlanCreatedEvent.java
│       ├── PlanDesignReadyEvent.java
│       ├── PlanPhaseChangedEvent.java
│       ├── PlanApprovedEvent.java
│       ├── PlanHaltedEvent.java
│       ├── PlanLogEvent.java
│       └── PlanChatEvent.java
├── execution/                  # 状态机、拦截器
│   ├── AgentStateMachine.java
│   ├── ActionContext.java
│   └── InterceptorChain.java
├── llm/                        # LLM 集成
│   ├── TaskPlanner.java       # 编排 LLM 调用 + 暴露异步客户端
│   ├── PromptBuilder.java     # 构建系统/用户/ReAct 提示词
│   ├── ResponseParser.java    # 解析 LLM 响应 (含 parseReActStep)
│   ├── OpenAIClient.java, GroqClient.java, GeminiClient.java
│   ├── async/                 # 异步非阻塞客户端 (AsyncOpenAIClient 等)
│   ├── react/                 # ReAct 主循环 + plan 模式辅助
│   │   ├── ReActAgent.java        # ReAct (Reason + Act) 主循环
│   │   ├── BuildPhase.java        # FEASIBILITY/DESIGN/.../COMPLETED 枚举
│   │   └── BuildDesignFormatter.java  # BuildProject → 聊天栏设计书文本
│   └── resilience/            # 熔断器、重试、限流
├── mcp/                        # MCP (Model Context Protocol) 集成
│   ├── MCPToolRegistry.java   # 多 MCP server 单例注册中心
│   ├── MCPClientWrapper.java  # McpSyncClient 包装
│   └── MCPToolConverter.java  # 工具描述 → 提示词段
├── memory/                     # 记忆和知识系统
│   ├── SteveMemory.java       # 短期动作历史 + mempalace 长期记忆查询
│   └── WorldKnowledge.java    # 世界状态追踪
├── plugin/                     # 插件架构
│   ├── ActionRegistry.java    # 动态动作工厂
│   ├── ActionFactory.java, ActionPlugin.java
│   ├── CoreActionsPlugin.java # 8 个基础动作注册 (pathfind/mine/gather/place/build/craft/attack/follow)
│   └── PluginManager.java
├── structure/                  # 建筑生成 + 模板管理
│   ├── StructureTemplateLoader.java  # 扫描 NBT + 注册到 mempalace
│   ├── StructureRegistry.java        # 模板索引
│   └── BlockPlacement.java
└── util/                       # 通用工具
```

## 核心组件

### 1. 实体系统 (`SteveEntity`)

自定义实体继承 `PathfinderMob`，支持 Minecraft 原生路径规划。

**属性配置**:
- 生命值: 20
- 移动速度: 0.25
- 攻击力: 8
- 跟随距离: 48

### 2. LLM 集成

支持三个提供商，通过 `TaskPlanner` 统一编排：

| 提供商 | 模型 | 特点 |
|--------|------|------|
| OpenAI | GPT-3.5-turbo / GPT-4 | 通用能力强 |
| Groq | llama-3.1-8b-instant | 低延迟 |
| Gemini | gemini-1.5-flash | Google 生态 |

**关键特性**:
- 异步非阻塞调用（`AsyncLLMClient`，游戏永不掉帧）
- 40-60% 缓存命中率（Caffeine + SHA-256 键）
- 熔断器模式（Resilience4j）
- 主提供商失败时自动切换到 Groq
- **ReAct 模式**：LLM 每步决定一个 Action + 参数，根据 Observation 反馈再决定下一步
- **MCP 工具**：通过 `MCPToolRegistry` 调用外部工具（默认连接 mempalace）

### 3. 动作系统

基于 tick 的增量执行，动作跨多个游戏 tick 完成，防止服务器卡顿。

**插件架构**: 动作通过 `ActionRegistry` 动态注册，支持扩展。

### 4. 多 Agent 协作 (`CollaborativeBuildManager`)

当多个 Steves 协同建造时：
- 结构分为 **4 象限**（西北、东北、西南、东南）
- 每个 Steve claim 一个象限，从底部向上建造
- 使用 `ConcurrentHashMap` 保证线程安全
- Agent 提前完成时动态重平衡

### 5. 代码执行引擎

使用 **GraalVM JavaScript** 引擎执行 LLM 生成的脚本代码。

## 关键设计决策

### 1. Tick-Based Execution
动作在多个游戏 tick 中增量执行，避免阻塞游戏线程。

### 2. ReAct（Reason + Act）主循环
LLM 不再一次性规划全部动作，而是按 Thought → Action → Observation 循环执行：每步决定一个 action，action 完成后把 ActionResult 作为 observation 反馈给 LLM，由 LLM 决定下一步，直到输出 `is_final: true` 或达到 `maxSteps` 终止。命令排队：玩家在 ReAct 进行中发新指令时入队 `pendingCommands`，当前 ReAct 完成后自动处理下一条。

### 3. Async Non-Blocking
使用 `CompletableFuture` 确保游戏线程永远不被 LLM 调用阻塞。

### 4. Multi-Agent Coordination
使用确定性空间划分（象限），而非动态协商，提高效率。

### 5. ReAct Agent (`llm/react/ReActAgent.java`)
ReAct 主循环位于 `com.steve.ai.llm.react.ReActAgent`。状态机：

```
[ReAct step N] sendAsync(prompt + scratchpad)
  → parseReActStep(LLM response)
    ├─ is_final=true                          → 标记 finished, finalAnswer
    ├─ is_final=true and tasks.size()==1      → 先派发 task, 等 observation 落地后再 finish (FINAL-with-task 延迟)
    ├─ tasks.size()==1 and !is_final          → 设 pendingStep, 等 game thread feedObservation
    └─ 解析失败/无 action                     → 把错误喂回 scratchpad, 继续下一轮
[game tick]
  reactAgent.consumeNextStep() → executeTask(task) → BaseAction
  BaseAction.isComplete() → reactAgent.feedObservation(ActionResult) → 触发下一轮
```

终止条件：`is_final` / `stepCount >= maxSteps` / 连续解析失败 ≥ `maxConsecutiveFailures`。Scratchpad 超过 12k 字符时裁掉最早的 step。

### 6. MCP 工具桥接 (`mcp/MCPToolRegistry.java`)
- 启动时连接所有配置的 MCP server（默认 mempalace @ `http://localhost:6060`）
- 同步客户端 `McpSyncClient`（`McpClient.sync(transport)`）
- 工具列表注入到 `PromptBuilder` 的 `AVAILABLE MCP TOOLS` 段
- LLM 输出 `action="mcp"` → `MCPAction` → `MCPToolRegistry.callTool()`

### 7. Mempalace 知识库
- 启动时 `StructureTemplateLoader.getAvailableStructures()` 扫描 `config/steve/structures/*.nbt`，按 `{type}_{name}` 命名规则解析后注册到 mempalace（`wing=structure_{type}, room={name}`），用 `mempalace_list_drawers` 验证
- 运行时 LLM 通过 `mempalace_list_drawers` / `mempalace_get_drawer` 查询模板
- 建造完成后写回 `wing=built_structures` 记录位置
- `SteveMemory` 通过 `mempalace:mempalace_list_drawers` 查询长期记忆，不再用 NBT 持久化


	
