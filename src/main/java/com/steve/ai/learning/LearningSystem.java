package com.steve.ai.learning;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.learning.FailureTracker.FailureRecord;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Learning system that identifies patterns in failures and generates insights
 * Helps Steve learn from mistakes and improve over time
 */
public class LearningSystem {
    private final SteveEntity steve;
    private final FailureTracker failureTracker;
    private final ActionKnowledgeBase knowledgeBase;

    // Pattern recognition thresholds
    private static final int MIN_SAMPLES_FOR_PATTERN = 3;
    private static final double HIGH_FAILURE_RATE_THRESHOLD = 50.0; // 50%
    private static final int RECENT_WINDOW = 20; // Last 20 actions

    public LearningSystem(SteveEntity steve, FailureTracker failureTracker, ActionKnowledgeBase knowledgeBase) {
        this.steve = steve;
        this.failureTracker = failureTracker;
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * Analyze failures and identify patterns
     */
    public List<Pattern> identifyPatterns() {
        List<Pattern> patterns = new ArrayList<>();

        // Pattern 1: Repeated failures with same action
        patterns.addAll(findRepeatedActionFailures());

        // Pattern 2: Failures with common error messages
        patterns.addAll(findCommonErrorPatterns());

        // Pattern 3: Sequential failure chains
        patterns.addAll(findFailureChains());

        // Pattern 4: Parameter-specific failures
        patterns.addAll(findParameterPatterns());

        return patterns;
    }

    /**
     * Find actions that fail repeatedly
     */
    private List<Pattern> findRepeatedActionFailures() {
        List<Pattern> patterns = new ArrayList<>();

        Map<String, Integer> failureCounts = failureTracker.getMostCommonFailures(10);

        for (Map.Entry<String, Integer> entry : failureCounts.entrySet()) {
            String action = entry.getKey();
            int count = entry.getValue();

            if (count >= MIN_SAMPLES_FOR_PATTERN) {
                double failureRate = failureTracker.getFailureRate(action, RECENT_WINDOW);

                if (failureRate > HIGH_FAILURE_RATE_THRESHOLD) {
                    Pattern pattern = new Pattern(
                        PatternType.REPEATED_ACTION_FAILURE,
                        action,
                        "Action '" + action + "' has high failure rate: " + String.format("%.1f%%", failureRate),
                        failureRate / 100.0
                    );
                    patterns.add(pattern);
                }
            }
        }

        return patterns;
    }

    /**
     * Find common error messages that appear across different actions
     */
    private List<Pattern> findCommonErrorPatterns() {
        List<Pattern> patterns = new ArrayList<>();

        Map<String, Integer> errorCounts = failureTracker.getMostCommonErrors(10);

        for (Map.Entry<String, Integer> entry : errorCounts.entrySet()) {
            String error = entry.getKey();
            int count = entry.getValue();

            if (count >= MIN_SAMPLES_FOR_PATTERN) {
                Pattern pattern = new Pattern(
                    PatternType.COMMON_ERROR,
                    error,
                    "Common error: '" + error + "' occurred " + count + " times",
                    Math.min(count / 10.0, 1.0)
                );
                patterns.add(pattern);
            }
        }

        return patterns;
    }

    /**
     * Find chains of failures (one failure leading to another)
     */
    private List<Pattern> findFailureChains() {
        List<Pattern> patterns = new ArrayList<>();

        List<FailureRecord> recent = failureTracker.getRecentFailures(10);

        if (recent.size() >= 3) {
            // Check if last 3+ failures are related
            Set<String> recentActions = recent.stream()
                .map(FailureRecord::getAction)
                .collect(Collectors.toSet());

            if (recentActions.size() > 1) {
                Pattern pattern = new Pattern(
                    PatternType.FAILURE_CHAIN,
                    "multiple",
                    "Chain of " + recent.size() + " failures detected involving: " + recentActions,
                    0.8
                );
                patterns.add(pattern);
            }
        }

        return patterns;
    }

    /**
     * Find patterns related to specific parameters
     */
    private List<Pattern> findParameterPatterns() {
        List<Pattern> patterns = new ArrayList<>();

        // Analyze parameter values in failures
        Map<String, List<FailureRecord>> failuresByAction = new HashMap<>();

        for (FailureRecord record : failureTracker.getRecentFailures(50)) {
            failuresByAction.computeIfAbsent(record.getAction(), k -> new ArrayList<>()).add(record);
        }

        for (Map.Entry<String, List<FailureRecord>> entry : failuresByAction.entrySet()) {
            String action = entry.getKey();
            List<FailureRecord> records = entry.getValue();

            if (records.size() >= MIN_SAMPLES_FOR_PATTERN) {
                // Check for common parameter values
                Map<String, Integer> paramValueCounts = new HashMap<>();

                for (FailureRecord record : records) {
                    if (record.getParameters() != null) {
                        for (Map.Entry<String, Object> param : record.getParameters().entrySet()) {
                            String key = param.getKey() + "=" + param.getValue();
                            paramValueCounts.merge(key, 1, Integer::sum);
                        }
                    }
                }

                // Find frequently occurring parameter values
                for (Map.Entry<String, Integer> paramEntry : paramValueCounts.entrySet()) {
                    if (paramEntry.getValue() >= MIN_SAMPLES_FOR_PATTERN) {
                        Pattern pattern = new Pattern(
                            PatternType.PARAMETER_PATTERN,
                            action,
                            "Action '" + action + "' often fails with parameter: " + paramEntry.getKey(),
                            0.6
                        );
                        patterns.add(pattern);
                    }
                }
            }
        }

        return patterns;
    }

    /**
     * Generate learning insights from patterns
     */
    public List<LearningInsight> generateInsights() {
        List<LearningInsight> insights = new ArrayList<>();
        List<Pattern> patterns = identifyPatterns();

        for (Pattern pattern : patterns) {
            LearningInsight insight = generateInsightFromPattern(pattern);
            if (insight != null) {
                insights.add(insight);

                // Store insight in knowledge base
                knowledgeBase.addInsight(insight);
            }
        }

        return insights;
    }

    /**
     * Generate actionable insight from a pattern
     */
    private LearningInsight generateInsightFromPattern(Pattern pattern) {
        switch (pattern.type) {
            case REPEATED_ACTION_FAILURE:
                return new LearningInsight(
                    "Avoid " + pattern.subject,
                    "The action '" + pattern.subject + "' has been failing frequently. " +
                    "Consider checking prerequisites, inventory, or environmental conditions before attempting.",
                    "avoidance",
                    pattern.confidence
                );

            case COMMON_ERROR:
                return new LearningInsight(
                    "Common error: " + pattern.subject,
                    "The error '" + pattern.subject + "' occurs frequently. " +
                    "This might indicate a systematic issue that needs addressing.",
                    "error_mitigation",
                    pattern.confidence
                );

            case FAILURE_CHAIN:
                return new LearningInsight(
                    "Failure cascade detected",
                    "Multiple failures in sequence suggest initial failure led to subsequent problems. " +
                    "Consider more robust error recovery and validation between steps.",
                    "chain_prevention",
                    pattern.confidence
                );

            case PARAMETER_PATTERN:
                return new LearningInsight(
                    "Parameter issue in " + pattern.subject,
                    pattern.description + ". Consider validating or adjusting this parameter.",
                    "parameter_adjustment",
                    pattern.confidence
                );

            default:
                return null;
        }
    }

    /**
     * Get actionable recommendations based on learning
     */
    public List<String> getRecommendations() {
        List<String> recommendations = new ArrayList<>();
        List<LearningInsight> insights = knowledgeBase.getRecentInsights(10);

        if (insights.isEmpty()) {
            recommendations.add("No learning insights available yet. Keep working to gather data.");
            return recommendations;
        }

        // Group insights by category
        Map<String, List<LearningInsight>> byCategory = insights.stream()
            .collect(Collectors.groupingBy(i -> i.category));

        for (Map.Entry<String, List<LearningInsight>> entry : byCategory.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                recommendations.add(entry.getValue().get(0).recommendation);
            }
        }

        return recommendations;
    }

