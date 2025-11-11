# Stevever Development Roadmap

**Document Version**: 1.0
**Created**: 2025-11-11
**Status**: Active Development

---

## Executive Summary

This roadmap outlines a comprehensive development plan for the Stevever project, organized into 5 phases spanning approximately 6-8 months. Each phase builds upon the previous one, moving from foundational improvements to innovative features.

**Current Project State**: 47 Java classes, functional AI agent system with basic mining, building, and combat capabilities.

**Target State**: Production-ready AI agent system with full autonomy, persistent learning, multi-modal interaction, and advanced coordination.

---

## Development Phases Overview

| Phase | Focus Area | Duration | Priority | Dependencies |
|-------|-----------|----------|----------|--------------|
| **Phase 1** | Foundation | 1-2 months | CRITICAL | None |
| **Phase 2** | Intelligence | 1 month | HIGH | Phase 1 |
| **Phase 3** | Expansion | 1-2 months | MEDIUM | Phase 1-2 |
| **Phase 4** | Polish | 1 month | MEDIUM | Phase 1-3 |
| **Phase 5** | Innovation | Ongoing | LOW | Phase 1-4 |

---

## Phase 1: Foundation (CRITICAL Priority)

**Goal**: Establish core infrastructure for autonomous Steve operation

### 1.1 Crafting System Implementation

**Status**: 🔴 Not Started
**Estimated Time**: 5-7 days
**Priority**: CRITICAL

#### Current State
- `CraftItemAction.java` is stub (placeholder)
- `RecipeHelper.java` has no implementation
- Steve can mine resources but cannot craft tools
- Severely limits autonomy (cannot progress beyond stone age)

#### Requirements
- [ ] Implement RecipeHelper with Minecraft recipe registry integration
- [ ] Create crafting table detection/placement logic
- [ ] Implement inventory material checking
- [ ] Add crafting sequence planning (wood → planks → sticks → crafting table)
- [ ] Support shaped and shapeless recipes
- [ ] Implement furnace/smelting support
- [ ] Add tool durability awareness

#### Implementation Checklist
```java
// Files to create/modify:
- src/main/java/com/steve/ai/util/RecipeHelper.java (complete implementation)
- src/main/java/com/steve/ai/action/actions/CraftItemAction.java (full implementation)
- src/main/java/com/steve/ai/util/InventoryHelper.java (prerequisite)
- src/main/java/com/steve/ai/action/actions/SmeltItemAction.java (new file)
- src/main/java/com/steve/ai/action/actions/PlaceCraftingTableAction.java (new file)
```

#### Success Criteria
- [ ] Steve can craft basic tools (pickaxe, axe, shovel)
- [ ] Steve can upgrade tools (wood → stone → iron → diamond)
- [ ] Steve can smelt ores (iron_ore → iron_ingot)
- [ ] Steve can plan multi-step crafting sequences
- [ ] LLM can request crafting via natural language ("craft diamond pickaxe")

#### Technical Notes
- Use `RecipeManager.getRecipes()` for recipe lookup
- Handle recipe variants (different wood types for planks)
- Implement nearest crafting table search (16 block radius)
- Fallback: place crafting table if none found
- Tool selection: prefer higher tier tools when available

---

### 1.2 Inventory Management System

**Status**: 🔴 Not Started
**Estimated Time**: 4-6 days
**Priority**: CRITICAL

#### Current State
- `InventoryHelper.java` is stub (all methods return false/null)
- No inventory fullness checking
- No chest interaction
- Items accumulate until inventory full

#### Requirements
- [ ] Implement full inventory query/manipulation API
- [ ] Add chest detection and interaction
- [ ] Create automatic item sorting system
- [ ] Implement inventory fullness monitoring
- [ ] Add tool durability tracking
- [ ] Create item priority system (keep diamonds, discard dirt)

#### Implementation Checklist
```java
// Files to create/modify:
- src/main/java/com/steve/ai/util/InventoryHelper.java (complete implementation)
- src/main/java/com/steve/ai/action/actions/StoreItemsAction.java (new file)
- src/main/java/com/steve/ai/action/actions/RetrieveItemsAction.java (new file)
- src/main/java/com/steve/ai/action/actions/PlaceChestAction.java (new file)
- src/main/java/com/steve/ai/memory/InventoryMemory.java (new file)
- src/main/java/com/steve/ai/util/ItemPriority.java (new file)
```

