# 实体管理 - Steve 生命周期管理

## 概述

SteveManager 负责管理所有活跃 Steve 实体的生命周期，包括生成、查找、移除和清理。使用 ConcurrentHashMap 保证线程安全，支持按名称和 UUID 双重索引。

## 架构设计

```
SteveManager
├── 数据结构
│   ├── activeSteves: Map<String, SteveEntity>  (按名称索引)
│   └── stevesByUUID: Map<UUID, SteveEntity>    (按 UUID 索引)
├── 生命周期管理
│   ├── spawnSteve() → 生成新 Steve
│   ├── removeSteve() → 移除 Steve
│   └── clearAllSteves() → 清除所有 Steve
├── 查询方法
│   ├── getSteve(name) → 按名称查找
│   ├── getSteve(uuid) → 按 UUID 查找
│   ├── getAllSteves() → 获取所有
│   └── getSteveNames() → 获取所有名称
└── 维护任务
    └── tick() → 清理死亡/移除的 Steve
```

## 核心设计决策

### 1. 双重索引

支持按名称和 UUID 两种方式查找：

```java
private final Map<String, SteveEntity> activeSteves;  // 名称 → 实体
private final Map<UUID, SteveEntity> stevesByUUID;     // UUID → 实体

// 按名称查找
public SteveEntity getSteve(String name) {
    return activeSteves.get(name);
}

// 按 UUID 查找
public SteveEntity getSteve(UUID uuid) {
    return stevesByUUID.get(uuid);
}
```

**优势**：
- 名称查找：人类友好（用于命令）
- UUID 查找：系统内部使用（用于事件、网络）

### 2. 线程安全

使用 `ConcurrentHashMap` 保证并发安全：

```java
private final Map<String, SteveEntity> activeSteves = new ConcurrentHashMap<>();
private final Map<UUID, SteveEntity> stevesByUUID = new ConcurrentHashMap<>();
```

**操作安全性**：
- `put()`：原子性
- `get()`：无锁读取
- `remove()`：原子性
- `containsKey()`：无锁检查

### 3. 生成流程

```java
public SteveEntity spawnSteve(ServerLevel level, Vec3 position, String name) {
    // 1. 检查名称是否已存在
    if (activeSteves.containsKey(name)) {
        LOGGER.warn("Steve name '{}' already exists", name);
        return null;
    }

    // 2. 检查是否达到最大数量限制
    int maxSteves = SteveConfig.MAX_ACTIVE_STEVES.get();
    if (activeSteves.size() >= maxSteves) {
        LOGGER.warn("Max Steve limit reached: {}", maxSteves);
        return null;
    }

    // 3. 创建实体
    SteveEntity steve = new SteveEntity(SteveMod.STEVE_ENTITY.get(), level);

    // 4. 设置属性
    steve.setSteveName(name);
    steve.setPos(position.x, position.y, position.z);

    // 5. 添加到世界
    boolean added = level.addFreshEntity(steve);
    if (added) {
        // 6. 注册到索引
        activeSteves.put(name, steve);
        stevesByUUID.put(steve.getUUID(), steve);
        return steve;
    }

    return null;
}
```

### 4. 移除流程

```java
public boolean removeSteve(String name) {
    // 1. 从名称索引移除
    SteveEntity steve = activeSteves.remove(name);
    if (steve != null) {
        // 2. 从 UUID 索引移除
        stevesByUUID.remove(steve.getUUID());

        // 3. 从世界中移除实体
        steve.discard();

        return true;
    }
    return false;
}
```

### 5. 自动清理

每 tick 检查并清理死亡或移除的 Steve：

```java
public void tick(ServerLevel level) {
    Iterator<Map.Entry<String, SteveEntity>> iterator = activeSteves.entrySet().iterator();

    while (iterator.hasNext()) {
        Map.Entry<String, SteveEntity> entry = iterator.next();
        SteveEntity steve = entry.getValue();

        // 检查 Steve 是否仍然有效
        if (!steve.isAlive() || steve.isRemoved()) {
            // 从 UUID 索引移除
            stevesByUUID.remove(steve.getUUID());

            // 从迭代器中移除（安全方式）
            iterator.remove();

            LOGGER.info("Cleaned up Steve: {}", entry.getKey());
        }
    }
}
```

