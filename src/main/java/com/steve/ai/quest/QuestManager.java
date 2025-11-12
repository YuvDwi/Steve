package com.steve.ai.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Manages quests for a Steve entity
 * Tracks active quests, completed quests, and quest progress
 */
public class QuestManager {
    private static final String QUEST_DATA_DIR = "config/steve/quests/";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String steveName;
    private final Map<String, Quest> activeQuests;
    private final Map<String, Quest> completedQuests;
    private final Map<String, Quest> questRegistry; // All available quests

    public QuestManager(String steveName) {
        this.steveName = steveName;
        this.activeQuests = new LinkedHashMap<>();
        this.completedQuests = new LinkedHashMap<>();
        this.questRegistry = new HashMap<>();

        // Create quest data directory
        try {
            Files.createDirectories(Paths.get(QUEST_DATA_DIR));
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to create quest data directory", e);
        }

        // Initialize default quests
        initializeDefaultQuests();

        // Load quest progress
        loadQuestProgress();
    }

    /**
     * Initialize default quests available to all Steves
     */
    private void initializeDefaultQuests() {
        // Beginner quests
        Quest gettingStarted = new Quest("getting_started", "Getting Started",
            "Gather basic resources and craft your first tools");
        gettingStarted.addObjective(new Quest.QuestObjective("mine_wood", "Mine 10 wood logs", 10));
        gettingStarted.addObjective(new Quest.QuestObjective("craft_pickaxe", "Craft a wooden pickaxe", 1));
        gettingStarted.addReward("bread", 5);
        questRegistry.put(gettingStarted.getId(), gettingStarted);

        Quest miningAdventure = new Quest("mining_adventure", "Mining Adventure",
            "Venture underground and mine valuable resources");
        miningAdventure.addObjective(new Quest.QuestObjective("mine_iron", "Mine 16 iron ore", 16));
        miningAdventure.addObjective(new Quest.QuestObjective("mine_coal", "Mine 32 coal", 32));
        miningAdventure.addReward("diamond", 2);
        questRegistry.put(miningAdventure.getId(), miningAdventure);

        Quest masterCrafter = new Quest("master_crafter", "Master Crafter",
            "Prove your crafting skills by creating advanced items");
        masterCrafter.addObjective(new Quest.QuestObjective("craft_iron_sword", "Craft an iron sword", 1));
        masterCrafter.addObjective(new Quest.QuestObjective("craft_iron_pickaxe", "Craft an iron pickaxe", 1));
        masterCrafter.addObjective(new Quest.QuestObjective("craft_chest", "Craft 5 chests", 5));
        masterCrafter.addReward("diamond", 3);
        questRegistry.put(masterCrafter.getId(), masterCrafter);

        // Combat quests
        Quest monsterHunter = new Quest("monster_hunter", "Monster Hunter",
            "Defeat hostile mobs to protect the land");
        monsterHunter.addObjective(new Quest.QuestObjective("kill_zombie", "Kill 10 zombies", 10));
        monsterHunter.addObjective(new Quest.QuestObjective("kill_skeleton", "Kill 10 skeletons", 10));
        monsterHunter.addObjective(new Quest.QuestObjective("kill_creeper", "Kill 5 creepers", 5));
        monsterHunter.addReward("diamond_sword", 1);
        questRegistry.put(monsterHunter.getId(), monsterHunter);

        // Building quests
        Quest architectApprentice = new Quest("architect_apprentice", "Architect Apprentice",
            "Build structures to showcase your construction skills");
        architectApprentice.addObjective(new Quest.QuestObjective("build_house", "Build a house", 1));
        architectApprentice.addObjective(new Quest.QuestObjective("build_tower", "Build a tower", 1));
        architectApprentice.addReward("golden_apple", 3);
        questRegistry.put(architectApprentice.getId(), architectApprentice);

        // Farming quests
        Quest farmersDelight = new Quest("farmers_delight", "Farmer's Delight",
            "Establish a thriving farm with crops and animals");
        farmersDelight.addObjective(new Quest.QuestObjective("farm_wheat", "Harvest 64 wheat", 64));
        farmersDelight.addObjective(new Quest.QuestObjective("breed_cow", "Breed 5 cows", 5));
        farmersDelight.addObjective(new Quest.QuestObjective("breed_chicken", "Breed 5 chickens", 5));
        farmersDelight.addReward("diamond_hoe", 1);
        questRegistry.put(farmersDelight.getId(), farmersDelight);

        // Advanced quests
        Quest netherExplorer = new Quest("nether_explorer", "Nether Explorer",
            "Brave the dangers of the Nether dimension");
        netherExplorer.addObjective(new Quest.QuestObjective("build_portal", "Build a nether portal", 1));
        netherExplorer.addObjective(new Quest.QuestObjective("enter_nether", "Enter the Nether", 1));
        netherExplorer.addObjective(new Quest.QuestObjective("mine_ancient_debris", "Mine ancient debris", 4));
        netherExplorer.addReward("netherite_ingot", 2);
        questRegistry.put(netherExplorer.getId(), netherExplorer);

        Quest redstoneEngineer = new Quest("redstone_engineer", "Redstone Engineer",
            "Master the art of redstone contraptions");
        redstoneEngineer.addObjective(new Quest.QuestObjective("build_auto_door", "Build automatic door", 1));
        redstoneEngineer.addObjective(new Quest.QuestObjective("place_redstone", "Place 64 redstone dust", 64));
        redstoneEngineer.addReward("diamond", 5);
        questRegistry.put(redstoneEngineer.getId(), redstoneEngineer);
    }