#### API Design
```java
public class InventoryHelper {
    // Query methods
    public static boolean hasItem(LivingEntity entity, Item item, int count);
    public static int getItemCount(LivingEntity entity, Item item);
    public static ItemStack findBestTool(LivingEntity entity, BlockState targetBlock);
    public static float getInventoryFullness(LivingEntity entity); // 0.0 - 1.0

    // Manipulation methods
    public static boolean addItem(LivingEntity entity, ItemStack item);
    public static boolean removeItem(LivingEntity entity, Item item, int count);
    public static boolean transferToChest(LivingEntity entity, BlockPos chestPos, ItemStack item);
    public static boolean retrieveFromChest(BlockPos chestPos, Item item, int count);

    // Tool management
    public static boolean needsToolRepair(ItemStack tool);
    public static ItemStack selectToolForBlock(Inventory inv, BlockState block);

    // Chest operations
    public static BlockPos findNearestChest(Level level, BlockPos center, int radius);
    public static boolean isInventoryFull(Container container);
}
```

#### Success Criteria
- [ ] Steve deposits items to chest when inventory >90% full
- [ ] Steve retrieves materials from chest when needed for crafting
- [ ] Broken tools automatically replaced from inventory
- [ ] Item priority system prevents valuable items from being dropped
- [ ] Chest placement when no storage available nearby

---

### 1.3 Persistent Memory System

**Status**: 🔴 Not Started
**Estimated Time**: 5-7 days
**Priority**: CRITICAL

#### Current State
- Memory resets on world reload
- No cross-session learning
- Conversational history lost
- Steve forgets previous task outcomes

#### Requirements
- [ ] Implement NBT-based memory persistence
- [ ] Create JSON serialization for complex memory structures
- [ ] Add episodic memory storage (important events)
- [ ] Implement memory loading on Steve spawn
- [ ] Create memory pruning/summarization (prevent file bloat)
- [ ] Add shared knowledge base for all Steves

#### Implementation Checklist
```java
// Files to create/modify:
- src/main/java/com/steve/ai/memory/SteveMemory.java (enhance existing)
- src/main/java/com/steve/ai/memory/PersistentMemoryStore.java (new file)
- src/main/java/com/steve/ai/memory/EpisodicMemory.java (new file)
- src/main/java/com/steve/ai/memory/SharedKnowledgeBase.java (new file)
- src/main/java/com/steve/ai/memory/MemorySummarizer.java (new file)
```

#### Data Structure
```json
{
  "steveName": "Steve",
  "uuid": "550e8400-e29b-41d4-a716-446655440000",
  "conversationalHistory": [
    {
      "timestamp": 1699708800000,
      "role": "user",
      "content": "build a house"
    },
    {
      "timestamp": 1699708810000,
      "role": "assistant",
      "content": "Building house at (100, 64, 200)"
    }
  ],
  "episodicMemory": [
    {
      "timestamp": 1699708900000,
      "event": "found_diamond",
      "location": {"x": 50, "y": -59, "z": 150},
      "importance": 0.9
    }
  ],
  "skills": {
    "mining": 5,
    "building": 3,
    "combat": 2
  },
  "knownLocations": {
    "home": {"x": 0, "y": 64, "z": 0},
    "iron_mine": {"x": 120, "y": 50, "z": -80}
  }
}
```

#### File Storage
```
config/steve/
├── memory/
│   ├── steve_550e8400.json (per-Steve memory)
│   ├── alex_660e9500.json
│   └── shared_knowledge.json (global knowledge)
└── vector_store/
    └── embeddings.db (vector store index)
```

#### Success Criteria
- [ ] Memory persists across world reload
- [ ] Steve remembers previous task outcomes
- [ ] Important discoveries saved (diamond locations, villages, etc.)
- [ ] Memory file size <10MB per Steve (with pruning)
- [ ] Cross-session conversation continuity

---

### 1.4 Real Vector Store Integration

**Status**: 🔴 Not Started
**Estimated Time**: 7-10 days
**Priority**: HIGH

#### Current State
- `VectorStore.java` uses hash-based fake embeddings
- No semantic similarity
- Cannot retrieve contextually relevant memories

