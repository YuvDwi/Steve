package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Tactical retreat action for when combat is unfavorable
 * Intelligently finds safe locations and retreats while monitoring threats
 */
public class TacticalRetreatAction extends BaseAction {
    private int ticksRunning;
    private BlockPos retreatTarget;
    private static final int MAX_TICKS = 400; // 20 seconds
    private static final double SAFE_DISTANCE = 32.0;
    private static final int REEVAL_INTERVAL = 40; // Re-evaluate every 2 seconds

    public TacticalRetreatAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        ticksRunning = 0;
        steve.setFlying(false);
        steve.setSprinting(true);

        // Find retreat location
        retreatTarget = findSafeRetreatLocation();

        if (retreatTarget == null) {
            SteveMod.LOGGER.warn("Steve '{}' couldn't find safe retreat location", steve.getSteveName());
            retreatTarget = steve.blockPosition().offset(16, 0, 16); // Fallback: move away
        }

        SteveMod.LOGGER.info("Steve '{}' retreating to {}",
            steve.getSteveName(), retreatTarget);
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.setSprinting(false);
            result = ActionResult.success("Retreat complete - reached safe distance");
            return;
        }

        // Re-evaluate retreat path periodically
        if (ticksRunning % REEVAL_INTERVAL == 0) {
            if (isSafeLocation()) {
                steve.setSprinting(false);
                result = ActionResult.success("Reached safe location");
                return;
            }

            // Check if still being pursued
            if (getNearestThreatDistance() > SAFE_DISTANCE) {
                steve.setSprinting(false);
                result = ActionResult.success("Threats cleared - safe distance reached");
                return;
            }

            // Update retreat target if needed
            BlockPos newTarget = findSafeRetreatLocation();
            if (newTarget != null && !newTarget.equals(retreatTarget)) {
                retreatTarget = newTarget;
                SteveMod.LOGGER.debug("Steve '{}' updating retreat path to {}",
                    steve.getSteveName(), retreatTarget);
            }
        }

        // Navigate to retreat location
        if (retreatTarget != null) {
            steve.getNavigation().moveTo(
                retreatTarget.getX() + 0.5,
                retreatTarget.getY(),
                retreatTarget.getZ() + 0.5,
                2.0 // Fast movement
            );
        }
    }

    @Override
    protected void onCancel() {
        steve.setSprinting(false);
        steve.getNavigation().stop();
        SteveMod.LOGGER.info("Steve '{}' retreat cancelled", steve.getSteveName());
    }

    @Override
    public String getDescription() {
        return "Tactical retreat";
    }

    /**
     * Find safe retreat location away from threats
     */
    private BlockPos findSafeRetreatLocation() {
        BlockPos currentPos = steve.blockPosition();
        List<LivingEntity> threats = getNearbyThreats(64.0);

        if (threats.isEmpty()) {
            // No threats nearby, just move away from current position
            return currentPos.offset(20, 0, 20);
        }

        // Calculate center of threats
        double threatCenterX = 0;
        double threatCenterZ = 0;
        for (LivingEntity threat : threats) {
            threatCenterX += threat.getX();
            threatCenterZ += threat.getZ();
        }
        threatCenterX /= threats.size();
        threatCenterZ /= threats.size();

        // Move in opposite direction
        double dx = steve.getX() - threatCenterX;
        double dz = steve.getZ() - threatCenterZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < 0.1) {
            // Avoid division by zero
            dx = 1.0;
            dz = 0.0;
            distance = 1.0;
        }

        // Calculate retreat position (32 blocks away from threats)
        double retreatDistance = SAFE_DISTANCE;
        int targetX = (int)(steve.getX() + (dx / distance) * retreatDistance);
        int targetZ = (int)(steve.getZ() + (dz / distance) * retreatDistance);

        // Find valid ground level
        BlockPos retreatPos = new BlockPos(targetX, steve.getBlockY(), targetZ);
        retreatPos = findGroundLevel(retreatPos);

        return retreatPos;
    }

    /**
     * Find ground level at position
     */
    private BlockPos findGroundLevel(BlockPos pos) {
        // Search down for ground
        for (int y = pos.getY(); y > pos.getY() - 10 && y > -64; y--) {
            BlockPos checkPos = new BlockPos(pos.getX(), y, pos.getZ());
            if (steve.level().getBlockState(checkPos).isSolid()) {
                return checkPos.above();
            }
        }

        // Search up for ground
        for (int y = pos.getY(); y < pos.getY() + 10 && y < 320; y++) {
            BlockPos checkPos = new BlockPos(pos.getX(), y, pos.getZ());
            if (steve.level().getBlockState(checkPos).isSolid()) {
                return checkPos.above();
            }
        }

        return pos; // Fallback to original position
    }

    /**
     * Get list of nearby hostile entities
     */
    private List<LivingEntity> getNearbyThreats(double range) {
        AABB searchBox = steve.getBoundingBox().inflate(range);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        return entities.stream()
            .filter(e -> e instanceof LivingEntity)
            .map(e -> (LivingEntity)e)
            .filter(this::isThreat)
            .toList();
    }

    /**
     * Check if entity is a threat
     */
    private boolean isThreat(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) {
            return false;
        }

        // Don't consider other Steves or players as threats
        if (entity instanceof SteveEntity || entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }

        // Hostile mobs are threats
        return entity instanceof Monster;
    }

    /**
     * Get distance to nearest threat
     */
    private double getNearestThreatDistance() {
        List<LivingEntity> threats = getNearbyThreats(64.0);

        if (threats.isEmpty()) {
            return Double.MAX_VALUE;
        }

        return threats.stream()
            .mapToDouble(steve::distanceTo)
            .min()
            .orElse(Double.MAX_VALUE);
    }

    /**
     * Check if current location is safe
     */
    private boolean isSafeLocation() {
        // Safe if no threats within safe distance
        return getNearestThreatDistance() > SAFE_DISTANCE;
    }

    /**
     * Get threat level (number of nearby enemies)
     */
    public int getThreatLevel() {
        return getNearbyThreats(32.0).size();
    }

    /**
     * Check if retreat is necessary based on health and threat level
     */
    public static boolean shouldRetreat(SteveEntity steve) {
        float health = steve.getHealth();
        float maxHealth = steve.getMaxHealth();
        float healthPercent = (health / maxHealth) * 100;

        // Retreat if health below 40%
        if (healthPercent < 40) {
            return true;
        }

        // Retreat if outnumbered (more than 3 enemies nearby)
        AABB searchBox = steve.getBoundingBox().inflate(16.0);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);
        long threatCount = entities.stream()
            .filter(e -> e instanceof Monster)
            .filter(e -> ((LivingEntity)e).isAlive())
            .count();

        return threatCount > 3;
    }
}
