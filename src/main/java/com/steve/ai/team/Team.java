package com.steve.ai.team;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a team of Steve entities working together
 * Manages team coordination, task assignment, and communication
 */
public class Team {
    private final String teamName;
    private final UUID teamId;
    private final Map<String, SteveEntity> members; // steveName -> entity
    private final Map<String, SteveRole> roleAssignments; // steveName -> role
    private String leaderName;
    private TeamGoal currentGoal;
    private final List<TeamMessage> messageQueue;
    private long creationTime;

    public Team(String teamName) {
        this.teamName = teamName;
        this.teamId = UUID.randomUUID();
        this.members = new ConcurrentHashMap<>();
        this.roleAssignments = new ConcurrentHashMap<>();
        this.messageQueue = new ArrayList<>();
        this.creationTime = System.currentTimeMillis();
        this.leaderName = null;
        this.currentGoal = null;
    }

    /**
     * Add a Steve to this team
     */
    public boolean addMember(SteveEntity steve, SteveRole role) {
        String steveName = steve.getSteveName();

        if (members.containsKey(steveName)) {
            SteveMod.LOGGER.warn("Steve '{}' is already in team '{}'", steveName, teamName);
            return false;
        }

        members.put(steveName, steve);
        roleAssignments.put(steveName, role);

        // If no leader and this is the first member or has LEADER role, make them leader
        if (leaderName == null || role == SteveRole.LEADER) {
            leaderName = steveName;
        }

        SteveMod.LOGGER.info("Steve '{}' joined team '{}' as {}", steveName, teamName, role);
        broadcastMessage(new TeamMessage(
            "SYSTEM",
            steveName + " joined the team as " + role.getDisplayName()
        ));

        return true;
    }

    /**
     * Remove a Steve from this team
     */
    public boolean removeMember(String steveName) {
        if (!members.containsKey(steveName)) {
            return false;
        }

        members.remove(steveName);
        roleAssignments.remove(steveName);

        // If removed member was leader, assign new leader
        if (steveName.equals(leaderName)) {
            assignNewLeader();
        }

        SteveMod.LOGGER.info("Steve '{}' left team '{}'", steveName, teamName);
        broadcastMessage(new TeamMessage(
            "SYSTEM",
            steveName + " left the team"
        ));

        return true;
    }

    /**
     * Assign a role to a team member
     */
    public boolean assignRole(String steveName, SteveRole role) {
        if (!members.containsKey(steveName)) {
            return false;
        }

        SteveRole oldRole = roleAssignments.get(steveName);
        roleAssignments.put(steveName, role);

        // Update leader if role changed to/from LEADER
        if (role == SteveRole.LEADER) {
            leaderName = steveName;
        } else if (oldRole == SteveRole.LEADER && role != SteveRole.LEADER) {
            assignNewLeader();
        }

        SteveMod.LOGGER.info("Steve '{}' role changed from {} to {}", steveName, oldRole, role);
        return true;
    }

    /**
     * Assign a new leader (called when current leader leaves or changes role)
     */
    private void assignNewLeader() {
        // Try to find a member with LEADER role
        for (Map.Entry<String, SteveRole> entry : roleAssignments.entrySet()) {
            if (entry.getValue() == SteveRole.LEADER) {
                leaderName = entry.getKey();
                return;
            }
        }

        // If no leader role, pick first member
        if (!members.isEmpty()) {
            leaderName = members.keySet().iterator().next();
            roleAssignments.put(leaderName, SteveRole.LEADER);
        } else {
            leaderName = null;
        }
    }

    /**
     * Set team goal
     */
    public void setGoal(TeamGoal goal) {
        this.currentGoal = goal;
        SteveMod.LOGGER.info("Team '{}' new goal: {}", teamName, goal.getDescription());
        broadcastMessage(new TeamMessage(
            "SYSTEM",
            "New team goal: " + goal.getDescription()
        ));
    }

    /**
     * Broadcast a message to all team members
     */
    public void broadcastMessage(TeamMessage message) {
        messageQueue.add(message);

        // Keep only last 50 messages
        if (messageQueue.size() > 50) {
            messageQueue.remove(0);
        }
    }

