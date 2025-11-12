package com.steve.ai.learning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;
import com.steve.ai.learning.LearningSystem.LearningInsight;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Knowledge base that stores learned patterns and insights
 * Persistent storage allows accumulated learning across sessions
 */
public class ActionKnowledgeBase {
    private static final String KNOWLEDGE_DIR = "config/steve/knowledge/";
    private static final int MAX_INSIGHTS = 200;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String steveName;
    private final List<LearningInsight> insights;
    private final Map<String, List<String>> actionTips; // Action -> List of tips
    private final Map<String, Integer> successCounts; // Track successes for learning

    public ActionKnowledgeBase(String steveName) {
        this.steveName = steveName;
        this.insights = new ArrayList<>();
        this.actionTips = new HashMap<>();
        this.successCounts = new HashMap<>();
        loadFromFile();
    }

    /**
     * Add a learning insight to the knowledge base
     */
    public void addInsight(LearningInsight insight) {
        // Check if similar insight already exists
        boolean exists = insights.stream()
            .anyMatch(existing ->
                existing.getTitle().equals(insight.getTitle()) &&
                existing.getCategory().equals(insight.getCategory())
            );

        if (!exists) {
            insights.add(insight);

            // Prune old insights if needed
            if (insights.size() > MAX_INSIGHTS) {
                // Remove oldest low-confidence insights
                insights.sort(Comparator.comparingDouble(LearningInsight::getConfidence));
                insights.remove(0);
            }

            SteveMod.LOGGER.info("Steve '{}' learned: {}", steveName, insight.getTitle());
            saveToFile();
        }
    }

    /**
     * Add a tip for a specific action
     */
    public void addActionTip(String action, String tip) {
        actionTips.computeIfAbsent(action, k -> new ArrayList<>()).add(tip);

        // Limit tips per action
        List<String> tips = actionTips.get(action);
        if (tips.size() > 10) {
            tips.remove(0); // Remove oldest
        }

        saveToFile();
    }

    /**
     * Record a successful action
     */
    public void recordSuccess(String action) {
        successCounts.merge(action, 1, Integer::sum);
        saveToFile();
    }

    /**
     * Get tips for a specific action
     */
    public List<String> getTipsForAction(String action) {
        return actionTips.getOrDefault(action, Collections.emptyList());
    }

    /**
     * Get recent insights
     */
    public List<LearningInsight> getRecentInsights(int count) {
        // Sort by timestamp (most recent first)
        return insights.stream()
            .sorted(Comparator.comparingLong(LearningInsight::getTimestamp).reversed())
            .limit(count)
            .collect(Collectors.toList());
    }

    /**
     * Get insights by category
     */
    public List<LearningInsight> getInsightsByCategory(String category) {
        return insights.stream()
            .filter(i -> i.getCategory().equals(category))
            .sorted(Comparator.comparingDouble(LearningInsight::getConfidence).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Get high-confidence insights
     */
    public List<LearningInsight> getHighConfidenceInsights(double minConfidence) {
        return insights.stream()
            .filter(i -> i.getConfidence() >= minConfidence)
            .sorted(Comparator.comparingDouble(LearningInsight::getConfidence).reversed())
            .collect(Collectors.toList());
    }

    /**
     * Get success count for an action
     */
    public int getSuccessCount(String action) {
        return successCounts.getOrDefault(action, 0);
    }

    /**
     * Get most successful actions
     */
    public Map<String, Integer> getMostSuccessfulActions(int limit) {
        return successCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (e1, e2) -> e1,
                LinkedHashMap::new
            ));
    }

    /**
     * Get total insights count
     */
    public int getInsightCount() {
        return insights.size();
    }

