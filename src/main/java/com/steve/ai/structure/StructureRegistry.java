package com.steve.ai.structure;

import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class StructureRegistry {
    private static final List<BuiltStructure> structures = new ArrayList<>();
    private static final int MIN_SPACING = 5;

    public static class BuiltStructure {
        public final BlockPos position;
        public final int width;
        public final int height;
        public final int depth;
        public final String type;
        public final AABB bounds;

        public BuiltStructure(BlockPos pos, int width, int height, int depth, String type) {
            this.position = pos;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.type = type;

            this.bounds = new AABB(
                pos.getX() - MIN_SPACING,
                pos.getY() - MIN_SPACING,
                pos.getZ() - MIN_SPACING,
                pos.getX() + width + MIN_SPACING,
                pos.getY() + height + MIN_SPACING,
                pos.getZ() + depth + MIN_SPACING
            );
        }

        public boolean intersects(BlockPos testPos, int testWidth, int testHeight, int testDepth) {
            AABB testBounds = new AABB(
                testPos.getX(),
                testPos.getY(),
                testPos.getZ(),
                testPos.getX() + testWidth,
                testPos.getY() + testHeight,
                testPos.getZ() + testDepth
            );
            return bounds.intersects(testBounds);
        }

        public double distanceTo(BlockPos pos) {
            double dx = pos.getX() - position.getX();
            double dy = pos.getY() - position.getY();
            double dz = pos.getZ() - position.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public static void register(BlockPos pos, int width, int height, int depth, String type) {
        BuiltStructure structure = new BuiltStructure(pos, width, height, depth, type);
        structures.add(structure);
        SteveMod.LOGGER.info("Registered structure '{}' at {} ({}x{}x{})", type, pos, width, height, depth);
    }

    public static boolean hasConflict(BlockPos pos, int width, int height, int depth) {
        for (BuiltStructure structure : structures) {
            if (structure.intersects(pos, width, height, depth)) {
                return true;
            }
        }
        return false;
    }

    public static BlockPos findClearPosition(BlockPos originalPos, int width, int height, int depth) {
        if (!hasConflict(originalPos, width, height, depth)) {
            return originalPos;
        }
        int maxSearchRadius = 50;
        int searchStep = Math.max(width, depth) + MIN_SPACING;

        for (int radius = searchStep; radius < maxSearchRadius; radius += searchStep) {
            for (int angle = 0; angle < 360; angle += 30) {
                double radians = Math.toRadians(angle);
                int offsetX = (int) (Math.cos(radians) * radius);
                int offsetZ = (int) (Math.sin(radians) * radius);

                BlockPos testPos = new BlockPos(
                    originalPos.getX() + offsetX,
                    originalPos.getY(),
                    originalPos.getZ() + offsetZ
                );

                if (!hasConflict(testPos, width, height, depth)) {
                    SteveMod.LOGGER.info("Found clear position at {} ({}m away)", testPos, radius);
                    return testPos;
                }
            }
        }

        BlockPos fallbackPos = new BlockPos(
            originalPos.getX() + maxSearchRadius,
            originalPos.getY(),
            originalPos.getZ()
        );
        SteveMod.LOGGER.warn("No clear position found, using fallback at {}", fallbackPos);
        return fallbackPos;
    }

    public static List<BuiltStructure> getAllStructures() {
        return new ArrayList<>(structures);
    }

    public static BuiltStructure getClosest(BlockPos pos) {
        if (structures.isEmpty()) {
            return null;
        }

        BuiltStructure closest = structures.get(0);
        double minDistance = closest.distanceTo(pos);

        for (BuiltStructure structure : structures) {
            double distance = structure.distanceTo(pos);
            if (distance < minDistance) {
                minDistance = distance;
                closest = structure;
            }
        }

        return closest;
    }

    public static void clear() {
        structures.clear();
    }

    public static int getCount() {
        return structures.size();
    }
}
