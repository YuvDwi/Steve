package com.steve.ai.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.steve.ai.SteveMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Manages achievements for a Steve entity
 * Tracks unlocked achievements and checks for new unlocks
 */
public class AchievementManager {
    private static final String ACHIEVEMENT_DATA_DIR = "config/steve/achievements/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String steveName;
    private final Map<String, Achievement> achievements;
    private final Map<String, Integer> stats; // Track stats for achievement checking

    public AchievementManager(String steveName) {
        this.steveName = steveName;
        this.achievements = new LinkedHashMap<>();
        this.stats = new HashMap<>();

        // Create achievement data directory
        try {
            Files.createDirectories(Paths.get(ACHIEVEMENT_DATA_DIR));
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to create achievement data directory", e);
        }

        // Initialize achievements
        initializeAchievements();

        // Load progress
        loadAchievements();
    }

    /**
     * Initialize all achievements
     */
    private void initializeAchievements() {
        // Mining achievements
        addAchievement(new Achievement("first_mine", "First Steps",
            "Mine your first block", Achievement.AchievementCategory.MINING,
            Achievement.AchievementRarity.COMMON, "mine_block:1"));

        addAchievement(new Achievement("iron_miner", "Iron Miner",
            "Mine 64 iron ore", Achievement.AchievementCategory.MINING,
            Achievement.AchievementRarity.UNCOMMON, "mine_iron:64"));

        addAchievement(new Achievement("diamond_hunter", "Diamond Hunter",
            "Mine 10 diamonds", Achievement.AchievementCategory.MINING,
            Achievement.AchievementRarity.RARE, "mine_diamond:10"));

        addAchievement(new Achievement("master_miner", "Master Miner",
            "Mine 1000 blocks", Achievement.AchievementCategory.MINING,
            Achievement.AchievementRarity.EPIC, "mine_block:1000"));

        // Crafting achievements
        addAchievement(new Achievement("first_craft", "Crafting Beginner",
            "Craft your first item", Achievement.AchievementCategory.CRAFTING,
            Achievement.AchievementRarity.COMMON, "craft_item:1"));

        addAchievement(new Achievement("tool_maker", "Tool Maker",
            "Craft 10 tools", Achievement.AchievementCategory.CRAFTING,
            Achievement.AchievementRarity.UNCOMMON, "craft_tool:10"));

        addAchievement(new Achievement("master_crafter", "Master Crafter",
            "Craft 100 items", Achievement.AchievementCategory.CRAFTING,
            Achievement.AchievementRarity.RARE, "craft_item:100"));

        // Combat achievements
        addAchievement(new Achievement("first_blood", "First Blood",
            "Defeat your first monster", Achievement.AchievementCategory.COMBAT,
            Achievement.AchievementRarity.COMMON, "kill_mob:1"));

        addAchievement(new Achievement("zombie_slayer", "Zombie Slayer",
            "Defeat 50 zombies", Achievement.AchievementCategory.COMBAT,
            Achievement.AchievementRarity.UNCOMMON, "kill_zombie:50"));

        addAchievement(new Achievement("monster_hunter", "Monster Hunter",
            "Defeat 100 monsters", Achievement.AchievementCategory.COMBAT,
            Achievement.AchievementRarity.RARE, "kill_mob:100"));

        addAchievement(new Achievement("legendary_warrior", "Legendary Warrior",
            "Defeat 500 monsters", Achievement.AchievementCategory.COMBAT,
            Achievement.AchievementRarity.LEGENDARY, "kill_mob:500"));

        // Building achievements
        addAchievement(new Achievement("first_build", "Builder",
            "Build your first structure", Achievement.AchievementCategory.BUILDING,
            Achievement.AchievementRarity.COMMON, "build_structure:1"));

        addAchievement(new Achievement("architect", "Architect",
            "Build 10 structures", Achievement.AchievementCategory.BUILDING,
            Achievement.AchievementRarity.UNCOMMON, "build_structure:10"));

        addAchievement(new Achievement("master_builder", "Master Builder",
            "Place 10,000 blocks", Achievement.AchievementCategory.BUILDING,
            Achievement.AchievementRarity.EPIC, "place_block:10000"));

        // Farming achievements
        addAchievement(new Achievement("green_thumb", "Green Thumb",
            "Harvest your first crop", Achievement.AchievementCategory.FARMING,
            Achievement.AchievementRarity.COMMON, "harvest_crop:1"));

        addAchievement(new Achievement("farmer", "Farmer",
            "Harvest 500 crops", Achievement.AchievementCategory.FARMING,
            Achievement.AchievementRarity.UNCOMMON, "harvest_crop:500"));

        addAchievement(new Achievement("animal_breeder", "Animal Breeder",
            "Breed 50 animals", Achievement.AchievementCategory.FARMING,
            Achievement.AchievementRarity.RARE, "breed_animal:50"));

        // Exploration achievements
        addAchievement(new Achievement("explorer", "Explorer",
            "Travel 1000 blocks", Achievement.AchievementCategory.EXPLORATION,
            Achievement.AchievementRarity.UNCOMMON, "travel_distance:1000"));

        addAchievement(new Achievement("nether_traveler", "Nether Traveler",
            "Enter the Nether", Achievement.AchievementCategory.EXPLORATION,
            Achievement.AchievementRarity.RARE, "enter_nether:1"));

        addAchievement(new Achievement("end_warrior", "End Warrior",
            "Enter the End", Achievement.AchievementCategory.EXPLORATION,
            Achievement.AchievementRarity.EPIC, "enter_end:1"));

        // Redstone achievements
        addAchievement(new Achievement("redstone_beginner", "Redstone Beginner",
            "Place your first redstone", Achievement.AchievementCategory.REDSTONE,
            Achievement.AchievementRarity.COMMON, "place_redstone:1"));

        addAchievement(new Achievement("redstone_engineer", "Redstone Engineer",
            "Build an automatic door", Achievement.AchievementCategory.REDSTONE,
            Achievement.AchievementRarity.UNCOMMON, "build_auto_door:1"));

        // General achievements
        addAchievement(new Achievement("hard_worker", "Hard Worker",
            "Complete 10 quests", Achievement.AchievementCategory.GENERAL,
            Achievement.AchievementRarity.UNCOMMON, "complete_quest:10"));

        addAchievement(new Achievement("achievement_hunter", "Achievement Hunter",
            "Unlock 25 achievements", Achievement.AchievementCategory.GENERAL,
            Achievement.AchievementRarity.EPIC, "unlock_achievement:25"));

        addAchievement(new Achievement("legend", "Legend",
            "Unlock all achievements", Achievement.AchievementCategory.GENERAL,
            Achievement.AchievementRarity.LEGENDARY, "unlock_achievement:50"));
    }