**触发清理的情况**：
- Steve 被杀死（isAlive() = false）
- Steve 被命令移除（isRemoved() = true）
- 世界卸载

## 查询方法

### 获取所有 Steve

```java
public Collection<SteveEntity> getAllSteves() {
    return Collections.unmodifiableCollection(activeSteves.values());
}
```

**返回不可修改集合**，防止外部修改内部状态。

### 获取所有名称

```java
public List<String> getSteveNames() {
    return new ArrayList<>(activeSteves.keySet());
}
```

**返回新列表**，避免暴露内部键集合。

### 获取活跃数量

```java
public int getActiveCount() {
    return activeSteves.size();
}
```

## 与其他系统集成

### 与命令系统集成

```java
// /steve spawn <name>
private static int spawnSteve(CommandContext<CommandSourceStack> context) {
    String name = StringArgumentType.getString(context, "name");
    SteveManager manager = SteveMod.getSteveManager();

    Vec3 spawnPos = calculateSpawnPosition(context);
    SteveEntity steve = manager.spawnSteve(serverLevel, spawnPos, name);

    if (steve != null) {
        source.sendSuccess(() -> Component.literal("Spawned Steve: " + name), true);
        return 1;
    }
    return 0;
}

// /steve remove <name>
private static int removeSteve(CommandContext<CommandSourceStack> context) {
    String name = StringArgumentType.getString(context, "name");
    SteveManager manager = SteveMod.getSteveManager();

    if (manager.removeSteve(name)) {
        source.sendSuccess(() -> Component.literal("Removed Steve: " + name), true);
        return 1;
    }
    return 0;
}

// /steve list
private static int listSteves(CommandContext<CommandSourceStack> context) {
    SteveManager manager = SteveMod.getSteveManager();
    List<String> names = manager.getSteveNames();

    source.sendSuccess(() -> Component.literal("Active Steves: " + String.join(", ", names)), true);
    return 1;
}
```

### 与 GUI 集成

```java
// SteveGUI.java
private static List<String> parseTargetSteves(String command) {
    List<String> targets = new ArrayList<>();

    // "all steves" 命令
    if (command.startsWith("all steves ") || command.startsWith("all ")) {
        var allSteves = SteveMod.getSteveManager().getAllSteves();
        for (SteveEntity steve : allSteves) {
            targets.add(steve.getSteveName());
        }
        return targets;
    }

    // 解析特定 Steve 名称
    var allSteves = SteveMod.getSteveManager().getAllSteves();
    List<String> availableNames = new ArrayList<>();
    for (SteveEntity steve : allSteves) {
        availableNames.add(steve.getSteveName().toLowerCase());
    }

    // 匹配名称
    String[] parts = command.split(",");
    for (String part : parts) {
        String firstWord = part.trim().split(" ")[0].toLowerCase();
        if (availableNames.contains(firstWord)) {
            targets.add(firstWord);
        }
    }

    return targets;
}
```

### 与任务规划集成

```java
// ActionExecutor.java
private void drainNextCommand() {
    String next = commandQueue.poll();
    if (next == null) return;

    Map<String, Object> reactBaseParams = getTaskPlanner().buildReActParams();
    reactAgent = new ReActAgent(steve, next,
        SteveConfig.REACT_MAX_STEPS.get(),
        SteveConfig.REACT_OBS_TRUNCATE.get(),
        SteveConfig.REACT_FAIL_TOLERANCE.get());
    reactAgent.startAsync(
        getTaskPlanner().getAsyncClient(SteveConfig.AI_PROVIDER.get()),
        reactBaseParams);
}
```

## 配置

### 最大 Steve 数量

```java
// SteveConfig.java
public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STEVES;

static {
    MAX_ACTIVE_STEVES = builder
        .comment("Maximum number of active Steves simultaneously")
        .defineInRange("maxActiveSteves", 10, 1, 50);
}
```

**配置文件**：
```toml
# config/steve-common.toml
[behavior]
maxActiveSteves = 10  # 1-50，默认 10
```

### 生成位置

```java
// 在玩家朝向前方 3 格生成
Vec3 sourcePos = source.getPosition();
if (source.getEntity() != null) {
    Vec3 lookVec = source.getEntity().getLookAngle();
    sourcePos = sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
}
```

