# 模块拼装协议 (Lego-style Module Composition)

> Status: 设计中 · 与 `02-plan-mode.md` (四阶段施工) + `01-mempalace-integration.md` (mempalace 集成) 并列。

## Context

当前 Steve 只能把 NBT 模板沿 +X 串行拼接 —— `PlanBuildAction.runDesign` L168 的 `originX += tpl.width + 1` 是它的全部铺装逻辑。要做高铁、高速公路、长城、运河这类**线状或网状**结构远远不够:

| 维度 | 现状 | 真正的乐高拼接 | 高铁/高速需求 |
|---|---|---|---|
| 朝向 | 锁死,无 rotation | 任意角度 | 至少 4 个 cardinal |
| 锚点 | 无,所有模块从同 Y 起 | 每个模块自带进/出口 | 必须有"上一块末端"概念 |
| 垂直对齐 | 全局 originY | per-module anchor | 坡道/桥/隧道 |
| 拓扑 | 单链 | 任意 | 直 + 弯 + 分叉 |

目标:加一个**通用**的模块拼装系统,LLM 用 `{name, dx, dy, dz, facing}` 描述一个模块链,系统自动:

- 从 bbox 推导每个模块的进/出口
- 沿上一模块的出口按 `{dx,dz}` 拼装(在上一模块的局部坐标系里)
- 绕 Y 轴 90° 旋转当前模块的局部坐标
- 用**同一个**坐标变换函数同时算世界方块和 dashboard 预览(零失真)

向后兼容:旧 `["house_1", "fence"]` 协议继续工作,行为零变化。NBT 文件**完全不用改**(锚点从 bbox 推导)。

---

## 1. 协议 (LLM → PlanBuildAction)

### 新协议

```json
{"structures": [
  {"name": "rail_straight_8", "dx": 0, "dy": 0, "dz": 0, "facing": "S"},
  {"name": "rail_curve_90",   "dx": 8, "dy": 0, "dz": 0, "facing": "E"}
]}
```

字段:

- `name` (string, required) — NBT 文件 stem,放在 `config/steve/structures/<name>.nbt`。
- `dx, dy, dz` (int, default 0) — **相对上一模块出口**的偏移,表达在**上一模块的局部坐标系**里(局部坐标系本身已按上一模块的 `facing` 转过)。
- `facing` (one of `N|E|S|W`, default `S`) — 绕 Y 轴 90° 旋转,作用于本模块的局部坐标。**S = +Z** 对齐 vanilla `StructureTemplate` 默认朝向。
- `anchor` (optional, **reserved**) — 协议预留,本版本忽略。

### 旧协议(继续支持,行为零变化)

```json
{"structures": ["house_1", "fence"]}
```

在 `PlanBuildAction` 入口处自动转成新协议 `{name, dx: previousWidth+1, dy: 0, dz: 0, facing: "S"}`,精确复现 `originX += width + 1` 行为。LLM / 玩家 / mempalace 旧 prompt **不需要任何改动**。

### 5 行 old vs new

```
Old: {"structures": ["house_1", "fence"]}
New: {"structures": [
  {"name": "house_1", "dx": 0, "dy": 0, "dz": 0, "facing": "S"},
  {"name": "fence",   "dx": <house_1.width+1>, "dy": 0, "dz": 0, "facing": "S"}
]}
```

**两者结果完全一致**:房子在原点,围栏在 `house_1.width + 1` 处的 +X 方向。

---

## 2. 锚点约定 (NBT 创作)

**关键:NBT 文件里不存锚点信息,锚点从 bbox 几何推导。** 作者只需要按下列规则摆结构块。

### 局部原点

NBT bbox 的 min corner 是局部 `(0, 0, 0)`。**入口**默认在该 corner 处,**出口**在 bbox 的某个面中心。

### 出口位置(per facing)

| facing | 出口位置 (local) | 含义 |
|--------|------------------|------|
| `S` (+Z) | `(width/2, 0, depth)` | +Z 面底边中点 |
| `N` (−Z) | `(width/2, 0, 0)`   | −Z 面底边中点 |
| `E` (+X) | `(width,   0, depth/2)` | +X 面底边中点 |
| `W` (−X) | `(0,     0, depth/2)` | −X 面底边中点 |

**直道**:8 格宽 × 1 格高 × 8 格深的轨道,入口在 `(0,0,0)`,出口在 `(4, 0, 8)`(S facing)。

**90° 转弯**:进/出口在两个相邻面。比如"从南进、从东出",bbox 8×8(正方形),入口 `(4, 0, 0)`(S face),出口 `(8, 0, 4)`(E face)。

### NBT 创作 5 步走