    /**
     * Add an achievement to the registry
     */
    private void addAchievement(Achievement achievement) {
        achievements.put(achievement.getId(), achievement);
    }

    /**
     * Update a stat and check for achievement unlocks
     */
    public void updateStat(String statType, int amount) {
        int oldValue = stats.getOrDefault(statType, 0);
        int newValue = oldValue + amount;
        stats.put(statType, newValue);

        // Check if any achievements should be unlocked
        checkAchievements(statType, newValue);
    }

    /**
     * Check if any achievements should be unlocked for a stat
     */
    private void checkAchievements(String statType, int value) {
        List<Achievement> newlyUnlocked = new ArrayList<>();

        for (Achievement achievement : achievements.values()) {
            if (!achievement.isUnlocked() && achievement.matchesRequirement(statType, value)) {
                achievement.unlock();
                newlyUnlocked.add(achievement);

                SteveMod.LOGGER.info("Steve '{}' unlocked achievement: {} ({})",
                    steveName, achievement.getTitle(), achievement.getRarity());
            }
        }

        // Check for meta-achievement (achievement_hunter)
        if (!newlyUnlocked.isEmpty()) {
            int unlockedCount = getUnlockedCount();
            updateStat("unlock_achievement", newlyUnlocked.size());
        }

        if (!newlyUnlocked.isEmpty()) {
            saveAchievements();
        }
    }