## 错误处理

### 名称冲突

```java
if (activeSteves.containsKey(name)) {
    LOGGER.warn("Steve name '{}' already exists", name);
    return null;
}
```

**解决方案**：使用唯一名称或自动编号。

### 达到数量限制

```java
if (activeSteves.size() >= maxSteves) {
    LOGGER.warn("Max Steve limit reached: {}", maxSteves);
    return null;
}
```

**解决方案**：移除现有 Steve 或增加限制。

### 实体添加失败

```java
boolean added = level.addFreshEntity(steve);
if (!added) {
    LOGGER.error("Failed to add Steve entity to world");
    return null;
}
```

**可能原因**：
- 世界已卸载
- 位置无效
- 实体 ID 冲突

## 性能考虑

### 内存占用

每个 Steve 实体占用：
- SteveEntity 对象：~2KB
- ActionExecutor：~1KB
- SteveMemory：~500B
- WorldKnowledge：~1KB
- 其他组件：~500B

**总计**：~5KB/Steve

**10 个 Steve**：~50KB（可忽略）

### 查找性能

- 按名称查找：O(1)（HashMap）
- 按 UUID 查找：O(1)（HashMap）
- 获取所有：O(n)（遍历 values）

### 清理开销

每 tick 清理检查：
- 遍历所有 Steve：O(n)
- 检查 isAlive/isRemoved：O(1)
- 移除无效 Steve：O(1)

**典型场景**：< 1μs（10 个 Steve）

## 线程安全

### 保证

1. **读操作**：无锁（ConcurrentHashMap）
2. **写操作**：原子性（put/remove）
3. **迭代操作**：安全（使用 Iterator.remove()）

### 潜在问题

```java
// 问题：在迭代中直接删除
for (SteveEntity steve : activeSteves.values()) {
    if (!steve.isAlive()) {
        activeSteves.remove(steve.getSteveName());  // ConcurrentModificationException
    }
}
```

**解决方案**：使用 Iterator.remove() 或 ConcurrentHashMap.entrySet().removeIf()

## 已知限制

1. **无持久化**：Steve 管理器状态不保存到磁盘
2. **无分组**：无法将 Steve 分组管理
3. **无优先级**：所有 Steve 优先级相同
4. **无限制检查**：无法限制特定类型的 Steve 数量

## 扩展建议

1. **持久化**：保存 Steve 管理器状态到 NBT
2. **分组管理**：支持 Steve 分组（如 "builders"、"miners"）
3. **优先级队列**：支持 Steve 优先级
4. **类型限制**：限制特定类型 Steve 的数量
5. **远程管理**：支持远程 Steve 管理

## 使用示例

### 基本使用

```java
// 获取管理器
SteveManager manager = SteveMod.getSteveManager();

// 生成 Steve
SteveEntity steve = manager.spawnSteve(level, position, "builder1");

// 查找 Steve
SteveEntity found = manager.getSteve("builder1");
SteveEntity byUUID = manager.getSteve(steve.getUUID());

// 列出所有 Steve
Collection<SteveEntity> allSteves = manager.getAllSteves();
List<String> names = manager.getSteveNames();

// 移除 Steve
boolean removed = manager.removeSteve("builder1");

// 清除所有
manager.clearAllSteves();
```

### 批量操作

```java
// 给所有 Steve 发送命令
for (SteveEntity steve : manager.getAllSteves()) {
    steve.getActionExecutor().processNaturalLanguageCommand("follow me");
}

// 统计活跃 Steve
int count = manager.getActiveCount();
LOGGER.info("Active Steves: {}", count);
```

### 条件移除

```java
// 移除空闲的 Steve
for (SteveEntity steve : manager.getAllSteves()) {
    if (steve.getActionExecutor().isIdle()) {
        manager.removeSteve(steve.getSteveName());
    }
}
```

## 最佳实践

1. **唯一名称**：使用描述性且唯一的名称
2. **及时清理**：不再需要时及时移除 Steve
3. **错误处理**：检查 spawnSteve/removeSteve 返回值
4. **避免并发**：在服务器线程操作 Steve 管理器
5. **监控数量**：定期检查活跃 Steve 数量
