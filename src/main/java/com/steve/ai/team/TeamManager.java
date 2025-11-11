package com.steve.ai.team;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Global manager for all Steve teams
 * Handles team creation, coordination, and task distribution
 */
public class TeamManager {
    private static TeamManager INSTANCE;

    private final Map<String, Team> teams; // teamName -> Team
    private final Map<String, String> memberTeams; // steveName -> teamName
    private final Map<UUID, Team> teamsByUuid; // teamId -> Team

    private TeamManager() {
        this.teams = new ConcurrentHashMap<>();
        this.memberTeams = new ConcurrentHashMap<>();
        this.teamsByUuid = new ConcurrentHashMap<>();
    }

    /**
     * Get singleton instance
     */
    public static synchronized TeamManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TeamManager();
        }
        return INSTANCE;
    }

    /**
     * Create a new team
     */
    public Team createTeam(String teamName) {
        if (teams.containsKey(teamName)) {
            SteveMod.LOGGER.warn("Team '{}' already exists", teamName);
            return teams.get(teamName);
        }

        Team team = new Team(teamName);
        teams.put(teamName, team);
        teamsByUuid.put(team.getTeamId(), team);

        SteveMod.LOGGER.info("Created team: {}", teamName);
        return team;
    }

    /**
     * Delete a team
     */
    public boolean deleteTeam(String teamName) {
        Team team = teams.get(teamName);
        if (team == null) {
            return false;
        }

        // Remove all members from team mapping
        for (SteveEntity member : team.getAllMembers()) {
            memberTeams.remove(member.getSteveName());
        }

        teams.remove(teamName);
        teamsByUuid.remove(team.getTeamId());

        SteveMod.LOGGER.info("Deleted team: {}", teamName);
        return true;
    }

    /**
     * Add a Steve to a team with a specific role
     */
    public boolean addToTeam(SteveEntity steve, String teamName, SteveRole role) {
        Team team = teams.get(teamName);
        if (team == null) {
            SteveMod.LOGGER.error("Team '{}' does not exist", teamName);
            return false;
        }

        String steveName = steve.getSteveName();

        // Remove from previous team if exists
        String previousTeam = memberTeams.get(steveName);
        if (previousTeam != null) {
            Team oldTeam = teams.get(previousTeam);
            if (oldTeam != null) {
                oldTeam.removeMember(steveName);
            }
        }

        // Add to new team
        boolean added = team.addMember(steve, role);
        if (added) {
            memberTeams.put(steveName, teamName);
        }

        return added;
    }

    /**
     * Remove a Steve from their current team
     */
    public boolean removeFromTeam(String steveName) {
        String teamName = memberTeams.get(steveName);
        if (teamName == null) {
            return false;
        }

        Team team = teams.get(teamName);
        if (team == null) {
            return false;
        }

        boolean removed = team.removeMember(steveName);
        if (removed) {
            memberTeams.remove(steveName);
        }

        return removed;
    }

    /**
     * Assign a role to a Steve
     */
    public boolean assignRole(String steveName, SteveRole role) {
        String teamName = memberTeams.get(steveName);
        if (teamName == null) {
            SteveMod.LOGGER.warn("Steve '{}' is not in any team", steveName);
            return false;
        }

        Team team = teams.get(teamName);
        if (team == null) {
            return false;
        }

        return team.assignRole(steveName, role);
    }

    /**
     * Get a Steve's team
     */
    public Team getTeam(String steveName) {
        String teamName = memberTeams.get(steveName);
        return teamName != null ? teams.get(teamName) : null;
    }

    /**
     * Get a team by name
     */
    public Team getTeamByName(String teamName) {
        return teams.get(teamName);
    }

    /**
     * Get a team by UUID
     */
    public Team getTeamById(UUID teamId) {
        return teamsByUuid.get(teamId);
    }

    /**
     * Get a Steve's role
     */
    public SteveRole getRole(String steveName) {
        Team team = getTeam(steveName);
        return team != null ? team.getRole(steveName) : SteveRole.GENERALIST;
    }

    /**
     * Check if a Steve is in a team
     */
    public boolean isInTeam(String steveName) {
        return memberTeams.containsKey(steveName);
    }

    /**
     * Get all teams
     */
    public Collection<Team> getAllTeams() {
        return new ArrayList<>(teams.values());
    }

    /**
     * Get all team names
     */
    public Set<String> getAllTeamNames() {
        return new HashSet<>(teams.keySet());
    }

    /**
     * Coordinate a team task
     * Distributes subtasks to appropriate team members based on roles
     */
    public void coordinateTeamTask(String teamName, String taskDescription, List<Task> subtasks) {
        Team team = teams.get(teamName);
        if (team == null) {
            SteveMod.LOGGER.error("Cannot coordinate task for non-existent team: {}", teamName);
            return;
        }

        if (team.getMemberCount() == 0) {
            SteveMod.LOGGER.warn("Cannot coordinate task for team '{}': no members", teamName);
            return;
        }

        SteveMod.LOGGER.info("Team '{}' coordinating task: {} ({} subtasks)",
            teamName, taskDescription, subtasks.size());

        // Set team goal
        team.setGoal(new Team.TeamGoal(taskDescription));

        // Assign subtasks to best suited members
        for (Task task : subtasks) {
            SteveEntity bestMember = team.findBestMemberForTask(task.getAction());

            if (bestMember != null) {
                SteveMod.LOGGER.info("Assigning {} task to {} (role: {})",
                    task.getAction(),
                    bestMember.getSteveName(),
                    team.getRole(bestMember.getSteveName())
                );

                // Send task to member's executor
                // This would need to be implemented based on how tasks are dispatched
                team.broadcastMessage(new Team.TeamMessage(
                    "COORDINATOR",
                    "Task assigned to " + bestMember.getSteveName() + ": " + task.getAction()
                ));
            } else {
                SteveMod.LOGGER.warn("No suitable team member found for task: {}", task.getAction());
            }
        }
    }

    /**
     * Create a mining team (MINER + HAULER)
     */
    public Team createMiningTeam(String teamName, SteveEntity miner, SteveEntity hauler) {
        Team team = createTeam(teamName);
        addToTeam(miner, teamName, SteveRole.MINER);
        addToTeam(hauler, teamName, SteveRole.HAULER);

        SteveMod.LOGGER.info("Created mining team '{}' with miner {} and hauler {}",
            teamName, miner.getSteveName(), hauler.getSteveName());

        return team;
    }

    /**
     * Create a combat team (FIGHTER + FIGHTER)
     */
    public Team createCombatTeam(String teamName, SteveEntity tank, SteveEntity dps) {
        Team team = createTeam(teamName);
        addToTeam(tank, teamName, SteveRole.FIGHTER);
        addToTeam(dps, teamName, SteveRole.FIGHTER);

        SteveMod.LOGGER.info("Created combat team '{}' with fighters {} and {}",
            teamName, tank.getSteveName(), dps.getSteveName());

        return team;
    }

    /**
     * Create a building team (multiple BUILDERS + HAULER)
     */
    public Team createBuildingTeam(String teamName, List<SteveEntity> builders, SteveEntity hauler) {
        Team team = createTeam(teamName);

        for (SteveEntity builder : builders) {
            addToTeam(builder, teamName, SteveRole.BUILDER);
        }

        if (hauler != null) {
            addToTeam(hauler, teamName, SteveRole.HAULER);
        }

        SteveMod.LOGGER.info("Created building team '{}' with {} builders",
            teamName, builders.size());

        return team;
    }

    /**
     * Broadcast message to all teams
     */
    public void broadcastToAll(String message) {
        Team.TeamMessage teamMessage = new Team.TeamMessage("SYSTEM", message);
        for (Team team : teams.values()) {
            team.broadcastMessage(teamMessage);
        }
    }

    /**
     * Get global status summary of all teams
     */
    public String getGlobalStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== TEAM MANAGER STATUS ===\n");
        sb.append("Total Teams: ").append(teams.size()).append("\n");
        sb.append("Total Team Members: ").append(memberTeams.size()).append("\n\n");

        if (teams.isEmpty()) {
            sb.append("No teams exist.\n");
        } else {
            for (Team team : teams.values()) {
                sb.append(team.getStatusSummary()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Clear all teams (for debugging/reset)
     */
    public void clearAll() {
        teams.clear();
        memberTeams.clear();
        teamsByUuid.clear();
        SteveMod.LOGGER.info("Cleared all teams");
    }
}
