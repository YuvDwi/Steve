package com.steve.ai.quest;

/**
 * Represents an achievement that can be unlocked
 * Achievements are earned through specific accomplishments
 */
public class Achievement {
    private final String id;
    private final String title;
    private final String description;
    private final AchievementCategory category;
    private final AchievementRarity rarity;
    private final String requirement; // e.g., "mine_diamond:1", "kill_zombie:100"
    private boolean unlocked;
    private long unlockTime;

    public Achievement(String id, String title, String description,
                      AchievementCategory category, AchievementRarity rarity,
                      String requirement) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
        this.rarity = rarity;
        this.requirement = requirement;
        this.unlocked = false;
        this.unlockTime = 0;
    }

    /**
     * Unlock this achievement
     */
    public void unlock() {
        if (!unlocked) {
            unlocked = true;
            unlockTime = System.currentTimeMillis();
        }
    }

    /**
     * Check if requirement matches a stat
     */
    public boolean matchesRequirement(String statType, int value) {
        String[] parts = requirement.split(":");
        if (parts.length != 2) {
            return false;
        }

        String reqType = parts[0];
        int reqValue = Integer.parseInt(parts[1]);

        return reqType.equals(statType) && value >= reqValue;
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AchievementCategory getCategory() {
        return category;
    }

    public AchievementRarity getRarity() {
        return rarity;
    }

    public String getRequirement() {
        return requirement;
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    public long getUnlockTime() {
        return unlockTime;
    }

    public void setUnlocked(boolean unlocked) {
        this.unlocked = unlocked;
    }

    public void setUnlockTime(long unlockTime) {
        this.unlockTime = unlockTime;
    }

    /**
     * Get display string with rarity indicator
     */
    public String getDisplayString() {
        String raritySymbol = switch (rarity) {
            case COMMON -> "⭐";
            case UNCOMMON -> "⭐⭐";
            case RARE -> "⭐⭐⭐";
            case EPIC -> "⭐⭐⭐⭐";
            case LEGENDARY -> "⭐⭐⭐⭐⭐";
        };

        return String.format("%s %s %s - %s",
            unlocked ? "✓" : "✗",
            raritySymbol,
            title,
            description);
    }

    /**
     * Achievement categories
     */
    public enum AchievementCategory {
        MINING,      // Mining-related achievements
        CRAFTING,    // Crafting achievements
        COMBAT,      // Combat achievements
        BUILDING,    // Building achievements
        FARMING,     // Farming achievements
        EXPLORATION, // Exploration achievements
        REDSTONE,    // Redstone achievements
        GENERAL      // General achievements
    }

    /**
     * Achievement rarity levels
     */
    public enum AchievementRarity {
        COMMON,      // Easy to get
        UNCOMMON,    // Moderate difficulty
        RARE,        // Challenging
        EPIC,        // Very challenging
        LEGENDARY    // Extremely difficult
    }
}