#### Requirements
- [ ] Research embedding options (OpenAI, Sentence-BERT, local models)
- [ ] Implement real embedding generation
- [ ] Create vector similarity search
- [ ] Integrate with episodic memory
- [ ] Add semantic query capability
- [ ] Optimize for Minecraft-specific vocabulary

#### Implementation Options

**Option 1: OpenAI Embeddings API** (Recommended for quick start)
- Model: text-embedding-3-small (1536 dimensions)
- Cost: $0.02 / 1M tokens
- Pros: High quality, no local setup
- Cons: API dependency, cost

**Option 2: Sentence-BERT (Local)** (Recommended for production)
- Model: all-MiniLM-L6-v2 (384 dimensions)
- Cost: Free
- Pros: No API calls, fast inference
- Cons: Requires ONNX runtime or Python bridge

**Option 3: Hybrid Approach**
- Use OpenAI for training/development
- Switch to local model for production

#### Implementation Checklist
```java
// Files to create/modify:
- src/main/java/com/steve/ai/agent/VectorStore.java (complete rewrite)
- src/main/java/com/steve/ai/ai/EmbeddingClient.java (new file)
- src/main/java/com/steve/ai/ai/OpenAIEmbeddingClient.java (new file)
- src/main/java/com/steve/ai/ai/LocalEmbeddingClient.java (new file - optional)
- src/main/java/com/steve/ai/util/VectorMath.java (cosine similarity, etc.)
```

#### API Design
```java
public interface EmbeddingClient {
    float[] generateEmbedding(String text);
    List<float[]> generateEmbeddings(List<String> texts); // Batch support
}

public class VectorStore {
    private Map<String, VectorEntry> vectors;
    private EmbeddingClient embeddingClient;

    public void addMemory(String id, String text, Map<String, Object> metadata);
    public List<SearchResult> search(String query, int topK);
    public void saveToFile(String path);
    public void loadFromFile(String path);
}

public class SearchResult {
    String id;
    String text;
    float similarity;
    Map<String, Object> metadata;
}
```

#### Example Usage
```java
// Store memory
vectorStore.addMemory(
    "event_001",
    "Found diamonds at Y=-59 near coordinates (50, -59, 150)",
    Map.of("type", "discovery", "importance", 0.9, "timestamp", System.currentTimeMillis())
);

// Semantic search
List<SearchResult> results = vectorStore.search("where did I find diamonds?", 5);
// Returns: "Found diamonds at Y=-59 near coordinates (50, -59, 150)" with high similarity
```

#### Success Criteria
- [ ] Semantic search returns relevant memories
- [ ] "where did I mine iron?" retrieves iron mining locations
- [ ] Embedding generation <500ms per query
- [ ] Vector store persists to disk
- [ ] Supports 10K+ memories without degradation

---

### 1.5 Async Action Execution

**Status**: 🔴 Not Started
**Estimated Time**: 6-8 days
**Priority**: HIGH

#### Current State
- Actions execute synchronously (one at a time)
- Cannot multitask (mining + following player simultaneously)
- Blocks other actions during long operations

#### Requirements
- [ ] Implement action priority system
- [ ] Create background task queue
- [ ] Add action interruption mechanism
- [ ] Implement concurrent action execution
- [ ] Create action conflict detection
- [ ] Add resource locking (prevent conflicting actions)

#### Implementation Checklist
```java
// Files to create/modify:
- src/main/java/com/steve/ai/action/ActionExecutor.java (major refactor)
- src/main/java/com/steve/ai/action/ActionPriority.java (new file)
- src/main/java/com/steve/ai/action/ActionScheduler.java (new file)
- src/main/java/com/steve/ai/action/ResourceLock.java (new file)
- src/main/java/com/steve/ai/action/actions/BaseAction.java (add priority field)
```

#### Architecture Design
```java
public enum ActionPriority {
    CRITICAL(0),    // Combat, danger avoidance
    HIGH(1),        // User commands
    NORMAL(2),      // Autonomous tasks
    LOW(3),         // Idle behavior
    BACKGROUND(4);  // Passive monitoring
}

public class ActionScheduler {
    // Priority queues for different action types
    private Map<ActionPriority, Queue<BaseAction>> actionQueues;

    // Currently executing actions
    private Set<BaseAction> runningActions;

    // Resource locks (prevent conflicting actions)
    private Map<String, ResourceLock> locks;

    public void scheduleAction(BaseAction action, ActionPriority priority);
    public void tick();
    public void interruptAction(BaseAction action);
    public boolean canExecute(BaseAction action);
}
```

