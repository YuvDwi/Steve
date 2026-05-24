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
```

## 技术栈

- **Minecraft Forge**: 1.20.1-47.2.0
- **GraalVM Polyglot**: JavaScript 代码执行
- **Resilience4j**: 熔断器、重试、限流、隔舱模式
- **Caffeine**: LLM 响应缓存
- **Commons Codec**: SHA-256 哈希（缓存键）

## 材料仓库配置

`config/steve/warehouses.json`

首次启动时自动生成默认配置。每个仓库定义位置和材料清单，箱子内材料用完后自动补满。

```json
{
  "warehouses": [
    {
      "name": "main_base",
      "x": 0, "y": 64, "z": 0,
      "materials": {
        "oak_planks": 896,
        "cobblestone": 896,
        "stone_bricks": 896,
        "glass_pane": 320,
        "glass": 320,
        "quartz_block": 384,
        "oak_log": 256,
        "spruce_planks": 256,
        "smooth_stone": 256,
        "dark_oak_planks": 256,
        "dark_oak_stairs": 192,
        "chiseled_stone_bricks": 128,
        "oak_door": 64,
        "torch": 256
      }
    }
  ]
}
```

| 字段 | 说明 |
|------|------|
| `name` | 仓库名称（唯一标识） |
| `x/y/z` | 箱子放置坐标 |
| `materials` | 材料 ID → 目标数量（自动补货上限） |

支持配置多个仓库，Steve 建造时自动去最近的仓库取材料。
