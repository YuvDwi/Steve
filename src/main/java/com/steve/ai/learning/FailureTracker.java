package com.steve.ai.learning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tracks failed actions and their contexts for learning and improvement
 * Persistent storage allows Steve to learn from past mistakes across sessions
 */
public class FailureTracker {
    private static final String FAILURE_LOG_DIR = "config/steve/failures/";
    private static final int MAX_FAILURES_PER_STEVE = 500; // Limit to prevent file bloat
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String steveName;
    private final List<FailureRecord> failures;
    private final Map<String, Integer> failureCountByAction;
    private final Map<String, Integer> failureCountByError;

    public FailureTracker(String steveName) {
        this.steveName = steveName;
        this.failures = new ArrayList<>();
        this.failureCountByAction = new HashMap<>();
        this.failureCountByError = new HashMap<>();
        loadFromFile();
    }

    /**
     * Record a failed action
     */
    public void recordFailure(String action, Map<String, Object> parameters,
                              String errorMessage, String context) {
        FailureRecord record = new FailureRecord(
            System.currentTimeMillis(),
            action,
            parameters,
            errorMessage,
            context
        );

        failures.add(record);

        // Update counters
        failureCountByAction.merge(action, 1, Integer::sum);
        failureCountByError.merge(errorMessage, 1, Integer::sum);

        // Prune old failures if needed
        if (failures.size() > MAX_FAILURES_PER_STEVE) {
            failures.remove(0); // Remove oldest
        }

        SteveMod.LOGGER.info("Steve '{}' recorded failure: {} - {}",
            steveName, action, errorMessage);

        saveToFile();
    }

    /**
     * Get all failures for a specific action type
     */
    public List<FailureRecord> getFailuresForAction(String action) {
        return failures.stream()
            .filter(f -> f.action.equals(action))
            .collect(Collectors.toList());
    }

    /**
     * Get recent failures (last N)
     */
    public List<FailureRecord> getRecentFailures(int count) {
        int startIndex = Math.max(0, failures.size() - count);
        return new ArrayList<>(failures.subList(startIndex, failures.size()));
    }

    /**
     * Get failure count for specific action
     */
    public int getFailureCount(String action) {
        return failureCountByAction.getOrDefault(action, 0);
    }

    /**
     * Get total failure count
     */
    public int getTotalFailures() {
        return failures.size();
    }

    /**
     * Get most common failures
     */
    public Map<String, Integer> getMostCommonFailures(int limit) {
        return failureCountByAction.entrySet().stream()
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
     * Get most common error messages
     */
    public Map<String, Integer> getMostCommonErrors(int limit) {
        return failureCountByError.entrySet().stream()
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
     * Check if action has failed recently (within last N attempts)
     */
    public boolean hasRecentFailure(String action, int withinLast) {
        List<FailureRecord> recent = getRecentFailures(withinLast);
        return recent.stream().anyMatch(f -> f.action.equals(action));
    }

    /**
     * Get failure rate for an action (percentage)
     */
    public double getFailureRate(String action, int sampleSize) {
        List<FailureRecord> recentForAction = failures.stream()
            .filter(f -> f.action.equals(action))
            .skip(Math.max(0, failures.stream().filter(f -> f.action.equals(action)).count() - sampleSize))
            .collect(Collectors.toList());

        if (recentForAction.isEmpty()) {
            return 0.0;
        }

        return (double) recentForAction.size() / Math.max(1, sampleSize) * 100.0;
    }

    /**
     * Clear all failure records
     */
    public void clear() {
        failures.clear();
        failureCountByAction.clear();
        failureCountByError.clear();
        saveToFile();
    }

    /**
     * Generate failure summary
     */
    public String generateSummary() {
        if (failures.isEmpty()) {
            return "No failures recorded yet.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Failure Summary for Steve '").append(steveName).append("':\n");
        sb.append("Total Failures: ").append(getTotalFailures()).append("\n\n");

        sb.append("Most Common Failed Actions:\n");
        getMostCommonFailures(5).forEach((action, count) ->
            sb.append("  - ").append(action).append(": ").append(count).append(" times\n")
        );

        sb.append("\nMost Common Errors:\n");
        getMostCommonErrors(5).forEach((error, count) ->
            sb.append("  - ").append(error).append(": ").append(count).append(" times\n")
        );

        return sb.toString();
    }

    /**
     * Save failures to JSON file
     */
    private void saveToFile() {
        try {
            Path dirPath = Paths.get(FAILURE_LOG_DIR);
            Files.createDirectories(dirPath);

            String filename = FAILURE_LOG_DIR + steveName + "_failures.json";
            File file = new File(filename);

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(failures, writer);
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save failure log for Steve '{}'", steveName, e);
        }
    }

    /**
     * Load failures from JSON file
     */
    private void loadFromFile() {
        try {
            String filename = FAILURE_LOG_DIR + steveName + "_failures.json";
            File file = new File(filename);

            if (!file.exists()) {
                SteveMod.LOGGER.info("No failure log found for Steve '{}', starting fresh", steveName);
                return;
            }

            try (FileReader reader = new FileReader(file)) {
                Type listType = new TypeToken<ArrayList<FailureRecord>>(){}.getType();
                List<FailureRecord> loaded = GSON.fromJson(reader, listType);

                if (loaded != null) {
                    failures.addAll(loaded);

                    // Rebuild counters
                    for (FailureRecord record : failures) {
                        failureCountByAction.merge(record.action, 1, Integer::sum);
                        failureCountByError.merge(record.errorMessage, 1, Integer::sum);
                    }

                    SteveMod.LOGGER.info("Loaded {} failure records for Steve '{}'",
                        failures.size(), steveName);
                }
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load failure log for Steve '{}'", steveName, e);
        }
    }

    /**
     * Individual failure record
     */
    public static class FailureRecord {
        private final long timestamp;
        private final String action;
        private final Map<String, Object> parameters;
        private final String errorMessage;
        private final String context;

        public FailureRecord(long timestamp, String action, Map<String, Object> parameters,
                           String errorMessage, String context) {
            this.timestamp = timestamp;
            this.action = action;
            this.parameters = parameters;
            this.errorMessage = errorMessage;
            this.context = context;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getAction() {
            return action;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getContext() {
            return context;
        }

        @Override
        public String toString() {
            return String.format("[%tF %<tT] %s failed: %s",
                new Date(timestamp), action, errorMessage);
        }
    }
}