    /**
     * Should we retry this action based on learning?
     */
    public boolean shouldRetry(String action, int attemptCount) {
        // Check if this action has high failure rate
        double failureRate = failureTracker.getFailureRate(action, RECENT_WINDOW);

        if (failureRate > 75.0 && attemptCount >= 2) {
            SteveMod.LOGGER.warn("Steve '{}' - Action '{}' has {}% failure rate, stopping retries",
                steve.getSteveName(), action, failureRate);
            return false;
        }

        // Check if we have recent failures for this action
        if (failureTracker.hasRecentFailure(action, 3) && attemptCount >= 3) {
            SteveMod.LOGGER.warn("Steve '{}' - Action '{}' failed recently, limiting retries",
                steve.getSteveName(), action);
            return false;
        }

        // Normal retry limit
        return attemptCount < 3;
    }

    /**
     * Pattern types
     */
    public enum PatternType {
        REPEATED_ACTION_FAILURE,
        COMMON_ERROR,
        FAILURE_CHAIN,
        PARAMETER_PATTERN
    }

    /**
     * Identified pattern
     */
    public static class Pattern {
        private final PatternType type;
        private final String subject;
        private final String description;
        private final double confidence;

        public Pattern(PatternType type, String subject, String description, double confidence) {
            this.type = type;
            this.subject = subject;
            this.description = description;
            this.confidence = confidence;
        }

        public PatternType getType() {
            return type;
        }

        public String getSubject() {
            return subject;
        }

        public String getDescription() {
            return description;
        }

        public double getConfidence() {
            return confidence;
        }
    }

    /**
     * Learning insight generated from patterns
     */
    public static class LearningInsight {
        private final String title;
        private final String recommendation;
        private final String category;
        private final double confidence;
        private final long timestamp;

        public LearningInsight(String title, String recommendation, String category, double confidence) {
            this.title = title;
            this.recommendation = recommendation;
            this.category = category;
            this.confidence = confidence;
            this.timestamp = System.currentTimeMillis();
        }

        public String getTitle() {
            return title;
        }

        public String getRecommendation() {
            return recommendation;
        }

        public String getCategory() {
            return category;
        }

        public double getConfidence() {
            return confidence;
        }

        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public String toString() {
            return String.format("[%.0f%% confidence] %s: %s",
                confidence * 100, title, recommendation);
        }
    }
}