    /**
     * Manually unlock an achievement
     */
    public boolean unlockAchievement(String achievementId) {
        Achievement achievement = achievements.get(achievementId);
        if (achievement != null && !achievement.isUnlocked()) {
            achievement.unlock();
            SteveMod.LOGGER.info("Steve '{}' unlocked achievement: {}",
                steveName, achievement.getTitle());
            saveAchievements();
            return true;
        }
        return false;
    }

    /**
     * Get unlocked achievements
     */
    public List<Achievement> getUnlockedAchievements() {
        List<Achievement> unlocked = new ArrayList<>();
        for (Achievement achievement : achievements.values()) {
            if (achievement.isUnlocked()) {
                unlocked.add(achievement);
            }
        }
        return unlocked;
    }

    /**
     * Get locked achievements
     */
    public List<Achievement> getLockedAchievements() {
        List<Achievement> locked = new ArrayList<>();
        for (Achievement achievement : achievements.values()) {
            if (!achievement.isUnlocked()) {
                locked.add(achievement);
            }
        }
        return locked;
    }

    /**
     * Get achievements by category
     */
    public List<Achievement> getAchievementsByCategory(Achievement.AchievementCategory category) {
        List<Achievement> categoryAchievements = new ArrayList<>();
        for (Achievement achievement : achievements.values()) {
            if (achievement.getCategory() == category) {
                categoryAchievements.add(achievement);
            }
        }
        return categoryAchievements;
    }

    /**
     * Get count of unlocked achievements
     */
    public int getUnlockedCount() {
        return (int) achievements.values().stream()
            .filter(Achievement::isUnlocked)
            .count();
    }

    /**
     * Get total achievement count
     */
    public int getTotalCount() {
        return achievements.size();
    }

    /**
     * Get completion percentage
     */
    public int getCompletionPercentage() {
        if (achievements.isEmpty()) {
            return 0;
        }
        return (getUnlockedCount() * 100) / getTotalCount();
    }

    /**
     * Get achievement summary string
     */
    public String getAchievementSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ACHIEVEMENTS ===\n");
        sb.append("Progress: ").append(getUnlockedCount())
          .append("/").append(getTotalCount())
          .append(" (").append(getCompletionPercentage()).append("%)\n\n");

        // Group by category
        for (Achievement.AchievementCategory category : Achievement.AchievementCategory.values()) {
            List<Achievement> categoryAchievements = getAchievementsByCategory(category);
            if (!categoryAchievements.isEmpty()) {
                sb.append(category).append(":\n");
                for (Achievement achievement : categoryAchievements) {
                    sb.append("  ").append(achievement.getDisplayString()).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Get recent achievements (last 5 unlocked)
     */
    public List<Achievement> getRecentAchievements() {
        return getUnlockedAchievements().stream()
            .sorted((a, b) -> Long.compare(b.getUnlockTime(), a.getUnlockTime()))
            .limit(5)
            .toList();
    }

    /**
     * Save achievements to disk
     */
    public void saveAchievements() {
        try {
            Path filePath = Paths.get(ACHIEVEMENT_DATA_DIR, steveName + "_achievements.json");

            Map<String, Object> data = new HashMap<>();
            data.put("achievements", achievements);
            data.put("stats", stats);

            String json = GSON.toJson(data);
            Files.writeString(filePath, json);

            SteveMod.LOGGER.debug("Saved achievements for Steve '{}'", steveName);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save achievements", e);
        }
    }

    /**
     * Load achievements from disk
     */
    public void loadAchievements() {
        try {
            Path filePath = Paths.get(ACHIEVEMENT_DATA_DIR, steveName + "_achievements.json");

            if (!Files.exists(filePath)) {
                return;
            }

            String json = Files.readString(filePath);
            // Note: Full deserialization would require custom deserializer
            // For now, this structure supports basic persistence

            SteveMod.LOGGER.info("Loaded achievements for Steve '{}'", steveName);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load achievements", e);
        }
    }

    /**
     * Get stat value
     */
    public int getStat(String statType) {
        return stats.getOrDefault(statType, 0);
    }
}