#### Action Compatibility Matrix
```
                Mining  Building  Combat  Pathfind  Follow
Mining          ❌      ❌        ✅      ❌        ❌
Building        ❌      ❌        ✅      ❌        ❌
Combat          ✅      ✅        ❌      ❌        ❌
Pathfind        ❌      ❌        ❌      ❌        ✅
Follow          ❌      ❌        ❌      ✅        ❌
```

✅ = Can run simultaneously
❌ = Cannot run simultaneously

#### Example Scenarios

**Scenario 1: Background Mining + Combat**
```java
// Steve is mining iron (NORMAL priority, background task)
scheduler.scheduleAction(new MineBlockAction(...), ActionPriority.NORMAL);

// Creeper detected! (CRITICAL priority)
scheduler.scheduleAction(new CombatAction(...), ActionPriority.CRITICAL);

// Result: Mining paused, combat starts, mining resumes after combat
```

**Scenario 2: Building + Following Player**
```java
// Steve is building house (HIGH priority)
scheduler.scheduleAction(new BuildStructureAction(...), ActionPriority.HIGH);

// Player moves away (LOW priority follow action)
scheduler.scheduleAction(new FollowPlayerAction(...), ActionPriority.LOW);

// Result: Building continues, follow ignored (building has higher priority)
```

#### Success Criteria
- [ ] Steve can mine while monitoring for threats
- [ ] Combat interrupts current action immediately
- [ ] Multiple Steves can work on same structure without conflicts
- [ ] Action priority system prevents low-priority tasks from blocking urgent ones
- [ ] Resource locks prevent inventory corruption

---

## Phase 2: Intelligence (HIGH Priority)

**Goal**: Enhance decision-making and learning capabilities

### 2.1 Advanced LLM Prompting

**Status**: 🔴 Not Started
**Estimated Time**: 3-4 days
**Priority**: HIGH

#### Current State
- Basic system prompt with JSON format
- No few-shot examples
- Limited error recovery

#### Requirements
- [ ] Add Chain-of-Thought reasoning
- [ ] Implement few-shot examples
- [ ] Create error recovery prompts
- [ ] Add self-reflection capability
- [ ] Implement plan validation

#### Enhanced Prompt Template
```
You are an expert Minecraft AI agent with extensive experience.

REASONING FRAMEWORK:
1. Analyze the situation (what resources/tools do I have?)
2. Identify requirements (what do I need to complete this task?)
3. Plan steps (what's the optimal sequence?)
4. Validate plan (are there any blockers or missing prerequisites?)

EXAMPLE SUCCESS CASES:
[User]: "build a house"
[Thought]: "I need wood, cobblestone, and glass. Let me check inventory first."
[Plan]: "1. Check inventory 2. Gather missing materials 3. Place crafting table 4. Build structure"
[Tasks]: [{"action": "check_inventory"}, {"action": "mine", "parameters": {"block": "oak_log", "quantity": 32}}, ...]

EXAMPLE FAILURE TO AVOID:
[User]: "craft diamond pickaxe"
[Wrong]: Immediately trying to craft without checking for diamonds
[Correct]: "I need 3 diamonds and 2 sticks. Let me check inventory and mine if needed."

CURRENT SITUATION:
{contextual_info}

USER COMMAND:
{user_command}

YOUR RESPONSE (think step-by-step):
```

#### Success Criteria
- [ ] LLM provides reasoning before action
- [ ] Plans validated before execution
- [ ] Fewer invalid action sequences
- [ ] Better error messages to user

---

### 2.2 Multi-Agent Team Coordination

**Status**: 🔴 Not Started
**Estimated Time**: 5-6 days
**Priority**: HIGH

#### Requirements
- [ ] Extend collaboration beyond building
- [ ] Implement role-based task assignment
- [ ] Create team communication protocol
- [ ] Add mining teams (digger + hauler)
- [ ] Implement combat teams (tank + DPS)

