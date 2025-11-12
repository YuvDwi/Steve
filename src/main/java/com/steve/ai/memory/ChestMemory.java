package com.steve.ai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks chest locations and their contents
 * Helps Steve remember where items are stored
 * Persisted to JSON files
 */
public class ChestMemory {
    private final String steveName;
    private final Map<BlockPos, ChestRecord> knownChests;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_CHESTS = 100; // Prevent unbounded growth

    public ChestMemory(String steveName) {
        this.steveName = steveName;
        this.knownChests = new HashMap<>();
    }

    /**
     * Record a chest and scan its contents
     */
    public void recordChest(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (!(blockEntity instanceof Container container)) {
            return;
        }

        Map<String, Integer> contents = scanChestContents(container);

        ChestRecord record = new ChestRecord(
            pos,
            System.currentTimeMillis(),
            contents
        );

        knownChests.put(pos, record);

        // Prune if too many chests
        if (knownChests.size() > MAX_CHESTS) {
            pruneOldChests();
        }

        SteveMod.LOGGER.info("Steve '{}' recorded chest at {} with {} item types",
            steveName, pos.toShortString(), contents.size());
    }

    /**
     * Update chest contents after storing/retrieving
     */
    public void updateChest(Level level, BlockPos pos) {
        if (!knownChests.containsKey(pos)) {
            recordChest(level, pos);
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            // Chest no longer exists
            knownChests.remove(pos);
            return;
        }

        Map<String, Integer> contents = scanChestContents(container);

        ChestRecord record = knownChests.get(pos);
        record.lastAccessed = System.currentTimeMillis();
        record.contents = contents;
    }

    /**
     * Find chests containing a specific item
     */
    public List<BlockPos> findChestsWithItem(String itemName) {
        return knownChests.entrySet().stream()
            .filter(entry -> entry.getValue().contents.containsKey(itemName))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Find nearest chest with a specific item
     */
    public BlockPos findNearestChestWithItem(String itemName, BlockPos currentPos) {
        return knownChests.entrySet().stream()
            .filter(entry -> entry.getValue().contents.containsKey(itemName))
            .min(Comparator.comparingDouble(entry ->
                entry.getKey().distSqr(currentPos)))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Find chest with most space
     */
    public BlockPos findChestWithMostSpace(BlockPos currentPos, int maxDistance) {
        return knownChests.entrySet().stream()
            .filter(entry -> entry.getKey().distSqr(currentPos) <= maxDistance * maxDistance)
            .max(Comparator.comparingInt(entry ->
                getTotalItems(entry.getValue())))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /**
     * Get all known chest locations
     */
    public Set<BlockPos> getAllChestLocations() {
        return new HashSet<>(knownChests.keySet());
    }

    /**
     * Get chest contents summary
     */
    public String getChestSummary(BlockPos pos) {
        ChestRecord record = knownChests.get(pos);
        if (record == null) {
            return "Unknown chest";
        }

        if (record.contents.isEmpty()) {
            return "Empty chest";
        }

        StringBuilder summary = new StringBuilder("Chest at ")
            .append(pos.toShortString())
            .append(": ");

        List<String> items = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : record.contents.entrySet()) {
            items.add(entry.getValue() + "x " + entry.getKey());
        }

        summary.append(String.join(", ", items));
        return summary.toString();
    }

    /**
     * Get summary of all chests for LLM context
     */
    public String getAllChestsSummary(int maxChests) {
        if (knownChests.isEmpty()) {
            return "No known storage chests.";
        }

        StringBuilder summary = new StringBuilder("Known Storage:\n");

        knownChests.entrySet().stream()
            .sorted(Comparator.comparingLong(e ->
                -e.getValue().lastAccessed)) // Most recently accessed first
            .limit(maxChests)
            .forEach(entry -> {
                BlockPos pos = entry.getKey();
                ChestRecord record = entry.getValue();

                summary.append("- Chest at ").append(pos.toShortString());

                if (!record.contents.isEmpty()) {
                    summary.append(": ");
                    List<String> topItems = record.contents.entrySet().stream()
                        .sorted(Comparator.comparingInt(e -> -e.getValue()))
                        .limit(5)
                        .map(e -> e.getValue() + "x " + e.getKey())
                        .collect(Collectors.toList());
                    summary.append(String.join(", ", topItems));
                }

                summary.append("\n");
            });

        return summary.toString();
    }

    /**
     * Remove chest from memory (if destroyed)
     */
    public void removeChest(BlockPos pos) {
        if (knownChests.remove(pos) != null) {
            SteveMod.LOGGER.info("Steve '{}' forgot about chest at {}",
                steveName, pos.toShortString());
        }
    }

    /**
     * Scan chest contents and return item counts
     */
    private Map<String, Integer> scanChestContents(Container container) {
        Map<String, Integer> contents = new HashMap<>();

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);

            if (stack.isEmpty()) {
                continue;
            }

            String itemName = stack.getItem().getDescriptionId();
            contents.put(itemName,
                contents.getOrDefault(itemName, 0) + stack.getCount());
        }

        return contents;
    }