    /**
     * Generate knowledge summary for LLM context
     */
    public String generateKnowledgeSummary() {
        if (insights.isEmpty() && actionTips.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== LEARNED KNOWLEDGE ===\n");

        // Add high-confidence insights
        List<LearningInsight> topInsights = getHighConfidenceInsights(0.7);
        if (!topInsights.isEmpty()) {
            sb.append("Key Insights:\n");
            for (LearningInsight insight : topInsights.stream().limit(5).collect(Collectors.toList())) {
                sb.append("- ").append(insight.getRecommendation()).append("\n");
            }
        }

        // Add action tips
        if (!actionTips.isEmpty()) {
            sb.append("\nAction Tips:\n");
            actionTips.entrySet().stream()
                .limit(5)
                .forEach(entry -> {
                    sb.append("- ").append(entry.getKey()).append(": ");
                    sb.append(String.join(", ", entry.getValue())).append("\n");
                });
        }

        // Add success patterns
        Map<String, Integer> topSuccesses = getMostSuccessfulActions(3);
        if (!topSuccesses.isEmpty()) {
            sb.append("\nMost Reliable Actions:\n");
            topSuccesses.forEach((action, count) ->
                sb.append("- ").append(action).append(" (").append(count).append(" successes)\n")
            );
        }

        return sb.toString();
    }

    /**
     * Clear all knowledge (reset learning)
     */
    public void clear() {
        insights.clear();
        actionTips.clear();
        successCounts.clear();
        saveToFile();
    }

    /**
     * Save knowledge base to JSON file
     */
    private void saveToFile() {
        try {
            Path dirPath = Paths.get(KNOWLEDGE_DIR);
            Files.createDirectories(dirPath);

            String filename = KNOWLEDGE_DIR + steveName + "_knowledge.json";
            File file = new File(filename);

            Map<String, Object> data = new HashMap<>();
            data.put("insights", insights);
            data.put("actionTips", actionTips);
            data.put("successCounts", successCounts);

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save knowledge base for Steve '{}'", steveName, e);
        }
    }

    /**
     * Load knowledge base from JSON file
     */
    @SuppressWarnings("unchecked")
    private void loadFromFile() {
        try {
            String filename = KNOWLEDGE_DIR + steveName + "_knowledge.json";
            File file = new File(filename);

            if (!file.exists()) {
                SteveMod.LOGGER.info("No knowledge base found for Steve '{}', starting fresh", steveName);
                return;
            }

            try (FileReader reader = new FileReader(file)) {
                Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String, Object> data = GSON.fromJson(reader, mapType);

                if (data != null) {
                    // Load insights
                    if (data.containsKey("insights")) {
                        Type insightListType = new TypeToken<ArrayList<LearningInsight>>(){}.getType();
                        List<LearningInsight> loadedInsights = GSON.fromJson(
                            GSON.toJson(data.get("insights")),
                            insightListType
                        );
                        if (loadedInsights != null) {
                            insights.addAll(loadedInsights);
                        }
                    }

                    // Load action tips
                    if (data.containsKey("actionTips")) {
                        Type tipsType = new TypeToken<Map<String, List<String>>>(){}.getType();
                        Map<String, List<String>> loadedTips = GSON.fromJson(
                            GSON.toJson(data.get("actionTips")),
                            tipsType
                        );
                        if (loadedTips != null) {
                            actionTips.putAll(loadedTips);
                        }
                    }

                    // Load success counts
                    if (data.containsKey("successCounts")) {
                        Type countsType = new TypeToken<Map<String, Double>>(){}.getType();
                        Map<String, Double> loadedCounts = GSON.fromJson(
                            GSON.toJson(data.get("successCounts")),
                            countsType
                        );
                        if (loadedCounts != null) {
                            loadedCounts.forEach((k, v) -> successCounts.put(k, v.intValue()));
                        }
                    }

                    SteveMod.LOGGER.info("Loaded knowledge base for Steve '{}': {} insights, {} tips",
                        steveName, insights.size(), actionTips.size());
                }
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load knowledge base for Steve '{}'", steveName, e);
        }
    }
}