1. 在 superflat 创意世界里,放一个 structure block,准备构建 piece。
2. 摆方块,使 **入口**在 bbox min corner 的某个面上,**出口**在另一个面(可以相同面 —— 直道)。
3. 命名 + 保存:structure block "Save" → 得到 `.nbt`。
4. 放进 `config/steve/structures/<type>_<name>.nbt`。`<type>_*` 前缀自动让 mempalace 把模板注册到 `wing=structure_<type>`(沿用 `01-mempalace-integration.md` 1.3 节的约定),比如 `rail_curve_90.nbt` → `structure_rail` wing。
5. LLM 现在可以这样请求:
   ```json
   {"structures":[
     {"name":"rail_curve_90","dx":8,"dy":0,"dz":0,"facing":"E"}
   ]}
   ```
   表示"从上一块出口向东 8 格放一个 90° 转弯"。

### 命名建议

`<type>` 用单数名词,描述**结构族**而非**单个 piece**:

- `rail_straight_8`, `rail_straight_16`, `rail_curve_90`, `rail_switch`, `rail_station_2car`
- `highway_lane_straight`, `highway_onramp`, `highway_offramp`, `highway_interchange_4way`
- `wall_corner`, `wall_gate`, `wall_battlement`
- `canal_lock`, `canal_bend`

---

## 3. 旋转与坐标变换

整个系统**唯一**的旋转/偏移源是 `ModuleTransform.apply(BlockPos rel, BlockPos origin, Facing f)`:

```java
public static BlockPos apply(BlockPos rel, BlockPos origin, Facing f) {
    int x = rel.getX(), y = rel.getY(), z = rel.getZ(), rx, rz;
    switch (f) {
        case S: rx =  x; rz =  z; break;  // identity
        case W: rx =  z; rz = -x; break;  // -90°
        case N: rx = -x; rz = -z; break;  // 180°
        case E: rx = -z; rz =  x; break;  // +90°
    }
    return origin.add(rx, y, rz);
}
```

旋转规则(Y 轴向上, N=−Z, E=+X, S=+Z, W=−X):

