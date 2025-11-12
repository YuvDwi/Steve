package com.steve.ai.dimension;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Helper for safe navigation in Nether and End dimensions
 * Handles dimension-specific hazards and navigation
 */
public class DimensionNavigator {
    private final SteveEntity steve;
    private final Level level;

    public enum Dimension {
        OVERWORLD,
        NETHER,
        END,
        UNKNOWN
    }

    public DimensionNavigator(SteveEntity steve) {
        this.steve = steve;
        this.level = steve.level();
    }

    /**
     * Get current dimension
     */
    public Dimension getCurrentDimension() {
        String dimensionKey = level.dimension().location().toString();

        return switch (dimensionKey) {
            case "minecraft:overworld" -> Dimension.OVERWORLD;
            case "minecraft:the_nether" -> Dimension.NETHER;
            case "minecraft:the_end" -> Dimension.END;
            default -> Dimension.UNKNOWN;
        };
    }

    /**
     * Check if position is safe in current dimension
     */
    public boolean isSafePosition(BlockPos pos) {
        Dimension dim = getCurrentDimension();

        return switch (dim) {
            case NETHER -> isSafeInNether(pos);
            case END -> isSafeInEnd(pos);
            case OVERWORLD -> true; // Overworld is generally safe
            case UNKNOWN -> false;
        };
    }

    /**
     * Check if position is safe in Nether
     */
    private boolean isSafeInNether(BlockPos pos) {
        // Check for lava nearby
        if (hasLavaNearby(pos, 2)) {
            return false;
        }

        // Check for solid ground
        if (!hasSolidGround(pos)) {
            return false;
        }

        // Check for open lava ocean below
        if (isAboveLavaOcean(pos)) {
            return false;
        }

        return true;
    }

    /**
     * Check if position is safe in End
     */
    private boolean isSafeInEnd(BlockPos pos) {
        // Check for void below
        if (isAboveVoid(pos, 10)) {
            return false;
        }

        // Check for solid ground
        if (!hasSolidGround(pos)) {
            return false;
        }

        return true;
    }

    /**
     * Check if there's lava nearby
     */
    private boolean hasLavaNearby(BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = center.offset(x, y, z);
                    BlockState state = level.getBlockState(checkPos);

                    if (state.getBlock() == Blocks.LAVA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Check if position has solid ground below
     */
    private boolean hasSolidGround(BlockPos pos) {
        BlockPos groundPos = pos.below();
        BlockState groundState = level.getBlockState(groundPos);
        return groundState.isSolid();
    }

    /**
     * Check if position is above lava ocean (common in Nether)
     */
    private boolean isAboveLavaOcean(BlockPos pos) {
        // Check several blocks below
        for (int y = 1; y <= 5; y++) {
            BlockPos checkPos = pos.below(y);
            BlockState state = level.getBlockState(checkPos);

            if (state.getBlock() == Blocks.LAVA) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if position is above void
     */
    private boolean isAboveVoid(BlockPos pos, int checkDepth) {
        for (int y = 1; y <= checkDepth; y++) {
            BlockPos checkPos = pos.below(y);

            // In End, Y < 0 is void
            if (checkPos.getY() < 0) {
                return true;
            }

            BlockState state = level.getBlockState(checkPos);
            if (state.isSolid()) {
                return false; // Found solid ground
            }
        }

        // No solid ground found within check depth
        return pos.getY() < 64; // Assume void if low Y and no ground
    }

    /**
     * Find nearest safe position from current location
     */
    public BlockPos findNearestSafePosition(int searchRadius) {
        BlockPos startPos = steve.blockPosition();
        BlockPos nearestSafe = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                for (int y = -5; y <= 5; y++) {
                    BlockPos checkPos = startPos.offset(x, y, z);

                    if (isSafePosition(checkPos)) {
                        double distance = startPos.distSqr(checkPos);
                        if (distance < nearestDistance) {
                            nearestSafe = checkPos;
                            nearestDistance = distance;
                        }
                    }
                }
            }
        }

        return nearestSafe;
    }

    /**
     * Get dimension-specific safety advice
     */
    public String getSafetyAdvice() {
        Dimension dim = getCurrentDimension();

        return switch (dim) {
            case NETHER -> "Nether: Watch for lava, ghasts, and open spaces. Build paths carefully.";
            case END -> "End: Avoid void edges. Beware of Endermen and the Dragon.";
            case OVERWORLD -> "Overworld: Standard precautions apply.";
            case UNKNOWN -> "Unknown dimension: Exercise extreme caution.";
        };
    }

    /**
     * Check if dimension is hostile
     */
    public boolean isHostileDimension() {
        Dimension dim = getCurrentDimension();
        return dim == Dimension.NETHER || dim == Dimension.END;
    }

    /**
     * Get recommended equipment for current dimension
     */
    public String getRecommendedEquipment() {
        Dimension dim = getCurrentDimension();

        return switch (dim) {
            case NETHER -> "Fire Resistance potions, good armor, cobblestone for bridging";
            case END -> "Bow and arrows, good armor, ender pearls, slow falling potions";
            case OVERWORLD -> "Standard equipment";
            case UNKNOWN -> "Full protection recommended";
        };
    }

    /**
     * Find nearest nether fortress (simplified detection)
     */
    public BlockPos findNetherFortress(int searchRadius) {
        if (getCurrentDimension() != Dimension.NETHER) {
            return null;
        }

        BlockPos stevePos = steve.blockPosition();

        // Search for nether brick blocks (indicates fortress)
        for (int x = -searchRadius; x <= searchRadius; x += 16) {
            for (int z = -searchRadius; z <= searchRadius; z += 16) {
                for (int y = 32; y <= 96; y += 8) {
                    BlockPos checkPos = stevePos.offset(x, y - stevePos.getY(), z);

                    if (level.getBlockState(checkPos).getBlock() == Blocks.NETHER_BRICKS) {
                        SteveMod.LOGGER.info("Steve '{}' found nether fortress at {}",
                            steve.getSteveName(), checkPos);
                        return checkPos;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Check if near End portal
     */
    public boolean isNearEndPortal(int radius) {
        if (getCurrentDimension() != Dimension.END) {
            return false;
        }

        BlockPos stevePos = steve.blockPosition();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -radius; y <= radius; y++) {
                    BlockPos checkPos = stevePos.offset(x, y, z);

                    if (level.getBlockState(checkPos).getBlock() == Blocks.END_PORTAL) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
