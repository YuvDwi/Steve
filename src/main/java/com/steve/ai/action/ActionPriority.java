package com.steve.ai.action;

/**
 * Action priority levels for scheduling and interruption
 * Lower priority value = higher urgency
 */
public enum ActionPriority {
    /**
     * CRITICAL: Immediate response required (combat, danger avoidance)
     * Can interrupt any other action
     */
    CRITICAL(0),

    /**
     * HIGH: User commands and important goals
     * Can interrupt NORMAL, LOW, and BACKGROUND actions
     */
    HIGH(1),

    /**
     * NORMAL: Standard autonomous tasks
     * Can interrupt LOW and BACKGROUND actions
     */
    NORMAL(2),

    /**
     * LOW: Optional tasks and idle behavior
     * Can interrupt BACKGROUND actions only
     */
    LOW(3),

    /**
     * BACKGROUND: Passive monitoring and observation
     * Cannot interrupt other actions
     */
    BACKGROUND(4);

    private final int level;

    ActionPriority(int level) {
        this.level = level;
    }

    /**
     * Get priority level (0 = highest priority)
     */
    public int getLevel() {
        return level;
    }

    /**
     * Check if this priority can interrupt another action
     * @param other The priority of the action to potentially interrupt
     * @return True if this priority can interrupt the other
     */
    public boolean canInterrupt(ActionPriority other) {
        return this.level < other.level;
    }

    /**
     * Check if this priority is higher than another
     * @param other The priority to compare against
     * @return True if this priority is higher
     */
    public boolean isHigherThan(ActionPriority other) {
        return this.level < other.level;
    }

    /**
     * Check if this priority is lower than another
     * @param other The priority to compare against
     * @return True if this priority is lower
     */
    public boolean isLowerThan(ActionPriority other) {
        return this.level > other.level;
    }

    /**
     * Check if two priorities are equal
     * @param other The priority to compare against
     * @return True if priorities are equal
     */
    public boolean isEqualTo(ActionPriority other) {
        return this.level == other.level;
    }

    @Override
    public String toString() {
        return name() + "(level=" + level + ")";
    }
}
