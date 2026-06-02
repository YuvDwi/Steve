# LLM 集成

## 支持的提供商

| 提供商 | 模型 | 特点 |
|--------|------|------|
| OpenAI | GPT-3.5-turbo | 通用能力强 |
| Groq | llama-3.1-70b | 低延迟 |
| Gemini | gemini-pro | Google 生态 |

## 核心组件

- `llm/TaskPlanner.java` - LLM 调用编排（暴露 `getAsyncClient()` 和 `buildReActParams()`）
- `llm/PromptBuilder.java` - 构建提示词（`buildSystemPrompt` / `buildUserPrompt` / `buildReActSystemPrompt` / `buildReActUserPrompt`）
- `llm/ResponseParser.java` - 解析 LLM 响应（`parseAIResponse` 旧 + `parseReActStep` 新）
- `llm/OpenAIClient.java`, `GroqClient.java`, `GeminiClient.java` - 同步客户端
- `llm/async/AsyncOpenAIClient.java`, `AsyncGroqClient.java`, `AsyncGeminiClient.java` - 异步客户端（Java HttpClient）
- `llm/resilience/ResilientLLMClient.java`, `LLMFallbackHandler.java` - 熔断器 + 降级
- `llm/react/ReActAgent.java` - **ReAct 主循环**（Thought/Action/Observation）
- `mcp/MCPToolRegistry.java` - 多 MCP server 单例注册中心
- `mcp/MCPClientWrapper.java` - 同步 MCP 客户端（`McpSyncClient`）
- `mcp/MCPToolConverter.java` - 工具列表 → 提示词段
- `action/actions/MCPAction.java` - 执行 `action="mcp"` 任务

## 关键特性

### 1. 异步非阻塞调用
- `AsyncLLMClient.sendAsync(prompt, params)` 返回 `CompletableFuture<LLMResponse>`
- `AsyncOpenAIClient` 用 Java `HttpClient.sendAsync`，30 秒超时
- 读 `params.get("systemPrompt")` 注入 system message

### 2. 缓存
- `LLMCache` 用 Caffeine
- 40-60% 缓存命中率
- SHA-256 哈希作为缓存键
- 命中后短路 LLM 调用

### 3. 熔断器模式
- `ResilientLLMClient` 包装基础异步客户端
- 主提供商失败时自动切换到 Groq
- 支持重试、限流、隔舱模式

### 4. ReAct Agent
- 路径：`com.steve.ai.llm.react.ReActAgent`
- 状态机：循环 `sendAsync(prompt + scratchpad)` → `parseReActStep` → 等 `feedObservation` → 下一轮
- 终止：`is_final` / `stepCount >= maxSteps` / 连续解析失败 ≥ `maxConsecutiveFailures`
- Scratchpad 软上限 12k 字符，自动裁剪最早 step
- 线程模型：LLM 调在 client 线程池；`consumeNextStep`/`feedObservation` 在 game thread

### 5. MCP 工具桥接
- 启动时 `MCPToolRegistry.init()` 连接所有配置的 MCP server（默认 mempalace @ `http://localhost:6060`）
- `MCPClientWrapper` 用 `McpClient.sync(transport)` 同步客户端
- 工具列表注入 `PromptBuilder` 的 `AVAILABLE MCP TOOLS` 段
- LLM 输出 `action="mcp"` → `MCPAction` → `MCPToolRegistry.callTool()`

## ParsedResponse 字段

```java
public static class ParsedResponse {
    private final String reasoning;    // Plan-and-Execute 模式: 简短想法
    private final String plan;         // Plan-and-Execute 模式: 动作描述
    private final List<Task> tasks;    // 旧模式: 多个任务; ReAct 模式: 单个任务或空
    private final boolean isFinal;     // ReAct 模式: 是否完成
    private final String finalAnswer;  // ReAct 模式: 给用户的最终回答
}
```

- `parseReActStep(text)` — 解析 ReAct 单步 JSON（`{thought, action, parameters, is_final?}`）
- `parseAIResponse(text)` — 解析旧 Plan-and-Execute JSON（`{reasoning, plan, tasks[]}`），仍保留向后兼容

## 配置

`config/steve-common.toml`:

```toml
[ai]
provider = "openai"  # 或 "groq", "gemini"

[openai]
apiKey = "your-key"
model = "gpt-4-turbo-preview"
maxTokens = 8000
temperature = 0.7
baseUrl = ""  # 自定义 OpenAI 兼容端点

[mcp]
enabled = true
servers = "[{\"name\":\"mempalace\",\"url\":\"http://localhost:6060\"}]"
timeoutMs = 30000

[react]
maxSteps = 12
observationTruncateChars = 800
maxConsecutiveFailures = 3
```
