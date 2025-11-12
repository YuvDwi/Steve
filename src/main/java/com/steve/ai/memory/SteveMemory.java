package com.steve.ai.memory;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Enhanced Steve memory system with multiple memory types:
 * - Short-term memory (current goal, recent actions)
 * - Episodic memory (significant events, discoveries)
 * - Chest memory (storage locations and contents)
 * - Conversation history (for LLM context)
 */
public class SteveMemory {
    private final SteveEntity steve;
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private static final int MAX_RECENT_ACTIONS = 20;

    // New memory systems
    private final EpisodicMemory episodicMemory;
    private final ChestMemory chestMemory;
    private final ConversationHistory conversationHistory;
    private final SharedKnowledgeBase sharedKnowledge;

    public SteveMemory(SteveEntity steve) {
        this.steve = steve;
        this.currentGoal = "";
        this.taskQueue = new LinkedList<>();
        this.recentActions = new LinkedList<>();

        // Initialize new memory systems
        this.episodicMemory = new EpisodicMemory(steve.getSteveName());
        this.chestMemory = new ChestMemory(steve.getSteveName());
        this.conversationHistory = new ConversationHistory(steve.getSteveName());
        this.sharedKnowledge = SharedKnowledgeBase.getInstance();

        // Load persistent memories
        loadPersistentMemories();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public void setCurrentGoal(String goal) {
        this.currentGoal = goal;
    }

    public void addAction(String action) {
        recentActions.addLast(action);
        if (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
    }

    public List<String> getRecentActions(int count) {
        int size = Math.min(count, recentActions.size());
        List<String> result = new ArrayList<>();

        int startIndex = Math.max(0, recentActions.size() - count);
        for (int i = startIndex; i < recentActions.size(); i++) {
            result.add(recentActions.get(i));
        }

        return result;
    }

    public void clearTaskQueue() {
        taskQueue.clear();
        currentGoal = "";
    }

    // ==================== New Memory Accessors ====================

    /**
     * Get episodic memory system
     */
    public EpisodicMemory getEpisodicMemory() {
        return episodicMemory;
    }

    /**
     * Get chest memory system
     */
    public ChestMemory getChestMemory() {
        return chestMemory;
    }

    /**
     * Get conversation history
     */
    public ConversationHistory getConversationHistory() {
        return conversationHistory;
    }

    /**
     * Get shared knowledge base (singleton)
     */
    public SharedKnowledgeBase getSharedKnowledge() {
        return sharedKnowledge;
    }

    /**
     * Record a significant event
     */
    public void recordEvent(EpisodicMemory.EventType type, String description,
                           BlockPos location, double importance) {
        episodicMemory.addMemory(type, description, location, importance);

        // Auto-save important memories
        if (importance >= 0.8) {
            savePersistentMemories();
        }
    }

    /**
     * Record chest interaction
     */
    public void recordChest(BlockPos pos) {
        chestMemory.recordChest(steve.level(), pos);
    }

    /**
     * Update chest contents after interaction
     */
    public void updateChest(BlockPos pos) {
        chestMemory.updateChest(steve.level(), pos);
    }

    /**
     * Add message to conversation history
     */
    public void addConversation(String role, String content) {
        conversationHistory.addMessage(role, content);
    }

    /**
     * Share knowledge with other Steves
     */
    public void shareKnowledge(SharedKnowledgeBase.KnowledgeType type, String key,
                              String description, BlockPos location, double importance) {
        sharedKnowledge.addKnowledge(steve.getSteveName(), type, key, description,
                                    location, importance);

        // Also save to file if important
        if (importance >= 0.8) {
            sharedKnowledge.saveToFile();
        }
    }

    /**
     * Query shared knowledge by type
     */
    public List<SharedKnowledgeBase.KnowledgeEntry> querySharedKnowledge(
            SharedKnowledgeBase.KnowledgeType type) {
        return sharedKnowledge.getKnowledgeByType(type);
    }

    /**
     * Find shared knowledge near a location
     */
    public List<SharedKnowledgeBase.KnowledgeEntry> findNearbyKnowledge(
            BlockPos pos, double maxDistance) {
        return sharedKnowledge.getKnowledgeNear(pos, maxDistance);
    }

    /**
     * Get comprehensive memory summary for LLM
     */
    public String getMemorySummary() {
        StringBuilder summary = new StringBuilder();

        // Recent actions
        if (!recentActions.isEmpty()) {
            summary.append("=== Recent Actions ===\n");
            List<String> recent = getRecentActions(5);
            for (String action : recent) {
                summary.append("- ").append(action).append("\n");
            }
            summary.append("\n");
        }

        // Significant memories
        if (episodicMemory.size() > 0) {
            summary.append(episodicMemory.getSummary(5));
            summary.append("\n");
        }

        // Known storage
        if (!chestMemory.getAllChestLocations().isEmpty()) {
            summary.append(chestMemory.getAllChestsSummary(3));
            summary.append("\n");
        }

        // Recent conversation
        List<ConversationHistory.Message> recentConvo =
            conversationHistory.getRecentMessages(5);
        if (!recentConvo.isEmpty()) {
            summary.append("=== Recent Conversation ===\n");
            for (ConversationHistory.Message msg : recentConvo) {
                summary.append(msg.getRole()).append(": ")
                       .append(msg.getContent()).append("\n");
            }
            summary.append("\n");
        }

        // Shared knowledge from other Steves
        if (sharedKnowledge.size() > 0) {
            summary.append(sharedKnowledge.getKnowledgeSummary(3));
        }

        return summary.toString();
    }

    // ==================== Persistence ====================

    /**
     * Load all persistent memories from files
     */
    private void loadPersistentMemories() {
        try {
            episodicMemory.loadFromFile();
            chestMemory.loadFromFile();
            conversationHistory.loadFromFile();

            SteveMod.LOGGER.info("Loaded persistent memories for Steve '{}'",
                steve.getSteveName());
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to load persistent memories for Steve '{}'",
                steve.getSteveName(), e);
        }
    }

    /**
     * Save all persistent memories to files
     */
    public void savePersistentMemories() {
        try {
            episodicMemory.saveToFile();
            chestMemory.saveToFile();
            conversationHistory.saveToFile();
            sharedKnowledge.saveToFile(); // Save shared knowledge (singleton)

            SteveMod.LOGGER.info("Saved persistent memories for Steve '{}'",
                steve.getSteveName());
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to save persistent memories for Steve '{}'",
                steve.getSteveName(), e);
        }
    }

    // ==================== NBT Serialization (for entity data) ====================

    public void saveToNBT(CompoundTag tag) {
        tag.putString("CurrentGoal", currentGoal);

        ListTag actionsList = new ListTag();
        for (String action : recentActions) {
            actionsList.add(StringTag.valueOf(action));
        }
        tag.put("RecentActions", actionsList);

        // Note: Persistent memories are saved to separate JSON files
        // NBT is only for in-game entity data
    }

    public void loadFromNBT(CompoundTag tag) {
        if (tag.contains("CurrentGoal")) {
            currentGoal = tag.getString("CurrentGoal");
        }

        if (tag.contains("RecentActions")) {
            recentActions.clear();
            ListTag actionsList = tag.getList("RecentActions", 8); // 8 = String type
            for (int i = 0; i < actionsList.size(); i++) {
                recentActions.add(actionsList.getString(i));
            }
        }

        // Persistent memories are loaded in constructor
    }
}
