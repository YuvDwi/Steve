package com.steve.ai.learning;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;

import java.util.HashMap;
import java.util.Map;

/**
 * Adaptive retry strategy that learns optimal retry behavior
 * Adjusts retry counts and delays based on past success/failure patterns
 */
public class AdaptiveRetryStrategy {
    private final FailureTracker failureTracker;
    private final LearningSystem learningSystem;

    // Retry attempt tracking
    private final Map<String, Integer> retryAttempts;
    private final Map<String, Long> lastRetryTime;

    // Default retry settings
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000; // 1 second
    private static final long MAX_RETRY_DELAY_MS = 10000; // 10 seconds

    // Adaptive parameters
    private static final double HIGH_FAILURE_THRESHOLD = 0.6; // 60%
    private static final double LOW_FAILURE_THRESHOLD = 0.2; // 20%

    public AdaptiveRetryStrategy(FailureTracker failureTracker, LearningSystem learningSystem) {
        this.failureTracker = failureTracker;
        this.learningSystem = learningSystem;
        this.retryAttempts = new HashMap<>();
        this.lastRetryTime = new HashMap<>();
    }

    /**
     * Determine if an action should be retried
     */
    public boolean shouldRetry(Task task, String errorMessage) {
        String taskKey = getTaskKey(task);
        int attemptCount = retryAttempts.getOrDefault(taskKey, 0);

        // Check learning system recommendation
        if (!learningSystem.shouldRetry(task.getAction(), attemptCount)) {
            SteveMod.LOGGER.info("Learning system recommends not retrying: {}", task.getAction());
            resetRetries(taskKey);
            return false;
        }

        // Get adaptive max retries based on failure history
        int maxRetries = getAdaptiveMaxRetries(task.getAction());

        if (attemptCount >= maxRetries) {
            SteveMod.LOGGER.info("Max retries ({}) reached for: {}", maxRetries, task.getAction());
            resetRetries(taskKey);
            return false;
        }

        // Check if enough time has passed since last retry
        long now = System.currentTimeMillis();
        Long lastRetry = lastRetryTime.get(taskKey);

        if (lastRetry != null) {
            long delay = getAdaptiveRetryDelay(task.getAction(), attemptCount);
            long timeSinceLastRetry = now - lastRetry;

            if (timeSinceLastRetry < delay) {
                // Not enough time has passed, don't retry yet
                return false;
            }
        }

        // Increment retry count
        retryAttempts.put(taskKey, attemptCount + 1);
        lastRetryTime.put(taskKey, now);

        SteveMod.LOGGER.info("Retry attempt {}/{} for: {}",
            attemptCount + 1, maxRetries, task.getAction());

        return true;
    }

    /**
     * Reset retry counter for a task (called after success or giving up)
     */
    public void resetRetries(Task task) {
        String taskKey = getTaskKey(task);
        resetRetries(taskKey);
    }

    private void resetRetries(String taskKey) {
        retryAttempts.remove(taskKey);
        lastRetryTime.remove(taskKey);
    }

    /**
     * Get current retry attempt count
     */
    public int getRetryCount(Task task) {
        return retryAttempts.getOrDefault(getTaskKey(task), 0);
    }

    /**
     * Calculate adaptive max retries based on historical failure rate
     */
    private int getAdaptiveMaxRetries(String action) {
        double failureRate = failureTracker.getFailureRate(action, 20) / 100.0;

        if (failureRate > HIGH_FAILURE_THRESHOLD) {
            // High failure rate: reduce retries to 1-2
            return Math.max(1, DEFAULT_MAX_RETRIES - 2);
        } else if (failureRate < LOW_FAILURE_THRESHOLD) {
            // Low failure rate: allow more retries
            return DEFAULT_MAX_RETRIES + 2;
        } else {
            // Normal failure rate: use default
            return DEFAULT_MAX_RETRIES;
        }
    }

    /**
     * Calculate adaptive retry delay using exponential backoff
     */
    private long getAdaptiveRetryDelay(String action, int attemptCount) {
        double failureRate = failureTracker.getFailureRate(action, 20) / 100.0;

        // Base delay with exponential backoff
        long baseDelay = DEFAULT_RETRY_DELAY_MS * (long) Math.pow(2, attemptCount);

        // Adjust based on failure rate
        if (failureRate > HIGH_FAILURE_THRESHOLD) {
            // High failure rate: increase delay
            baseDelay *= 2;
        }

        return Math.min(baseDelay, MAX_RETRY_DELAY_MS);
    }

    /**
     * Get retry strategy info for logging
     */
    public String getStrategyInfo(String action) {
        int maxRetries = getAdaptiveMaxRetries(action);
        double failureRate = failureTracker.getFailureRate(action, 20);

        return String.format("Action: %s | Max Retries: %d | Failure Rate: %.1f%%",
            action, maxRetries, failureRate);
    }

    /**
     * Generate unique key for a task
     */
    private String getTaskKey(Task task) {
        return task.getAction() + "_" + System.identityHashCode(task);
    }

    /**
     * Get statistics on retry behavior
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("active_retries", retryAttempts.size());
        stats.put("total_failures_tracked", failureTracker.getTotalFailures());

        // Calculate average retry count
        double avgRetries = retryAttempts.values().stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
        stats.put("average_retry_count", avgRetries);

        return stats;
    }

    /**
     * Retry decision details for debugging
     */
    public static class RetryDecision {
        public final boolean shouldRetry;
        public final String reason;
        public final int attemptCount;
        public final int maxRetries;
        public final long delayMs;

        public RetryDecision(boolean shouldRetry, String reason, int attemptCount,
                           int maxRetries, long delayMs) {
            this.shouldRetry = shouldRetry;
            this.reason = reason;
            this.attemptCount = attemptCount;
            this.maxRetries = maxRetries;
            this.delayMs = delayMs;
        }

        @Override
        public String toString() {
            return String.format("Retry: %s | Reason: %s | Attempt: %d/%d | Delay: %dms",
                shouldRetry ? "YES" : "NO", reason, attemptCount, maxRetries, delayMs);
        }
    }
}