    /**
     * Prune least recently accessed chests
     */
    private void pruneOldChests() {
        if (knownChests.size() <= MAX_CHESTS) {
            return;
        }

        List<BlockPos> sortedChests = knownChests.entrySet().stream()
            .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed))
            .map(Map.Entry::getKey)
            .toList();

        int toRemove = knownChests.size() - MAX_CHESTS;
        for (int i = 0; i < toRemove; i++) {
            knownChests.remove(sortedChests.get(i));
        }

        SteveMod.LOGGER.info("Steve '{}' pruned {} old chest records",
            steveName, toRemove);
    }

    /**
     * Get total item count in chest
     */
    private int getTotalItems(ChestRecord record) {
        return record.contents.values().stream()
            .mapToInt(Integer::intValue)
            .sum();
    }

    /**
     * Save to JSON file
     */
    public void saveToFile() {
        try {
            Path memoryDir = Paths.get("config", "steve", "memory");
            Files.createDirectories(memoryDir);

            Path chestFile = memoryDir.resolve(steveName + "_chests.json");

            // Convert to serializable format (BlockPos to string)
            Map<String, ChestRecord> serializable = new HashMap<>();
            for (Map.Entry<BlockPos, ChestRecord> entry : knownChests.entrySet()) {
                String key = posToString(entry.getKey());
                serializable.put(key, entry.getValue());
            }

            try (Writer writer = new FileWriter(chestFile.toFile())) {
                GSON.toJson(serializable, writer);
            }

            SteveMod.LOGGER.info("Saved {} chest records for Steve '{}'",
                knownChests.size(), steveName);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save chest memory for Steve '{}'", steveName, e);
        }
    }

    /**
     * Load from JSON file
     */
    public void loadFromFile() {
        try {
            Path chestFile = Paths.get("config", "steve", "memory", steveName + "_chests.json");

            if (!Files.exists(chestFile)) {
                SteveMod.LOGGER.info("No existing chest memory file for Steve '{}'", steveName);
                return;
            }

            try (Reader reader = new FileReader(chestFile.toFile())) {
                Type mapType = new TypeToken<Map<String, ChestRecord>>(){}.getType();
                Map<String, ChestRecord> loaded = GSON.fromJson(reader, mapType);

                if (loaded != null) {
                    knownChests.clear();
                    for (Map.Entry<String, ChestRecord> entry : loaded.entrySet()) {
                        BlockPos pos = stringToPos(entry.getKey());
                        knownChests.put(pos, entry.getValue());
                    }

                    SteveMod.LOGGER.info("Loaded {} chest records for Steve '{}'",
                        knownChests.size(), steveName);
                }
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load chest memory for Steve '{}'", steveName, e);
        }
    }

    private String posToString(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private BlockPos stringToPos(String str) {
        String[] parts = str.split(",");
        return new BlockPos(
            Integer.parseInt(parts[0]),
            Integer.parseInt(parts[1]),
            Integer.parseInt(parts[2])
        );
    }

    /**
     * Clear all chest records
     */
    public void clear() {
        knownChests.clear();
    }

    /**
     * Chest record data class
     */
    public static class ChestRecord {
        private final BlockPos position;
        private long lastAccessed;
        private Map<String, Integer> contents;

        public ChestRecord(BlockPos position, long lastAccessed, Map<String, Integer> contents) {
            this.position = position;
            this.lastAccessed = lastAccessed;
            this.contents = contents;
        }

        public BlockPos getPosition() { return position; }
        public long getLastAccessed() { return lastAccessed; }
        public Map<String, Integer> getContents() { return contents; }
    }
}