#### Implementation
```java
public enum SteveRole {
    MINER, BUILDER, FIGHTER, HAULER, SCOUT, LEADER
}

public class TeamManager {
    private Map<String, SteveRole> roleAssignments;
    private Map<String, Team> teams;

    public void assignRole(String steveName, SteveRole role);
    public void createTeam(String teamName, List<String> members);
    public void coordinateTask(String teamName, TeamTask task);
}
```

---

### 2.3 Farming & Food System

**Status**: 🔴 Not Started
**Estimated Time**: 4-5 days
**Priority**: MEDIUM

#### Requirements
- [ ] Implement crop farming (wheat, carrots, potatoes)
- [ ] Add animal breeding
- [ ] Create hunger management
- [ ] Implement automatic replanting

---

### 2.4 Error Recovery & Learning

**Status**: 🔴 Not Started
**Estimated Time**: 4-5 days
**Priority**: HIGH

#### Requirements
- [ ] Track failed actions
- [ ] Send error context to LLM
- [ ] Implement retry strategies
- [ ] Learn from mistakes

---

## Phase 3: Expansion (MEDIUM Priority)

**Goal**: Extend capabilities to all Minecraft dimensions and features

### 3.1 Advanced Combat AI

**Status**: 🔴 Not Started
**Estimated Time**: 5-6 days

#### Requirements
- [ ] Shield usage
- [ ] Bow & arrow support
- [ ] Tactical retreat logic
- [ ] Armor auto-equipping
- [ ] Boss fight coordination

---

### 3.2 Nether & End Support

**Status**: 🔴 Not Started
**Estimated Time**: 7-10 days

#### Requirements
- [ ] Portal building
- [ ] Nether navigation
- [ ] Fortress raiding
- [ ] Ender Dragon fight
- [ ] Elytra usage

---

### 3.3 Redstone Mechanisms

**Status**: 🔴 Not Started
**Estimated Time**: 6-8 days

#### Requirements
- [ ] Basic circuits (doors, lights)
- [ ] Piston doors
- [ ] Automatic farms
- [ ] Minecart systems

---

### 3.4 Quest & Achievement System

**Status**: 🔴 Not Started
**Estimated Time**: 4-5 days

#### Requirements
- [ ] Quest definitions
- [ ] Progress tracking
- [ ] Reward system
- [ ] Achievement unlocks

---

## Phase 4: Polish (MEDIUM Priority)

**Goal**: Improve code quality, testing, and user experience

### 4.1 Unit & Integration Tests

**Status**: 🔴 Not Started
**Estimated Time**: 7-10 days

#### Requirements
- [ ] Set up JUnit + Mockito
- [ ] Test all action classes
- [ ] Test LLM parsing
- [ ] Integration tests for workflows

---

### 4.2 Web Dashboard

**Status**: 🔴 Not Started
**Estimated Time**: 10-14 days

#### Requirements
- [ ] Embedded HTTP server
- [ ] Real-time Steve monitoring
- [ ] Task visualization
- [ ] LLM conversation logs

---

### 4.3 Advanced GUI

**Status**: 🔴 Not Started
**Estimated Time**: 5-7 days

#### Requirements
- [ ] Status indicators
- [ ] Progress bars
- [ ] Minimap with Steve locations
- [ ] Task queue visualization

---

### 4.4 Performance Optimization

**Status**: 🔴 Not Started
**Estimated Time**: 5-6 days

#### Requirements
- [ ] LLM response caching
- [ ] Pathfinding optimization
- [ ] WorldKnowledge caching
- [ ] Memory profiling

---

## Phase 5: Innovation (LOW Priority)

**Goal**: Experimental features and cutting-edge capabilities

### 5.1 Multi-Modal LLM (Vision)

**Status**: 🔴 Not Started
**Estimated Time**: 10-14 days

#### Requirements
- [ ] Screenshot capture
- [ ] GPT-4 Vision integration
- [ ] Visual structure analysis
- [ ] Map understanding

---

### 5.2 Voice Commands

**Status**: 🔴 Not Started
**Estimated Time**: 5-7 days

#### Requirements
- [ ] Whisper API integration
- [ ] Push-to-talk system
- [ ] TTS responses

---

### 5.3 Emotion & Personality System

**Status**: 🔴 Not Started
**Estimated Time**: 6-8 days

#### Requirements
- [ ] Emotion state tracking
- [ ] Personality-based behavior
- [ ] Emotional responses in chat

---

### 5.4 Meta-Learning (Steve Collective Intelligence)

