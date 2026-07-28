package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class CombatAction extends BaseAction {
    private String targetType;
    private LivingEntity target;
    private int ticksRunning;
    private int ticksStuck;
    private double lastX, lastZ;
    private boolean everHadTarget;
    private boolean hasAttacked;
    private static final int MAX_TICKS = 600;
    private static final int NO_TARGET_GIVEUP_TICKS = 100; // ~5s of finding nothing before giving up
    private static final double ATTACK_RANGE = 3.5;

    public CombatAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        targetType = task.getStringParameter("target");
        ticksRunning = 0;
        ticksStuck = 0;
        
        // Make sure we're not flying (in case we were building)
        steve.setFlying(false);
        
        steve.setInvulnerableBuilding(true);
        
        findTarget();
        
        if (target == null) {
            com.steve.ai.SteveMod.LOGGER.warn("Steve '{}' no targets nearby", steve.getSteveName());
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            cleanupCombat();
            // Report honestly: only a success if we actually hit something
            result = hasAttacked
                ? ActionResult.success("Combat complete")
                : ActionResult.failure("Couldn't reach any " + targetType + " to attack");
            return;
        }

        // Re-search for targets periodically or if current target is invalid
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (ticksRunning % 20 == 0) {
                findTarget();
            }
            if (target == null) {
                // Give up quickly (with feedback) if there's simply nothing of this type around,
                // instead of standing still for the full 30-second timeout.
                if (!everHadTarget && ticksRunning > NO_TARGET_GIVEUP_TICKS) {
                    cleanupCombat();
                    result = ActionResult.failure("No " + targetType + " nearby to attack");
                }
                return; // Keep searching
            }
        }
        
        double distance = steve.distanceTo(target);
        
        steve.setSprinting(true);
        steve.getNavigation().moveTo(target, 2.5); // High speed multiplier for sprinting
        
        double currentX = steve.getX();
        double currentZ = steve.getZ();
        if (Math.abs(currentX - lastX) < 0.1 && Math.abs(currentZ - lastZ) < 0.1) {
            ticksStuck++;
            
            if (ticksStuck > 40 && distance > ATTACK_RANGE) {
                // Teleport 4 blocks closer to target
                double dx = target.getX() - steve.getX();
                double dz = target.getZ() - steve.getZ();
                double dist = Math.sqrt(dx*dx + dz*dz);
                double moveAmount = Math.min(4.0, dist - ATTACK_RANGE);
                
                steve.teleportTo(
                    steve.getX() + (dx/dist) * moveAmount,
                    steve.getY(),
                    steve.getZ() + (dz/dist) * moveAmount
                );
                ticksStuck = 0;
                com.steve.ai.SteveMod.LOGGER.info("Steve '{}' was stuck, teleported closer to target", 
                    steve.getSteveName());
            }
        } else {
            ticksStuck = 0;
        }
        lastX = currentX;
        lastZ = currentZ;
        
        if (distance <= ATTACK_RANGE) {
            steve.doHurtTarget(target);
            hasAttacked = true;
            steve.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            
            // Attack 3 times per second (every 6-7 ticks)
            if (ticksRunning % 7 == 0) {
                steve.doHurtTarget(target);
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.setInvulnerableBuilding(false);
        steve.getNavigation().stop();
        steve.setSprinting(false);
        steve.setFlying(false);
        target = null;
        com.steve.ai.SteveMod.LOGGER.info("Steve '{}' combat cancelled, invulnerability disabled", 
            steve.getSteveName());
    }

    @Override
    public String getDescription() {
        return "Attack " + targetType;
    }

    private void findTarget() {
        AABB searchBox = steve.getBoundingBox().inflate(32.0);
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
            everHadTarget = true;
            com.steve.ai.SteveMod.LOGGER.info("Steve '{}' locked onto: {} at {}m",
                steve.getSteveName(), target.getType().toString(), (int)nearestDistance);
        }
    }

    private void cleanupCombat() {
        steve.setInvulnerableBuilding(false);
        steve.setSprinting(false);
        steve.getNavigation().stop();
    }

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

        // Match ANY passive animal (e.g. "hunt an animal for dinner")
        if (targetLower.contains("animal") || targetLower.contains("passive")) {
            return entity instanceof net.minecraft.world.entity.animal.Animal;
        }

        // Match specific entity type (e.g. "cow", "pig", "sheep", "chicken")
        String entityTypeName = entity.getType().toString().toLowerCase();
        return entityTypeName.contains(targetLower);
    }
}
