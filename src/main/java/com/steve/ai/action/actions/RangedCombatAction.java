package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.combat.CombatEquipmentManager;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ranged combat action using bow and arrows
 * Maintains distance from enemies while shooting
 */
public class RangedCombatAction extends BaseAction {
    private String targetType;
    private LivingEntity target;
    private final CombatEquipmentManager equipmentManager;
    private int ticksRunning;
    private int ticksSinceLastShot;
    private static final int MAX_TICKS = 1200; // 60 seconds
    private static final double OPTIMAL_RANGE = 15.0; // Optimal shooting distance
    private static final double MIN_RANGE = 8.0; // Minimum safe distance
    private static final double MAX_RANGE = 32.0; // Maximum targeting range
    private static final int SHOT_COOLDOWN = 20; // 1 second between shots

    public RangedCombatAction(SteveEntity steve, Task task) {
        super(steve, task);
        this.equipmentManager = new CombatEquipmentManager(steve);
    }

    @Override
    protected void onStart() {
        targetType = task.getStringParameter("target", "hostile");
        ticksRunning = 0;
        ticksSinceLastShot = 0;

        // Ensure not flying
        steve.setFlying(false);
        steve.setInvulnerableBuilding(true);

        // Auto-equip bow and arrows
        if (!equipmentManager.hasBowEquipped() || !equipmentManager.hasArrows()) {
            boolean equipped = equipmentManager.equipBowAndArrows();
            if (!equipped) {
                result = ActionResult.failure("No bow or arrows available");
                return;
            }
        }

        // Find initial target
        findTarget();

        if (target == null) {
            SteveMod.LOGGER.warn("Steve '{}' no targets nearby for ranged combat", steve.getSteveName());
        } else {
            SteveMod.LOGGER.info("Steve '{}' starting ranged combat against {}",
                steve.getSteveName(), target.getType().toString());
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastShot++;

        if (ticksRunning > MAX_TICKS) {
            steve.setInvulnerableBuilding(false);
            steve.getNavigation().stop();
            result = ActionResult.success("Ranged combat complete");
            return;
        }

        // Check if still have arrows
        if (!equipmentManager.hasArrows()) {
            steve.setInvulnerableBuilding(false);
            result = ActionResult.failure("Out of arrows");
            return;
        }

        // Re-search for targets periodically or if current target is invalid
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (ticksRunning % 40 == 0) {
                findTarget();
            }
            if (target == null) {
                return; // Keep searching
            }
        }

        double distance = steve.distanceTo(target);

        // Maintain optimal range
        if (distance < MIN_RANGE) {
            // Too close - back away
            retreatFromTarget();
        } else if (distance > OPTIMAL_RANGE + 5) {
            // Too far - move closer
            steve.getNavigation().moveTo(target, 1.0);
        } else {
            // Good range - stop and shoot
            steve.getNavigation().stop();

            // Face target
            steve.getLookControl().setLookAt(target, 30.0F, 30.0F);

            // Shoot if cooldown elapsed
            if (ticksSinceLastShot >= SHOT_COOLDOWN) {
                shootArrow();
                ticksSinceLastShot = 0;
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.setInvulnerableBuilding(false);
        steve.getNavigation().stop();
        target = null;
        SteveMod.LOGGER.info("Steve '{}' ranged combat cancelled", steve.getSteveName());
    }

    @Override
    public String getDescription() {
        return "Ranged attack " + targetType;
    }

    /**
     * Shoot arrow at target
     */
    private void shootArrow() {
        if (target == null || !target.isAlive()) {
            return;
        }

        // Get bow
        ItemStack bow = steve.getMainHandItem();
        if (!(bow.getItem() instanceof BowItem)) {
            return;
        }

        // Create arrow entity
        Arrow arrow = new Arrow(steve.level(), steve);

        // Calculate trajectory to target
        double dx = target.getX() - steve.getX();
        double dy = target.getY() + target.getEyeHeight() - steve.getY() - steve.getEyeHeight();
        double dz = target.getZ() - steve.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Add arc for distance
        double arcAdjustment = distance * 0.05;
        dy += arcAdjustment;

        // Normalize and set arrow velocity
        double speed = 3.0; // Arrow speed
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

        arrow.shoot(dx / length * speed, dy / length * speed, dz / length * speed, (float)speed, 1.0F);

        // Set arrow properties
        arrow.setBaseDamage(2.0);
        arrow.setOwner(steve);
        arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;

        // Spawn arrow
        steve.level().addFreshEntity(arrow);

        // Play animation
        steve.swing(InteractionHand.MAIN_HAND, true);

        SteveMod.LOGGER.debug("Steve '{}' shot arrow at {} (distance: {}m)",
            steve.getSteveName(), target.getType().toString(), (int)steve.distanceTo(target));
    }

    /**
     * Retreat from target to maintain safe distance
     */
    private void retreatFromTarget() {
        if (target == null) {
            return;
        }

        // Calculate retreat vector (opposite direction from target)
        double dx = steve.getX() - target.getX();
        double dz = steve.getZ() - target.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < 0.1) {
            // Avoid division by zero
            dx = 1.0;
            dz = 0.0;
            distance = 1.0;
        }

        // Normalize and scale to retreat distance
        double retreatDistance = 3.0;
        double targetX = steve.getX() + (dx / distance) * retreatDistance;
        double targetZ = steve.getZ() + (dz / distance) * retreatDistance;

        // Move to retreat position
        steve.getNavigation().moveTo(targetX, steve.getY(), targetZ, 1.5);

        SteveMod.LOGGER.debug("Steve '{}' retreating from target (distance: {}m)",
            steve.getSteveName(), (int)distance);
    }

    /**
     * Find nearest valid target
     */
    private void findTarget() {
        AABB searchBox = steve.getBoundingBox().inflate(MAX_RANGE);
        List<Entity> entities = steve.level().getEntities(steve, searchBox);

        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && isValidTarget(living)) {
                double distance = steve.distanceTo(living);
                if (distance < nearestDistance) {
                    nearest = living;
                    nearestDistance = distance;
                }
            }
        }

        target = nearest;
        if (target != null) {
            SteveMod.LOGGER.info("Steve '{}' locked onto: {} at {}m",
                steve.getSteveName(), target.getType().toString(), (int)nearestDistance);
        }
    }

    /**
     * Check if entity is a valid target
     */
    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) {
            return false;
        }

        // Don't attack other Steves or players
        if (entity instanceof SteveEntity || entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }

        String targetLower = targetType.toLowerCase();

        // Match ANY hostile mob
        if (targetLower.contains("mob") || targetLower.contains("hostile") ||
            targetLower.contains("monster") || targetLower.equals("any")) {
            return entity instanceof Monster;
        }

        // Match specific entity type
        String entityTypeName = entity.getType().toString().toLowerCase();
        return entityTypeName.contains(targetLower);
    }
}
