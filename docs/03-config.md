# 配置参考

## 配置文件

`config/steve-common.toml`

## LLM 配置

```toml
[llm]
provider = "groq"  # 或 "openai", "gemini"
```

### OpenAI

```toml
[openai]
apiKey = "your-api-key"
model = "gpt-3.5-turbo"
maxTokens = 1000
temperature = 0.7
```

### Groq

```toml
[groq]
apiKey = "your-api-key"
model = "llama-3.1-70b"
```

### Gemini

```toml
[gemini]
apiKey = "your-api-key"
model = "gemini-pro"
```

## 行为配置

```toml
[behavior]
actionTickDelay = 20      # 动作检查间隔 (tick)
enableChatResponses = true
maxActiveSteves = 10      # 最大活跃 Steve 数量
buildTickDelay = 20       # 方块放置间隔 (tick, PlanBuildAction CONSTRUCTION 阶段每方块 tick 数)
creativeMode = true       # 创造模式: 材料无限, 跳过采矿
maxTemplatesPerPlan = 4   # /steve plan 一次最多拼 N 个 NBT 模板 (1-10)
```

## MCP / Mempalace 配置

```toml
[mcp]
enabled = true
# JSON 数组: [{name, url}]
servers = "[{\"name\":\"mempalace\",\"url\":\"http://localhost:6060\"}]"
timeoutMs = 30000          # MCP 工具调用超时 (ms)
```

| 字段 | 说明 |
|------|------|
| `enabled` | 是否启用 MCP 工具调用（ReAct 模式下 LLM 仍可调 `action="mcp"`） |
| `servers` | MCP server 列表，JSON 数组 |
| `timeoutMs` | 单次工具调用超时 |

默认连 mempalace（结构模板 + 位置归档）。mempalace 不可达时启动不报错，但 `mempalace_list_drawers` 等调用会失败。

## ReAct 模式配置

ReAct 是唯一模式，无需 `enabled` 开关。

```toml
[react]
maxSteps = 12                  # 最大步数, 超限强制结束
observationTruncateChars = 800 # 单条 observation 截断字符
maxConsecutiveFailures = 3     # 连续解析失败上限, 达上限失败
```

| 字段 | 说明 |
|------|------|
| `maxSteps` | LLM 决策轮上限（1-50），防止死循环 |
| `observationTruncateChars` | ActionResult 截断长度（100-4000），控制 scratchpad 大小 |
| `maxConsecutiveFailures` | 连续 JSON 解析失败上限（1-10），硬切不再回退 |

## 技术栈

- **Minecraft Forge**: 1.20.1-47.2.0
- **MCP SDK**: `io.modelcontextprotocol.sdk:mcp:2.0.0-M3`（通过 shadowLibs 打包）
- **GraalVM Polyglot**: JavaScript 代码执行
- **Resilience4j**: 熔断器、重试、限流、隔舱模式
- **Caffeine**: LLM 响应缓存
- **Commons Codec**: SHA-256 哈希（缓存键）
	
        "chiseled_stone_bricks": 128,
        "oak_door": 64,
        "torch": 256
      }
    }
  ]
}
```

## NBT 建筑模板

`config/steve/structures/*.nbt`

按 `{type}_{name}.nbt` 命名（如 `template_house_1.nbt`、`decoration_tower.nbt`、`castle.nbt`）。启动时 `StructureTemplateLoader.getAvailableStructures()` 扫描并注册到 mempalace（`wing=structure_{type}, room={name}`），LLM 通过 `mempalace_list_drawers` 发现。命名规则：

| 文件名 | type | name | mempalace wing |
|--------|------|------|----------------|
| `template_house_1.nbt` | template | house_1 | `structure_template` |
| `decoration_tower.nbt` | decoration | tower | `structure_decoration` |
| `castle.nbt`（无下划线）| default | castle | `structure_default` |