    /**
     * Send a message from one team member to another
     */
    public void sendMessage(String from, String to, String content) {
        TeamMessage message = new TeamMessage(from, content, to);
        messageQueue.add(message);
    }

    /**
     * Get messages for a specific team member
     */
    public List<TeamMessage> getMessagesFor(String steveName) {
        return messageQueue.stream()
            .filter(msg -> msg.isForEveryone() || msg.getRecipient().equals(steveName))
            .toList();
    }

    /**
     * Get recent team messages
     */
    public List<TeamMessage> getRecentMessages(int count) {
        int start = Math.max(0, messageQueue.size() - count);
        return new ArrayList<>(messageQueue.subList(start, messageQueue.size()));
    }

    /**
     * Find best team member for a task
     */
    public SteveEntity findBestMemberForTask(String taskType) {
        if (members.isEmpty()) {
            return null;
        }

        // Find members with roles suitable for this task
        return members.entrySet().stream()
            .max(Comparator.comparingDouble(entry -> {
                SteveRole role = roleAssignments.get(entry.getKey());
                return role.getPriorityMultiplier(taskType);
            }))
            .map(Map.Entry::getValue)
            .orElse(null);
    }

    /**
     * Get all members with a specific role
     */
    public List<SteveEntity> getMembersByRole(SteveRole role) {
        return roleAssignments.entrySet().stream()
            .filter(entry -> entry.getValue() == role)
            .map(entry -> members.get(entry.getKey()))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Get team status summary
     */
    public String getStatusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Team: ").append(teamName).append(" ===\n");
        sb.append("Members: ").append(members.size()).append("\n");
        sb.append("Leader: ").append(leaderName != null ? leaderName : "None").append("\n");

        if (currentGoal != null) {
            sb.append("Goal: ").append(currentGoal.getDescription()).append("\n");
        }

        sb.append("Roles:\n");
        for (Map.Entry<String, SteveRole> entry : roleAssignments.entrySet()) {
            sb.append("  - ").append(entry.getKey()).append(": ")
              .append(entry.getValue().getDisplayName()).append("\n");
        }

        return sb.toString();
    }

    // Getters

    public String getTeamName() {
        return teamName;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public int getMemberCount() {
        return members.size();
    }

    public boolean hasMember(String steveName) {
        return members.containsKey(steveName);
    }

    public SteveEntity getMember(String steveName) {
        return members.get(steveName);
    }

    public Collection<SteveEntity> getAllMembers() {
        return new ArrayList<>(members.values());
    }

    public SteveRole getRole(String steveName) {
        return roleAssignments.getOrDefault(steveName, SteveRole.GENERALIST);
    }

    public String getLeaderName() {
        return leaderName;
    }

    public SteveEntity getLeader() {
        return leaderName != null ? members.get(leaderName) : null;
    }

    public TeamGoal getCurrentGoal() {
        return currentGoal;
    }

    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Team message for communication
     */
    public static class TeamMessage {
        private final long timestamp;
        private final String sender;
        private final String content;
        private final String recipient; // null for broadcast

        public TeamMessage(String sender, String content) {
            this(sender, content, null);
        }

        public TeamMessage(String sender, String content, String recipient) {
            this.timestamp = System.currentTimeMillis();
            this.sender = sender;
            this.content = content;
            this.recipient = recipient;
        }

        public boolean isForEveryone() {
            return recipient == null;
        }

        public long getTimestamp() { return timestamp; }
        public String getSender() { return sender; }
        public String getContent() { return content; }
        public String getRecipient() { return recipient; }

        @Override
        public String toString() {
            if (isForEveryone()) {
                return String.format("[%s] %s", sender, content);
            } else {
                return String.format("[%s -> %s] %s", sender, recipient, content);
            }
        }
    }

    /**
     * Team goal representation
     */
    public static class TeamGoal {
        private final String description;
        private final BlockPos location;
        private final Map<String, Object> metadata;

        public TeamGoal(String description) {
            this(description, null, new HashMap<>());
        }

        public TeamGoal(String description, BlockPos location, Map<String, Object> metadata) {
            this.description = description;
            this.location = location;
            this.metadata = metadata;
        }

        public String getDescription() { return description; }
        public BlockPos getLocation() { return location; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
}