**Status**: 🔴 Not Started
**Estimated Time**: 8-10 days

#### Requirements
- [ ] Shared knowledge base
- [ ] Discovery sharing
- [ ] Collective optimization

---

## Development Guidelines

### Code Quality Standards
- All new code must have Javadoc comments
- Follow existing code style (Google Java Style Guide)
- No warnings in build output
- All public methods must have null checks

### Testing Requirements
- Unit tests for all utility classes
- Integration tests for action workflows
- Mock LLM responses for deterministic testing
- >70% code coverage target

### Git Workflow
- Branch naming: `feature/{phase}-{task-name}` (e.g., `feature/phase1-crafting-system`)
- Commit messages: Follow Conventional Commits
  - `feat: add crafting system`
  - `fix: resolve inventory duplication bug`
  - `refactor: optimize pathfinding cache`
  - `docs: update API documentation`

### Research Protocol
When uncertain about Minecraft APIs or best practices:
1. Check Minecraft Forge documentation
2. Search for examples in existing mods
3. Test in development environment before implementing
4. Document assumptions and limitations

### LLM Integration Best Practices
- Always implement retry logic with exponential backoff
- Cache responses when possible (5-minute TTL)
- Validate JSON responses before parsing
- Include request IDs for debugging
- Monitor API quota usage

---

## Success Metrics

### Phase 1 Completion Criteria
- [ ] Steve can autonomously progress from wood to diamond tools
- [ ] Inventory never gets full (auto-storage)
- [ ] Memory persists across sessions
- [ ] Semantic search returns relevant results (>80% accuracy)
- [ ] Multiple actions can run simultaneously

### Phase 2 Completion Criteria
- [ ] LLM plans are valid >95% of the time
- [ ] Teams of 3+ Steves coordinate efficiently
- [ ] Steve can sustain itself (food/hunger)
- [ ] Failed actions trigger intelligent retry

### Phase 3 Completion Criteria
- [ ] Steve can defeat Ender Dragon with team
- [ ] Redstone contraptions function correctly
- [ ] Quest system has 10+ quests implemented

### Phase 4 Completion Criteria
- [ ] >70% test coverage
- [ ] Web dashboard fully functional
- [ ] Zero critical performance issues
- [ ] User satisfaction >4/5 stars

### Phase 5 Completion Criteria
- [ ] Vision-based structure copying works
- [ ] Voice commands have <10% error rate
- [ ] Emotion system creates engaging interactions
- [ ] Meta-learning demonstrates measurable improvement

---

## Risk Management

### High-Risk Areas

**1. LLM API Reliability**
- **Risk**: API downtime, rate limits, cost overruns
- **Mitigation**: Multi-provider fallback, caching, local model option

**2. Async Action Conflicts**
- **Risk**: Race conditions, inventory corruption
- **Mitigation**: Resource locking, thorough testing, rollback mechanisms

**3. Memory Storage Growth**
- **Risk**: Unbounded memory files (>100MB)
- **Mitigation**: Automatic pruning, summarization, size limits

**4. Minecraft API Changes**
- **Risk**: Forge updates breaking compatibility
- **Mitigation**: Version pinning, API abstraction layer

---

## Appendix

### Useful Resources

**Minecraft Modding**
- Forge Documentation: https://docs.minecraftforge.net/
- Minecraft Wiki: https://minecraft.fandom.com/
- Fabric/Forge Examples: https://github.com/MinecraftForge/MinecraftForge

**LLM Integration**
- OpenAI API Docs: https://platform.openai.com/docs
- Groq API Docs: https://console.groq.com/docs
- Gemini API Docs: https://ai.google.dev/docs

**Vector Embeddings**
- Sentence-BERT: https://www.sbert.net/
- OpenAI Embeddings: https://platform.openai.com/docs/guides/embeddings
- FAISS (Facebook AI Similarity Search): https://github.com/facebookresearch/faiss

**Testing**
- JUnit 5: https://junit.org/junit5/
- Mockito: https://site.mockito.org/

---

## Changelog

| Date | Version | Changes |
|------|---------|---------|
| 2025-11-11 | 1.0 | Initial roadmap created |

---

**Next Review Date**: 2025-12-11
**Document Owner**: Development Team
**Approval Status**: ✅ Approved for Implementation
