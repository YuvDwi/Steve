package com.steve.ai.combat;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.team.Team;
import com.steve.ai.team.TeamManager;
import com.steve.ai.team.SteveRole;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Coordinates team-based boss fights
 * Assigns roles and strategies for fighting powerful enemies
 */
public class BossFightCoordinator {
    private final String teamName;
    private final Team team;
    private LivingEntity bossTarget;
    private final Map<String, CombatRole> assignedRoles;

    public enum CombatRole {
        TANK,       // Draws aggro, uses shield
        DPS_MELEE,  // Close-range damage
        DPS_RANGED, // Bow attacks from distance
        SUPPORT     // Healing, backup
    }

    public BossFightCoordinator(String teamName) {
        this.teamName = teamName;
        this.team = TeamManager.getInstance().getTeam(teamName);
        this.assignedRoles = new HashMap<>();

        if (team == null) {
            SteveMod.LOGGER.error("Boss fight coordinator created for non-existent team: {}", teamName);
        }
    }

    /**
     * Initiate a boss fight with team coordination
     */
    public boolean initiateBossFight(LivingEntity boss) {
        if (team == null || team.getAllMembers().isEmpty()) {
            SteveMod.LOGGER.error("Cannot initiate boss fight: team is null or empty");
            return false;
        }

        this.bossTarget = boss;

        // Assign combat roles based on team size
        assignCombatRoles();

        // Notify team
        team.broadcastMessage(new Team.TeamMessage(
            "Coordinator",
            "Boss fight initiated! Target: " + boss.getType().toString()
        ));

        SteveMod.LOGGER.info("Team '{}' initiating boss fight against {}",
            teamName, boss.getType().toString());

        return true;
    }

    /**
     * Assign combat roles to team members
     */
    private void assignCombatRoles() {
        if (team == null) return;

        Map<String, SteveEntity> members = team.getAllMembers();
        List<String> memberNames = new ArrayList<>(members.keySet());

        // Clear previous assignments
        assignedRoles.clear();

        if (memberNames.isEmpty()) {
            return;
        }

        // Assign roles based on team size
        int teamSize = memberNames.size();

        if (teamSize == 1) {
            // Solo: versatile fighter
            assignedRoles.put(memberNames.get(0), CombatRole.DPS_MELEE);
        } else if (teamSize == 2) {
            // Duo: tank + DPS
            assignedRoles.put(memberNames.get(0), CombatRole.TANK);
            assignedRoles.put(memberNames.get(1), CombatRole.DPS_RANGED);
        } else if (teamSize == 3) {
            // Trio: tank + melee DPS + ranged DPS
            assignedRoles.put(memberNames.get(0), CombatRole.TANK);
            assignedRoles.put(memberNames.get(1), CombatRole.DPS_MELEE);
            assignedRoles.put(memberNames.get(2), CombatRole.DPS_RANGED);
        } else {
            // 4+: tank + DPS + support
            assignedRoles.put(memberNames.get(0), CombatRole.TANK);
            for (int i = 1; i < teamSize - 1; i++) {
                // Alternate melee/ranged DPS
                CombatRole role = (i % 2 == 0) ? CombatRole.DPS_MELEE : CombatRole.DPS_RANGED;
                assignedRoles.put(memberNames.get(i), role);
            }
            assignedRoles.put(memberNames.get(teamSize - 1), CombatRole.SUPPORT);
        }

        // Log assignments
        assignedRoles.forEach((name, role) ->
            SteveMod.LOGGER.info("Team '{}' - {} assigned role: {}",
                teamName, name, role)
        );
    }

    /**
     * Get combat role for a team member
     */
    public CombatRole getCombatRole(String steveName) {
        return assignedRoles.getOrDefault(steveName, CombatRole.DPS_MELEE);
    }

    /**
     * Get recommended action for team member based on role
     */
    public String getRecommendedAction(String steveName) {
        CombatRole role = getCombatRole(steveName);

        return switch (role) {
            case TANK -> "attack_melee_shield"; // Melee with shield blocking
            case DPS_MELEE -> "attack_melee"; // Aggressive melee
            case DPS_RANGED -> "attack_ranged"; // Bow attacks
            case SUPPORT -> "support"; // Stay back, assist if needed
        };
    }

    /**
     * Check if entity is a boss
     */
    public static boolean isBoss(LivingEntity entity) {
        return entity instanceof EnderDragon ||
               entity instanceof WitherBoss ||
               entity instanceof Raider; // Raid captains
    }

    /**
     * Find nearest boss entity
     */
    public static LivingEntity findNearestBoss(SteveEntity steve, double range) {
        AABB searchBox = steve.getBoundingBox().inflate(range);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        LivingEntity nearestBoss = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && isBoss(living)) {
                double distance = steve.distanceTo(living);
                if (distance < nearestDistance) {
                    nearestBoss = living;
                    nearestDistance = distance;
                }
            }
        }

        return nearestBoss;
    }

    /**
     * Get boss fight status summary
     */
    public String getStatusSummary() {
        if (bossTarget == null) {
            return "No active boss fight";
        }

        if (!bossTarget.isAlive()) {
            return "Boss defeated!";
        }

        float healthPercent = (bossTarget.getHealth() / bossTarget.getMaxHealth()) * 100;

        return String.format("Boss: %s | Health: %.0f%% | Team: %d members",
            bossTarget.getType().toString(),
            healthPercent,
            team != null ? team.getAllMembers().size() : 0);
    }

    /**
     * Check if boss fight is still active
     */
    public boolean isActive() {
        return bossTarget != null && bossTarget.isAlive();
    }

    /**
     * Get current boss target
     */
    public LivingEntity getBossTarget() {
        return bossTarget;
    }
}
