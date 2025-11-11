package com.steve.ai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared knowledge base accessible by all Steve entities
 * Enables collective learning and optimization
 * Different Steves can contribute and access shared discoveries
 */
public class SharedKnowledgeBase {
    private static SharedKnowledgeBase INSTANCE;
    private final Map<String, KnowledgeEntry> knowledgeMap;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ENTRIES = 500;
    private static final Path KNOWLEDGE_FILE = Paths.get("config", "steve", "memory", "shared_knowledge.json");

    /**
     * Knowledge entry types
     */
    public enum KnowledgeType {
        RESOURCE_LOCATION,  // Where to find resources (diamonds, iron, etc.)
        CRAFTING_TIP,       // Useful crafting patterns
        DANGER_ZONE,        // Dangerous areas to avoid
        EFFICIENT_PATH,     // Optimized paths between locations
        FARMING_SPOT,       // Good locations for farming
        MOB_SPAWN,          // Mob spawn locations
        STRUCTURE,          // Village, dungeon, etc.
        OPTIMIZATION        // General optimization tips
    }

    private SharedKnowledgeBase() {
        this.knowledgeMap = new HashMap<>();
        loadFromFile();
    }

    /**
     * Get singleton instance
     */
    public static synchronized SharedKnowledgeBase getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SharedKnowledgeBase();
        }
        return INSTANCE;
    }

    /**
     * Add knowledge to the shared base
     */
    public void addKnowledge(String steveName, KnowledgeType type, String key,
                           String description, BlockPos location, double importance) {
        KnowledgeEntry entry = new KnowledgeEntry(
            System.currentTimeMillis(),
            steveName,
            type,
            key,
            description,
            location,
            importance,
            1 // Initial confirmation count
        );

        // If knowledge already exists, update confirmation count
        if (knowledgeMap.containsKey(key)) {
            KnowledgeEntry existing = knowledgeMap.get(key);
            existing.confirmationCount++;
            existing.lastConfirmed = System.currentTimeMillis();
            existing.importance = Math.max(existing.importance, importance);
        } else {
            knowledgeMap.put(key, entry);
        }

        // Prune if too many entries
        if (knowledgeMap.size() > MAX_ENTRIES) {
            pruneOldKnowledge();
        }

        SteveMod.LOGGER.info("Steve '{}' added shared knowledge: {} - {}",
            steveName, type, description);
    }

    /**
     * Query knowledge by type
     */
    public List<KnowledgeEntry> getKnowledgeByType(KnowledgeType type) {
        return knowledgeMap.values().stream()
            .filter(entry -> entry.type == type)
            .sorted(Comparator.comparingDouble(e -> -e.importance))
            .collect(Collectors.toList());
    }

    /**
     * Query knowledge by key
     */
    public KnowledgeEntry getKnowledge(String key) {
        return knowledgeMap.get(key);
    }

    /**
     * Find knowledge near a location
     */
    public List<KnowledgeEntry> getKnowledgeNear(BlockPos pos, double maxDistance) {
        return knowledgeMap.values().stream()
            .filter(entry -> entry.location != null)
            .filter(entry -> entry.location.distSqr(pos) <= maxDistance * maxDistance)
            .sorted(Comparator.comparingDouble(e -> e.location.distSqr(pos)))
            .collect(Collectors.toList());
    }

    /**
     * Find resource locations
     */
    public List<KnowledgeEntry> findResourceLocations(String resourceName) {
        return knowledgeMap.values().stream()
            .filter(entry -> entry.type == KnowledgeType.RESOURCE_LOCATION)
            .filter(entry -> entry.key.contains(resourceName) || entry.description.contains(resourceName))
            .sorted(Comparator.comparingInt(e -> -e.confirmationCount))
            .collect(Collectors.toList());
    }

    /**
     * Get most important knowledge
     */
    public List<KnowledgeEntry> getMostImportant(int limit) {
        return knowledgeMap.values().stream()
            .sorted(Comparator.comparingDouble(e -> -e.importance))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Get recent knowledge
     */
    public List<KnowledgeEntry> getRecent(int limit) {
        return knowledgeMap.values().stream()
            .sorted(Comparator.comparingLong(e -> -e.timestamp))
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * Get knowledge summary for LLM context
     */
    public String getKnowledgeSummary(int maxEntries) {
        if (knowledgeMap.isEmpty()) {
            return "No shared knowledge available.";
        }

        StringBuilder summary = new StringBuilder("=== Shared Knowledge ===\n");

        // Get most important and recent knowledge
        List<KnowledgeEntry> important = getMostImportant(maxEntries);

        for (KnowledgeEntry entry : important) {
            summary.append("- [").append(entry.type).append("] ");
            summary.append(entry.description);

            if (entry.location != null) {
                summary.append(" at ").append(entry.location.toShortString());
            }

            if (entry.confirmationCount > 1) {
                summary.append(" (confirmed ").append(entry.confirmationCount).append("x)");
            }

            summary.append(" [by ").append(entry.contributor).append("]\n");
        }

        return summary.toString();
    }

    /**
     * Remove knowledge by key
     */
    public void removeKnowledge(String key) {
        if (knowledgeMap.remove(key) != null) {
            SteveMod.LOGGER.info("Removed shared knowledge: {}", key);
        }
    }

    /**
     * Clear all knowledge
     */
    public void clear() {
        knowledgeMap.clear();
    }

    /**
     * Get total knowledge count
     */
    public int size() {
        return knowledgeMap.size();
    }

    /**
     * Prune low-importance old knowledge
     */
    private void pruneOldKnowledge() {
        if (knowledgeMap.size() <= MAX_ENTRIES) {
            return;
        }

        // Calculate score: importance * confirmationCount / age
        List<String> sortedKeys = knowledgeMap.entrySet().stream()
            .sorted(Comparator.comparingDouble(e -> {
                KnowledgeEntry entry = e.getValue();
                long age = System.currentTimeMillis() - entry.timestamp;
                double ageDays = age / (1000.0 * 60 * 60 * 24);
                return -(entry.importance * entry.confirmationCount / Math.max(1, ageDays));
            }))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        // Remove bottom 20%
        int toRemove = knowledgeMap.size() - MAX_ENTRIES;
        for (int i = sortedKeys.size() - 1; i >= sortedKeys.size() - toRemove; i--) {
            knowledgeMap.remove(sortedKeys.get(i));
        }

        SteveMod.LOGGER.info("Pruned {} old knowledge entries", toRemove);
    }

    /**
     * Save to JSON file
     */
    public void saveToFile() {
        try {
            Path memoryDir = KNOWLEDGE_FILE.getParent();
            if (memoryDir != null) {
                Files.createDirectories(memoryDir);
            }

            try (Writer writer = new FileWriter(KNOWLEDGE_FILE.toFile())) {
                GSON.toJson(knowledgeMap, writer);
            }

            SteveMod.LOGGER.info("Saved {} shared knowledge entries", knowledgeMap.size());
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save shared knowledge", e);
        }
    }

    /**
     * Load from JSON file
     */
    public void loadFromFile() {
        try {
            if (!Files.exists(KNOWLEDGE_FILE)) {
                SteveMod.LOGGER.info("No existing shared knowledge file");
                return;
            }

            try (Reader reader = new FileReader(KNOWLEDGE_FILE.toFile())) {
                Type mapType = new TypeToken<Map<String, KnowledgeEntry>>(){}.getType();
                Map<String, KnowledgeEntry> loaded = GSON.fromJson(reader, mapType);

                if (loaded != null) {
                    knowledgeMap.clear();
                    knowledgeMap.putAll(loaded);
                    SteveMod.LOGGER.info("Loaded {} shared knowledge entries", knowledgeMap.size());
                }
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load shared knowledge", e);
        }
    }

    /**
     * Knowledge entry data class
     */
    public static class KnowledgeEntry {
        private final long timestamp;
        private final String contributor; // Which Steve added this
        private final KnowledgeType type;
        private final String key; // Unique identifier
        private final String description;
        private final BlockPos location;
        private double importance; // 0.0 to 1.0
        private int confirmationCount; // How many Steves confirmed this
        private long lastConfirmed;

        public KnowledgeEntry(long timestamp, String contributor, KnowledgeType type,
                            String key, String description, BlockPos location,
                            double importance, int confirmationCount) {
            this.timestamp = timestamp;
            this.contributor = contributor;
            this.type = type;
            this.key = key;
            this.description = description;
            this.location = location;
            this.importance = importance;
            this.confirmationCount = confirmationCount;
            this.lastConfirmed = timestamp;
        }

        // Getters
        public long getTimestamp() { return timestamp; }
        public String getContributor() { return contributor; }
        public KnowledgeType getType() { return type; }
        public String getKey() { return key; }
        public String getDescription() { return description; }
        public BlockPos getLocation() { return location; }
        public double getImportance() { return importance; }
        public int getConfirmationCount() { return confirmationCount; }
        public long getLastConfirmed() { return lastConfirmed; }

        @Override
        public String toString() {
            return String.format("[%s] %s (importance: %.2f, confirmed: %dx)",
                type, description, importance, confirmationCount);
        }
    }
}
