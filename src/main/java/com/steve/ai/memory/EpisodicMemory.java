package com.steve.ai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;
import com.steve.ai.agent.VectorStore;
import net.minecraft.core.BlockPos;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Episodic memory stores significant events and discoveries
 * Persisted to JSON files in config/steve/memory/
 * Each memory has:
 * - Timestamp
 * - Event type (discovery, achievement, interaction, etc.)
 * - Description
 * - Location (optional)
 * - Importance score (0.0 - 1.0)
 * - Metadata (custom data)
 *
 * Optional: Can use VectorStore for semantic search capabilities
 */
public class EpisodicMemory {
    private final String steveName;
    private final List<MemoryEntry> memories;
    private VectorStore vectorStore; // Optional for semantic search
    private static final int MAX_MEMORIES = 500; // Prevent unbounded growth
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public EpisodicMemory(String steveName) {
        this.steveName = steveName;
        this.memories = new ArrayList<>();
        this.vectorStore = null; // Disabled by default
    }

    /**
     * Enable semantic search using vector embeddings
     * @param vectorStore Vector store instance
     */
    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;

        // Re-index existing memories
        if (vectorStore != null && !memories.isEmpty()) {
            reindexMemories();
        }

        SteveMod.LOGGER.info("Steve '{}' enabled semantic memory search", steveName);
    }

    /**
     * Get the vector store (may be null)
     */
    public VectorStore getVectorStore() {
        return vectorStore;
    }

    /**
     * Check if semantic search is enabled
     */
    public boolean hasSemanticSearch() {
        return vectorStore != null;
    }

    /**
     * Add a new memory entry
     */
    public void addMemory(EventType type, String description, BlockPos location, double importance) {
        addMemory(type, description, location, importance, new HashMap<>());
    }

    /**
     * Add memory with custom metadata
     */
    public void addMemory(EventType type, String description, BlockPos location,
                          double importance, Map<String, Object> metadata) {
        long timestamp = System.currentTimeMillis();
        MemoryEntry entry = new MemoryEntry(
            timestamp,
            type,
            description,
            location,
            importance,
            metadata
        );

        memories.add(entry);

        // Add to vector store for semantic search
        if (vectorStore != null) {
            String memoryId = steveName + "_" + timestamp;
            Map<String, Object> vectorMetadata = new HashMap<>(metadata);
            vectorMetadata.put("type", type.toString());
            vectorMetadata.put("importance", importance);
            vectorMetadata.put("timestamp", timestamp);

            vectorStore.addMemory(memoryId, description, vectorMetadata);
        }

        // Prune old low-importance memories if exceeding limit
        if (memories.size() > MAX_MEMORIES) {
            pruneMemories();
        }

        SteveMod.LOGGER.info("Steve '{}' recorded memory: {} (importance: {})",
            steveName, description, importance);
    }

    /**
     * Get all memories of a specific type
     */
    public List<MemoryEntry> getMemoriesByType(EventType type) {
        return memories.stream()
            .filter(m -> m.type == type)
            .sorted(Comparator.comparingLong(MemoryEntry::getTimestamp).reversed())
            .toList();
    }

    /**
     * Get memories within a time range
     */
    public List<MemoryEntry> getMemoriesSince(long timestamp) {
        return memories.stream()
            .filter(m -> m.timestamp >= timestamp)
            .sorted(Comparator.comparingLong(MemoryEntry::getTimestamp).reversed())
            .toList();
    }

    /**
     * Get most important memories
     */
    public List<MemoryEntry> getTopMemories(int count) {
        return memories.stream()
            .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
            .limit(count)
            .toList();
    }

    /**
     * Get recent memories
     */
    public List<MemoryEntry> getRecentMemories(int count) {
        return memories.stream()
            .sorted(Comparator.comparingLong(MemoryEntry::getTimestamp).reversed())
            .limit(count)
            .toList();
    }

    /**
     * Find memories by location (within radius)
     */
    public List<MemoryEntry> getMemoriesNear(BlockPos pos, int radius) {
        return memories.stream()
            .filter(m -> m.location != null)
            .filter(m -> m.location.distSqr(pos) <= radius * radius)
            .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
            .toList();
    }

    /**
     * Search memories by keyword in description
     */
    public List<MemoryEntry> searchMemories(String keyword) {
        String lowerKeyword = keyword.toLowerCase();
        return memories.stream()
            .filter(m -> m.description.toLowerCase().contains(lowerKeyword))
            .sorted(Comparator.comparingDouble(MemoryEntry::getImportance).reversed())
            .toList();
    }

    /**
     * Semantic search using natural language query
     * Requires vector store to be enabled
     * @param query Natural language query
     * @param topK Number of results to return
     * @return List of matching memories sorted by relevance
     */
    public List<MemoryEntry> semanticSearch(String query, int topK) {
        if (vectorStore == null) {
            SteveMod.LOGGER.warn("Semantic search requested but vector store not enabled");
            return new ArrayList<>();
        }

        // Search vector store
        List<VectorStore.SearchResult> results = vectorStore.search(query, topK);

        // Convert results back to MemoryEntry objects
        return results.stream()
            .map(result -> {
                long timestamp = ((Number) result.metadata.get("timestamp")).longValue();
                return findMemoryByTimestamp(timestamp);
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * Find memory by timestamp
     */
    private MemoryEntry findMemoryByTimestamp(long timestamp) {
        return memories.stream()
            .filter(m -> m.timestamp == timestamp)
            .findFirst()
            .orElse(null);
    }

    /**
     * Re-index all existing memories in vector store
     */
    private void reindexMemories() {
        if (vectorStore == null) {
            return;
        }

        SteveMod.LOGGER.info("Re-indexing {} memories for Steve '{}'",
            memories.size(), steveName);

        for (MemoryEntry memory : memories) {
            String memoryId = steveName + "_" + memory.timestamp;
            Map<String, Object> metadata = new HashMap<>(memory.metadata);
            metadata.put("type", memory.type.toString());
            metadata.put("importance", memory.importance);
            metadata.put("timestamp", memory.timestamp);

            vectorStore.addMemory(memoryId, memory.description, metadata);
        }
    }

    /**
     * Prune old, low-importance memories to stay under limit
     */
    private void pruneMemories() {
        // Sort by importance (ascending), remove bottom 20%
        memories.sort(Comparator.comparingDouble(MemoryEntry::getImportance));

        int toRemove = memories.size() - MAX_MEMORIES;
        if (toRemove > 0) {
            for (int i = 0; i < toRemove; i++) {
                // Only remove if importance < 0.5 (keep important memories)
                if (memories.get(0).importance < 0.5) {
                    memories.remove(0);
                } else {
                    break;
                }
            }
        }

        SteveMod.LOGGER.info("Steve '{}' pruned memories, now has {} entries",
            steveName, memories.size());
    }

    /**
     * Save memories to JSON file
     */
    public void saveToFile() {
        try {
            Path memoryDir = Paths.get("config", "steve", "memory");
            Files.createDirectories(memoryDir);

            Path memoryFile = memoryDir.resolve(steveName + "_episodic.json");

            try (Writer writer = new FileWriter(memoryFile.toFile())) {
                GSON.toJson(memories, writer);
            }

            SteveMod.LOGGER.info("Saved {} episodic memories for Steve '{}'",
                memories.size(), steveName);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save episodic memory for Steve '{}'", steveName, e);
        }
    }

    /**
     * Load memories from JSON file
     */
    public void loadFromFile() {
        try {
            Path memoryFile = Paths.get("config", "steve", "memory", steveName + "_episodic.json");

            if (!Files.exists(memoryFile)) {
                SteveMod.LOGGER.info("No existing episodic memory file for Steve '{}'", steveName);
                return;
            }

            try (Reader reader = new FileReader(memoryFile.toFile())) {
                Type listType = new TypeToken<List<MemoryEntry>>(){}.getType();
                List<MemoryEntry> loadedMemories = GSON.fromJson(reader, listType);

                if (loadedMemories != null) {
                    memories.clear();
                    memories.addAll(loadedMemories);
                    SteveMod.LOGGER.info("Loaded {} episodic memories for Steve '{}'",
                        memories.size(), steveName);
                }
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load episodic memory for Steve '{}'", steveName, e);
        }
    }

    /**
     * Get summary of all memories for LLM context
     */
    public String getSummary(int maxEntries) {
        List<MemoryEntry> topMemories = getTopMemories(maxEntries);

        if (topMemories.isEmpty()) {
            return "No significant memories yet.";
        }

        StringBuilder summary = new StringBuilder("Significant Memories:\n");
        for (MemoryEntry memory : topMemories) {
            summary.append("- ").append(memory.description);
            if (memory.location != null) {
                summary.append(" at ").append(formatPosition(memory.location));
            }
            summary.append("\n");
        }

        return summary.toString();
    }

    private String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Clear all memories (for testing/reset)
     */
    public void clear() {
        memories.clear();
    }

    /**
     * Get memory count
     */
    public int size() {
        return memories.size();
    }

    /**
     * Memory entry data class
     */
    public static class MemoryEntry {
        private final long timestamp;
        private final EventType type;
        private final String description;
        private final BlockPos location;
        private final double importance;
        private final Map<String, Object> metadata;

        public MemoryEntry(long timestamp, EventType type, String description,
                          BlockPos location, double importance, Map<String, Object> metadata) {
            this.timestamp = timestamp;
            this.type = type;
            this.description = description;
            this.location = location;
            this.importance = importance;
            this.metadata = metadata;
        }

        public long getTimestamp() { return timestamp; }
        public EventType getType() { return type; }
        public String getDescription() { return description; }
        public BlockPos getLocation() { return location; }
        public double getImportance() { return importance; }
        public Map<String, Object> getMetadata() { return metadata; }

        @Override
        public String toString() {
            return String.format("[%s] %s (importance: %.2f)",
                type, description, importance);
        }
    }

    /**
     * Event types for categorizing memories
     */
    public enum EventType {
        DISCOVERY,      // Found something valuable (diamonds, structures, etc.)
        ACHIEVEMENT,    // Completed significant task (killed boss, built house)
        INTERACTION,    // Interacted with player or another Steve
        FAILURE,        // Failed at important task
        LEARNING,       // Learned something new (recipe, strategy)
        COMBAT,         // Combat-related event
        BUILDING,       // Built something significant
        EXPLORATION     // Explored new area
    }
}