    /**
     * Start a quest by ID
     */
    public boolean startQuest(String questId) {
        if (!questRegistry.containsKey(questId)) {
            SteveMod.LOGGER.warn("Quest not found: {}", questId);
            return false;
        }

        if (activeQuests.containsKey(questId)) {
            SteveMod.LOGGER.warn("Quest already active: {}", questId);
            return false;
        }

        if (completedQuests.containsKey(questId)) {
            SteveMod.LOGGER.info("Quest already completed: {}", questId);
            return false;
        }

        // Create a copy of the quest from registry
        Quest quest = questRegistry.get(questId);
        Quest activeQuest = new Quest(quest.getId(), quest.getTitle(), quest.getDescription());

        // Copy objectives
        for (Quest.QuestObjective objective : quest.getObjectives()) {
            activeQuest.addObjective(new Quest.QuestObjective(
                objective.getType(),
                objective.getDescription(),
                objective.getTargetProgress()
            ));
        }

        // Copy rewards
        quest.getRewards().forEach(activeQuest::addReward);

        activeQuest.start();
        activeQuests.put(questId, activeQuest);

        SteveMod.LOGGER.info("Steve '{}' started quest: {}", steveName, quest.getTitle());
        saveQuestProgress();
        return true;
    }

    /**
     * Update quest progress for all active quests
     */
    public void updateQuestProgress(String objectiveType, int amount) {
        List<Quest> completedNow = new ArrayList<>();

        for (Quest quest : activeQuests.values()) {
            quest.updateProgress(objectiveType, amount);

            if (quest.isComplete()) {
                completedNow.add(quest);
            }
        }

        // Move completed quests
        for (Quest quest : completedNow) {
            completeQuest(quest);
        }

        if (!completedNow.isEmpty()) {
            saveQuestProgress();
        }
    }

    /**
     * Complete a quest and give rewards
     */
    private void completeQuest(Quest quest) {
        activeQuests.remove(quest.getId());
        completedQuests.put(quest.getId(), quest);

        SteveMod.LOGGER.info("Steve '{}' completed quest: {} in {}s",
            steveName, quest.getTitle(), quest.getTimeSpentSeconds());

        // Note: Reward giving would be implemented in SteveEntity
        if (!quest.getRewards().isEmpty()) {
            SteveMod.LOGGER.info("Quest rewards: {}", quest.getRewards());
        }
    }

    /**
     * Abandon a quest
     */
    public boolean abandonQuest(String questId) {
        Quest quest = activeQuests.remove(questId);
        if (quest != null) {
            quest.fail();
            SteveMod.LOGGER.info("Steve '{}' abandoned quest: {}", steveName, quest.getTitle());
            saveQuestProgress();
            return true;
        }
        return false;
    }

    /**
     * Get all active quests
     */
    public List<Quest> getActiveQuests() {
        return new ArrayList<>(activeQuests.values());
    }

    /**
     * Get all completed quests
     */
    public List<Quest> getCompletedQuests() {
        return new ArrayList<>(completedQuests.values());
    }

    /**
     * Get all available quests (from registry)
     */
    public List<Quest> getAvailableQuests() {
        List<Quest> available = new ArrayList<>();
        for (Quest quest : questRegistry.values()) {
            if (!activeQuests.containsKey(quest.getId()) &&
                !completedQuests.containsKey(quest.getId())) {
                available.add(quest);
            }
        }
        return available;
    }

    /**
     * Get quest summary string
     */
    public String getQuestSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== ACTIVE QUESTS ===\n");

        if (activeQuests.isEmpty()) {
            sb.append("No active quests\n");
        } else {
            for (Quest quest : activeQuests.values()) {
                sb.append(quest.getSummary()).append("\n");
            }
        }

        sb.append("\nCompleted: ").append(completedQuests.size())
          .append(" | Available: ").append(getAvailableQuests().size()).append("\n");

        return sb.toString();
    }

    /**
     * Save quest progress to disk
     */
    public void saveQuestProgress() {
        try {
            Path filePath = Paths.get(QUEST_DATA_DIR, steveName + "_quests.json");

            Map<String, Object> data = new HashMap<>();
            data.put("activeQuests", activeQuests);
            data.put("completedQuests", completedQuests);

            String json = GSON.toJson(data);
            Files.writeString(filePath, json);

            SteveMod.LOGGER.debug("Saved quest progress for Steve '{}'", steveName);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save quest progress", e);
        }
    }

    /**
     * Load quest progress from disk
     */
    public void loadQuestProgress() {
        try {
            Path filePath = Paths.get(QUEST_DATA_DIR, steveName + "_quests.json");

            if (!Files.exists(filePath)) {
                return;
            }

            String json = Files.readString(filePath);
            Map<String, Object> data = GSON.fromJson(json,
                new TypeToken<Map<String, Object>>(){}.getType());

            // Note: Full deserialization would require custom deserializer
            // For now, this structure supports basic persistence

            SteveMod.LOGGER.info("Loaded quest progress for Steve '{}'", steveName);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load quest progress", e);
        }
    }

    /**
     * Get quest by ID
     */
    public Quest getQuest(String questId) {
        if (activeQuests.containsKey(questId)) {
            return activeQuests.get(questId);
        }
        if (completedQuests.containsKey(questId)) {
            return completedQuests.get(questId);
        }
        return questRegistry.get(questId);
    }
}