| facing | (x, y, z) → (x', y', z') | 说明 |
|--------|--------------------------|------|
| S | ( x, y,  z) | identity |
| W | ( z, y, -x) | -90° |
| N | (-x, y, -z) | 180° |
| E | (-z, y,  x) | +90° |

**为什么这是关键设计**:把"怎么旋转"集中到一个文件,所有消费方都通过这里计算世界坐标。这意味着 3D dashboard 预览和实际放置**永远不可能错位** —— 任何 bug 都只能在这一个地方出现一次。

---

## 4. 数据模型

### 新增 `PlacedModule` (`com.steve.ai.structure`)

```java
public final class PlacedModule {
    public final LoadedTemplate template;
    public final BlockPos worldOrigin;   // 模块入口在世界里的坐标(已应用 facing)
    public final PlacedModule.Facing facing;

    public enum Facing { N, E, S, W }
}
```

### `BuildProject` 字段替换

| 旧 | 新 |
|----|----|
| `List<LoadedTemplate> templates` | `List<PlacedModule> placedModules` |
| `int currentTemplateIndex` | `int currentModuleIndex` |

其它字段(`originPos`, `materials`, `nextBlockIndex`, `blocksPlaced`, `totalBlocks`)不动。

### `Task.java` — 新 accessor

```java
public List<Map<String, Object>> getModuleListParameter(String key)
```

返回 `List<Map<String,Object>>`,跟 `getStringListParameter` 并存。`PlanBuildAction` 优先用新接口;若返回空再 fallback 到旧接口并自动转新协议。

---

## 5. 拼装算法 (`PlanBuildAction.runDesign` 替换 L154-169)

```
cursor       = project.getOriginPos()         // 从玩家 look-target 算 (见 L142)
prevExit     = cursor                          // "上一块末端"初始化为项目原点
prevFacing   = PlacedModule.Facing.S

for spec in moduleSpecs:                       // 旧协议已自动转新协议
    tpl    = StructureTemplateLoader.loadFromNBT(level, spec.name)
    facing = spec.facing ?: S
    localIn = BlockPos(spec.dx, spec.dy, spec.dz)

    worldIn = ModuleTransform.apply(localIn, prevExit, prevFacing)
    project.placedModules.add(new PlacedModule(tpl, worldIn, facing))

    prevExit   = ModuleTransform.apply(
                     ModuleTransform.exitAnchor(tpl, facing), worldIn, facing)
    prevFacing = facing

project.totalBlocks = sum(m.template.blocks.size() for m in placedModules)
emit PlanDesignReadyEvent(...)                 // 这里也走 ModuleTransform.apply
```

`placeNextBlock()` (L258-274) 和 `buildSnapshot()` (L470-510) 改遍历 `project.getPlacedModules()`,每个 block 用 `ModuleTransform.apply(tb.relativePos, m.worldOrigin, m.facing)` 算世界坐标。**两处代码完全镜像**。

---

## 6. 改动文件清单

| 路径 | 改动 |
|------|------|
| `src/main/java/com/steve/ai/structure/PlacedModule.java` | **新增** — `PlacedModule` + `Facing` |
| `src/main/java/com/steve/ai/structure/ModuleTransform.java` | **新增** — 唯一旋转源 |
| `src/main/java/com/steve/ai/action/Task.java` | 加 `getModuleListParameter(String)` |
| `src/main/java/com/steve/ai/action/BuildProject.java` | `templates` / `currentTemplateIndex` → `placedModules` / `currentModuleIndex` |
| `src/main/java/com/steve/ai/llm/ResponseParser.java` | `extractValue` 加 JsonArray-of-JsonObject 分支 |
| `src/main/java/com/steve/ai/llm/PromptBuilder.java` | 系统 + ReAct prompt 改 doc,加新示例 |
| `src/main/java/com/steve/ai/action/actions/PlanBuildAction.java` | `runDesign` 改 L154-202;`placeNextBlock` 改 L258-274;构造函数加旧协议转新 |
| `src/main/java/com/steve/ai/dashboard/PlanDashboardServer.java` | `buildSnapshot` L470-510 改遍历 `placedModules` + 走 helper |
| `src/test/java/com/steve/ai/structure/ModuleTransformTest.java` | **新增** — 32 个 rotation 用例 + 4 个 exitAnchor 用例 |

---

## 7. Verification

### 单元

`ModuleTransformTest`:
- 4 facings × 8 unit vectors = **32 个** rotation 用例
- 4 个 exitAnchor 用例(对照 §2 表格)
- 2 个兼容用例(旧 `["a","b"]` 字符串列表自动转新协议,跟手写新协议**字节级一致**)

### 端到端 Minecraft

**准备**(临时手作):
- `config/steve/structures/rail_straight_8.nbt` — 8 格直道,8×1×8 bbox
- `config/steve/structures/rail_curve_90.nbt` — 8×8 转弯,进 S 出 E

**跑**:
```
/steve dashboard
# 浏览器开 http://localhost:5173
/steve plan "build a high speed rail demo"
```

**期望 LLM 输出**(mock 或真实):
```json
{"action":"build","structures":[
  {"name":"rail_straight_8","facing":"S"},
  {"name":"rail_straight_8","dx":8,"facing":"S"},
  {"name":"rail_curve_90",  "dx":8,"facing":"E"},
  {"name":"rail_straight_8","dx":8,"facing":"E"}
]}
```

**期望**:
1. dashboard 3D 预览: 4 段轨道,先 +Z 0–16,转弯,+X 16–24
2. `/steve approve` 后世界里出现完全相同的 4 段
3. 走到每个弯点目视验证方向正确(无镜像错位)
4. dashboard 像素级 ≈ 世界位置

### 回归

```
/steve plan "建个小屋"
# 期望 LLM 输出老协议: {"structures": ["house_1"]}
/steve approve
# 期望 房子位置和上一版 Steve 完全一致
```

---

## 8. 不做什么

- 中段插入/extend API(LLM 想加模块,撤回重建)
- 任意角度(只 4 个 cardinal)
- X/Z 轴旋转(只 Y)
- B-spline / 曲线拟合
- NBT 创作工具(继续用 vanilla structure block + MCEdit)
- per-module 动态缩放、自动地形适配
- `anchor` 字段 override(协议预留,本版本忽略)
- per-module metadata(战利品/红石)

---

## 9. 复用清单(不要重新实现)

| 已有 | 路径 | 怎么用 |
|------|------|--------|
| `StructureTemplateLoader.loadFromNBT(name)` | `structure/StructureTemplateLoader.java` | runDesign 加载每个 NBT |
| `LoadedTemplate.blocks` / `width/height/depth` | 同上 | exitAnchor + blocks 遍历 |
| `BlockPos` 不可变 3-int | vanilla | 整条协议用 |
| `PlanDashboardServer.buildSnapshot()` | `dashboard/PlanDashboardServer.java` | 改遍历 `placedModules` 即可,无需新接口 |
| `mempalace_add_drawer` wing `structure_<type>` | `mcp/MCPToolRegistry.java` | 模板注册零改动,`rail_*` 仍进 `structure_rail` |
| `PlanBuildAction.runDesign` L129-214 整体架构 | 同上 | 只换 L154-169 那个循环,其它(design doc 输出、approve 流、archive)不动 |
| `ResponseParser.extractValue` JsonArray 分支 | `llm/ResponseParser.java` | 加一个 "第一个元素是 JsonObject 且有 name" 的判别子分支 |
| `PromptBuilder` 的 "build action" doc 段 | `llm/PromptBuilder.java` | 改两段,加新示例,旧示例保留 |

---

## 10. 落地顺序

1. `PlacedModule` + `Facing` enum(纯数据,最简单)
2. `ModuleTransform.apply` + `exitAnchor` + 单元测试(旋转数学独立可测)
3. `Task.getModuleListParameter` + `ResponseParser` 加分支
4. `BuildProject` 字段替换 + 所有调用点跟改
5. `PlanBuildAction.runDesign` 改写 + 构造函数加旧协议转新
6. `placeNextBlock` + `buildSnapshot` 改走 `ModuleTransform.apply`
7. `PromptBuilder` 改两段 prompt doc
8. 端到端 Minecraft 测试(高铁 demo)
9. 回归测试(老 `["house_1"]` 协议)
10. 把本文件挪到 `docs/hackathon/03-module-composition.md`,更新 `施工流程.md` 末尾的链接表
