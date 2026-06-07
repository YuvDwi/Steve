# 可建造结构

Steve 当前只支持一种生成结构的方式：**NBT 模板**。

> **已废弃**：之前文档里提到的"程序化生成（`StructureGenerators`）"已删除。
> 所有 build 走 NBT 模板，模板列表由 `config/steve/structures/*.nbt` 决定。
> 无匹配 NBT 时 `PlanBuildAction.runDesign` 会 `ActionResult.failure("None of the requested NBT templates could be loaded")`，**不再有兜底生成**。
>
> 下面程序化生成结构表保留作为历史参考，**不再生效**。

## 生成流程

```
玩家指令 → LLM 解析 → ReAct 输出 action="build" → 拦截到 PlanBuildAction
  ├── 1. PlanBuildAction.runDesign
  │     └── StructureTemplateLoader.loadFromNBT(name)
  │           └── 找到 → 使用模板，按 origin 偏移在世界坐标铺开
  └── 2. 加载失败
        └── ActionResult.failure("None of the requested NBT templates could be loaded")
```

## 程序化生成结构列表（已废弃，仅供历史参考）

| 结构类型 | 别名 | 默认尺寸 | 材料 | 说明 |
|---------|------|---------|------|------|
| `house` | `home` | 9x6x9 | 橡木板、圆石、玻璃板 | 带窗户、门和金字塔屋顶的房屋 |
| `castle` | `catle`, `fort` | 14x10x14 | 石砖、圆石、玻璃板 | 带角楼、城垛和大门的城堡 |
| `tower` | — | 6x6x16 | 石砖、錾制石砖、玻璃板、深色橡木楼梯 | 带窗户和金字塔顶的塔楼 |
| `barn` | `shed` | 12x8x14 | 橡木板、橡木原木、云杉木板 | 带大门和尖顶的谷仓 |
| `modern` | `modern_house` | 9x6x9 | 石英块、平滑石头、玻璃、深色橡木板 | 大量玻璃的现代风格房屋 |
| `wall` | — | 用户指定 | 使用第一个材料 | 单层墙壁 |
| `platform` | — | 用户指定 | 使用第一个材料 | 平台/地板 |
| `box` | `cube` | 用户指定 | 使用第一个材料 | 实心方块 |

## 使用方式

```
build house
build castle
build tower
build barn
build modern
build wall
build platform
build box
```

## 材料说明

- `house` — 地板用材料1，墙壁用材料2，屋顶用材料3，窗户固定为玻璃板，门固定为橡木门
- `castle` — 固定使用石砖（地板）、圆石（墙壁）、玻璃板（窗户）
- `tower` — 固定使用石砖、錾制石砖、玻璃板、深色橡木楼梯
- `barn` — 固定使用橡木板、橡木原木、云杉木板
- `modern` — 固定使用石英块、平滑石头、玻璃、深色橡木板
- `wall`/`platform`/`box` — 使用用户指定的材料

## 自定义尺寸

```
build house with dimensions 12x8x12
build castle with width 20 height 15 depth 20
```

默认尺寸为程序化生成的推荐值。NBT 模板使用自动尺寸（从文件中读取），自定义尺寸参数会被忽略。

## NBT 模板

`PlanBuildAction` 的唯一结构来源。将 `.nbt` 文件放入运行时配置目录：

```
<minecraft>/config/steve/structures/
```

- 文件名即为结构名（如 `house.nbt` → `build house`）
- 支持多种命名格式自动匹配：`name.nbt`、`name_lower.nbt`、`snake_case.nbt`
- LLM prompt 会动态读取目录下的模板名列表（`StructureTemplateLoader.getAvailableStructures()`），供 AI 识别
- 启动时 `StructureTemplateLoader` 扫描该目录并注册到 mempalace（`wing=structure_{type}, room={name}`），LLM 通过 `mempalace_list_drawers` 发现

## 材料仓库

建造时如果材料不足，Steve 会自动去最近的仓库箱子取材料，取完返回继续建造。仓库箱子内的材料会自动补满（配置的目标数量）。

仓库通过 `config/steve/warehouses.json` 配置，详见 [配置参考 - 材料仓库](03-config.md#材料仓库配置)。
