package com.steve.ai.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a quest that Steve can complete
 * Tracks objectives, rewards, and completion status
 */
public class Quest {
    private final String id;
    private final String title;
    private final String description;
    private final List<QuestObjective> objectives;
    private final Map<String, Integer> rewards; // Item -> quantity
    private QuestStatus status;
    private long startTime;
    private long completionTime;

    public Quest(String id, String title, String description) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.objectives = new ArrayList<>();
        this.rewards = new HashMap<>();
        this.status = QuestStatus.NOT_STARTED;
        this.startTime = 0;
        this.completionTime = 0;
    }

    /**
     * Add an objective to this quest
     */
    public void addObjective(QuestObjective objective) {
        objectives.add(objective);
    }

    /**
     * Add a reward for completing this quest
     */
    public void addReward(String item, int quantity) {
        rewards.put(item, rewards.getOrDefault(item, 0) + quantity);
    }

    /**
     * Start this quest
     */
    public void start() {
        if (status == QuestStatus.NOT_STARTED) {
            status = QuestStatus.IN_PROGRESS;
            startTime = System.currentTimeMillis();
        }
    }

    /**
     * Update quest progress for a specific objective type
     */
    public void updateProgress(String objectiveType, int amount) {
        if (status != QuestStatus.IN_PROGRESS) {
            return;
        }

        for (QuestObjective objective : objectives) {
            if (objective.getType().equals(objectiveType)) {
                objective.incrementProgress(amount);
            }
        }

        // Check if all objectives complete
        if (isAllObjectivesComplete()) {
            complete();
        }
    }

    /**
     * Check if all objectives are complete
     */
    private boolean isAllObjectivesComplete() {
        for (QuestObjective objective : objectives) {
            if (!objective.isComplete()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Mark quest as complete
     */
    private void complete() {
        status = QuestStatus.COMPLETED;
        completionTime = System.currentTimeMillis();
    }

    /**
     * Fail this quest
     */
    public void fail() {
        if (status == QuestStatus.IN_PROGRESS) {
            status = QuestStatus.FAILED;
        }
    }

    /**
     * Get quest completion percentage (0-100)
     */
    public int getCompletionPercentage() {
        if (objectives.isEmpty()) {
            return 0;
        }

        int totalProgress = 0;
        for (QuestObjective objective : objectives) {
            totalProgress += objective.getProgressPercentage();
        }

        return totalProgress / objectives.size();
    }

    /**
     * Get time spent on quest in seconds
     */
    public long getTimeSpentSeconds() {
        if (startTime == 0) {
            return 0;
        }

        long endTime = completionTime != 0 ? completionTime : System.currentTimeMillis();
        return (endTime - startTime) / 1000;
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

    public List<QuestObjective> getObjectives() {
        return objectives;
    }

    public Map<String, Integer> getRewards() {
        return rewards;
    }

    public QuestStatus getStatus() {
        return status;
    }

    public boolean isComplete() {
        return status == QuestStatus.COMPLETED;
    }

    public boolean isInProgress() {
        return status == QuestStatus.IN_PROGRESS;
    }

    /**
     * Get summary string for GUI display
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append(" (").append(status).append(")\n");
        sb.append(description).append("\n");
        sb.append("Progress: ").append(getCompletionPercentage()).append("%\n");

        if (!objectives.isEmpty()) {
            sb.append("Objectives:\n");
            for (QuestObjective obj : objectives) {
                sb.append("  - ").append(obj.getDescription())
                    .append(": ").append(obj.getCurrentProgress())
                    .append("/").append(obj.getTargetProgress())
                    .append(obj.isComplete() ? " ✓" : "")
                    .append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Quest status enum
     */
    public enum QuestStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    /**
     * Quest objective - something that must be accomplished
     */
    public static class QuestObjective {
        private final String type; // e.g., "mine_iron", "craft_sword", "kill_zombie"
        private final String description;
        private final int targetProgress;
        private int currentProgress;

        public QuestObjective(String type, String description, int targetProgress) {
            this.type = type;
            this.description = description;
            this.targetProgress = targetProgress;
            this.currentProgress = 0;
        }

        public void incrementProgress(int amount) {
            currentProgress = Math.min(currentProgress + amount, targetProgress);
        }

        public boolean isComplete() {
            return currentProgress >= targetProgress;
        }

        public int getProgressPercentage() {
            return (currentProgress * 100) / targetProgress;
        }

        // Getters
        public String getType() {
            return type;
        }

        public String getDescription() {
            return description;
        }

        public int getTargetProgress() {
            return targetProgress;
        }

        public int getCurrentProgress() {
            return currentProgress;
        }

        public void setCurrentProgress(int progress) {
            this.currentProgress = Math.min(progress, targetProgress);
        }
    }
}
