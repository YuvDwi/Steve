package com.steve.ai.team;

/**
 * Roles that Steve entities can take on for team coordination
 * Each role has specific responsibilities and strengths
 */
public enum SteveRole {
    /**
     * MINER: Specializes in resource gathering
     * - Mines ores and minerals
     * - Focuses on underground exploration
     * - Efficient at tool usage
     */
    MINER("Miner", "Resource gathering and mining operations"),

    /**
     * BUILDER: Specializes in construction
     * - Builds structures
     * - Places blocks efficiently
     * - Manages building materials
     */
    BUILDER("Builder", "Construction and building projects"),

    /**
     * FIGHTER: Specializes in combat
     * - Engages hostile mobs
     * - Protects team members
     * - Patrols dangerous areas
     */
    FIGHTER("Fighter", "Combat and protection"),

    /**
     * HAULER: Specializes in logistics
     * - Transports items between locations
     * - Manages inventory and storage
     * - Organizes chest systems
     */
    HAULER("Hauler", "Logistics and item transport"),

    /**
     * SCOUT: Specializes in exploration
     * - Explores new areas
     * - Locates resources and structures
     * - Reports findings to team
     */
    SCOUT("Scout", "Exploration and reconnaissance"),

    /**
     * LEADER: Coordinates team activities
     * - Assigns tasks to team members
     * - Makes strategic decisions
     * - Monitors team progress
     */
    LEADER("Leader", "Team coordination and strategy"),

    /**
     * GENERALIST: No specific specialization
     * - Can perform any task
     * - Default role for unassigned Steves
     * - Flexible task assignment
     */
    GENERALIST("Generalist", "General purpose tasks");

    private final String displayName;
    private final String description;

    SteveRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Check if this role is suitable for a given task type
     */
    public boolean isSuitableFor(String taskType) {
        return switch (this) {
            case MINER -> taskType.equals("mine") || taskType.equals("gather");
            case BUILDER -> taskType.equals("build") || taskType.equals("place");
            case FIGHTER -> taskType.equals("attack") || taskType.equals("combat");
            case HAULER -> taskType.equals("store") || taskType.equals("retrieve") ||
                          taskType.equals("place_chest");
            case SCOUT -> taskType.equals("pathfind") || taskType.equals("explore");
            case LEADER -> true; // Leaders can coordinate any task
            case GENERALIST -> true; // Generalists can do anything
        };
    }

    /**
     * Get priority multiplier for this role with a given task
     * Higher values mean more suitable
     */
    public double getPriorityMultiplier(String taskType) {
        if (!isSuitableFor(taskType)) {
            return 0.5; // Not ideal but can still do it
        }

        return switch (this) {
            case MINER -> taskType.equals("mine") ? 2.0 : 1.0;
            case BUILDER -> taskType.equals("build") ? 2.0 : 1.0;
            case FIGHTER -> taskType.equals("attack") ? 2.0 : 1.0;
            case HAULER -> (taskType.equals("store") || taskType.equals("retrieve")) ? 2.0 : 1.0;
            case SCOUT -> taskType.equals("pathfind") ? 2.0 : 1.0;
            case LEADER -> 1.5; // Leaders are good at everything
            case GENERALIST -> 1.0; // Standard priority
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
