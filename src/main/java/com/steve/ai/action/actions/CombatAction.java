package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
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

    private static final int MAX_TICKS = 600;
    private static final double ATTACK_RANGE = 3.5;

    public CombatAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        targetType = task.getStringParameter("target");
        ticksRunning = 0;
        ticksStuck = 0;

        steve.setFlying(false);
        steve.setInvulnerableBuilding(true);

        findTarget();

        if (target == null) {
            SteveMod.LOGGER.warn("Steve '{}' no targets nearby", steve.getSteveName());
        }
    }

    @Override
    protected void onTick() {
        try {
            ticksRunning++;

            if (ticksRunning > MAX_TICKS) {
                cleanup();
                result = ActionResult.success("Combat complete");
                return;
            }

            if (target == null || !target.isAlive() || target.isRemoved()) {
                if (ticksRunning % 20 == 0) {
                    findTarget();
                }
                if (target == null) return;
            }

            double distance = steve.distanceTo(target);

            steve.setSprinting(true);
            steve.getNavigation().moveTo(target, 2.5);

            double currentX = steve.getX();
            double currentZ = steve.getZ();

            if (Math.abs(currentX - lastX) < 0.1 && Math.abs(currentZ - lastZ) < 0.1) {
                ticksStuck++;

                if (ticksStuck > 40 && distance > ATTACK_RANGE) {
                    double dx = target.getX() - steve.getX();
                    double dz = target.getZ() - steve.getZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);

                    if (dist > 0.01) {
                        double moveAmount = Math.min(4.0, dist - ATTACK_RANGE);
                        steve.teleportTo(
                            steve.getX() + (dx / dist) * moveAmount,
                            steve.getY(),
                            steve.getZ() + (dz / dist) * moveAmount
                        );
                    }

                    ticksStuck = 0;
                }
            } else {
                ticksStuck = 0;
            }

            lastX = currentX;
            lastZ = currentZ;

            if (distance <= ATTACK_RANGE) {
                steve.doHurtTarget(target);
                steve.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);

                if (ticksRunning % 7 == 0) {
                    steve.doHurtTarget(target);
                }
            }

        } catch (Exception e) {
            SteveMod.LOGGER.error("Error during combat", e);
            cleanup();
            result = ActionResult.failure("Combat error: " + e.getMessage());
        }
    }

    private void cleanup() {
        steve.setInvulnerableBuilding(false);
        steve.setSprinting(false);
        steve.getNavigation().stop();
        steve.setFlying(false);
        target = null;
    }

    @Override
    protected void onCancel() {
        cleanup();
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
            SteveMod.LOGGER.info(
                "Steve '{}' locked onto: {} at {}m",
                steve.getSteveName(),
                target.getType().toString(),
                (int) nearestDistance
            );
        }
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) return false;

        if (entity instanceof SteveEntity ||
            entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }

        String targetLower = targetType.toLowerCase();

        if (targetLower.contains("mob") ||
            targetLower.contains("hostile") ||
            targetLower.contains("monster") ||
            targetLower.equals("any")) {
            return entity instanceof Monster;
        }

        String entityTypeName = entity.getType().toString().toLowerCase();
        return entityTypeName.contains(targetLower);
    }
}
