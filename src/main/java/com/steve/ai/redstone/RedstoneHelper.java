package com.steve.ai.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Helper class for redstone operations and circuit building
 * Provides utilities for placing redstone components and checking power
 */
public class RedstoneHelper {

    /**
     * Place redstone dust at position
     */
    public static boolean placeRedstoneDust(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        BlockState redstone = Blocks.REDSTONE_WIRE.defaultBlockState();
        level.setBlock(pos, redstone, 3);
        return true;
    }

    /**
     * Place redstone torch at position
     */
    public static boolean placeRedstoneTorch(Level level, BlockPos pos, Direction facing) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        Block torchBlock = facing == Direction.UP ?
            Blocks.REDSTONE_TORCH : Blocks.REDSTONE_WALL_TORCH;

        BlockState torch = torchBlock.defaultBlockState();
        level.setBlock(pos, torch, 3);
        return true;
    }

    /**
     * Place redstone repeater at position
     */
    public static boolean placeRepeater(Level level, BlockPos pos, Direction facing, int delay) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        BlockState repeater = Blocks.REPEATER.defaultBlockState()
            .setValue(RepeaterBlock.FACING, facing)
            .setValue(RepeaterBlock.DELAY, Math.min(4, Math.max(1, delay)));

        level.setBlock(pos, repeater, 3);
        return true;
    }

    /**
     * Place redstone comparator at position
     */
    public static boolean placeComparator(Level level, BlockPos pos, Direction facing) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        BlockState comparator = Blocks.COMPARATOR.defaultBlockState()
            .setValue(ComparatorBlock.FACING, facing);

        level.setBlock(pos, comparator, 3);
        return true;
    }

    /**
     * Place lever at position
     */
    public static boolean placeLever(Level level, BlockPos pos, Direction attachFace) {
        BlockState lever = Blocks.LEVER.defaultBlockState();

        if (attachFace == Direction.UP) {
            lever = lever.setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
        } else if (attachFace == Direction.DOWN) {
            lever = lever.setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.CEILING);
        } else {
            lever = lever.setValue(LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                         .setValue(LeverBlock.FACING, attachFace);
        }

        level.setBlock(pos, lever, 3);
        return true;
    }

    /**
     * Place button at position
     */
    public static boolean placeButton(Level level, BlockPos pos, Direction facing) {
        BlockState button = Blocks.STONE_BUTTON.defaultBlockState()
            .setValue(ButtonBlock.FACING, facing);

        level.setBlock(pos, button, 3);
        return true;
    }

    /**
     * Place pressure plate at position
     */
    public static boolean placePressurePlate(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        BlockState plate = Blocks.STONE_PRESSURE_PLATE.defaultBlockState();
        level.setBlock(pos, plate, 3);
        return true;
    }

    /**
     * Place piston at position
     */
    public static boolean placePiston(Level level, BlockPos pos, Direction facing, boolean sticky) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        Block pistonBlock = sticky ? Blocks.STICKY_PISTON : Blocks.PISTON;
        BlockState piston = pistonBlock.defaultBlockState()
            .setValue(PistonBaseBlock.FACING, facing);

        level.setBlock(pos, piston, 3);
        return true;
    }

    /**
     * Place redstone lamp at position
     */
    public static boolean placeRedstoneLamp(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }

        BlockState lamp = Blocks.REDSTONE_LAMP.defaultBlockState();
        level.setBlock(pos, lamp, 3);
        return true;
    }

    /**
     * Check if position is receiving redstone power
     */
    public static boolean isPowered(Level level, BlockPos pos) {
        return level.hasNeighborSignal(pos);
    }

    /**
     * Get redstone power level at position
     */
    public static int getPowerLevel(Level level, BlockPos pos) {
        return level.getBestNeighborSignal(pos);
    }

    /**
     * Check if block is a redstone component
     */
    public static boolean isRedstoneComponent(Block block) {
        return block instanceof RedStoneWireBlock ||
               block instanceof RepeaterBlock ||
               block instanceof ComparatorBlock ||
               block instanceof LeverBlock ||
               block instanceof ButtonBlock ||
               block instanceof PressurePlateBlock ||
               block instanceof RedstoneTorchBlock ||
               block instanceof PistonBaseBlock ||
               block instanceof RedstoneLampBlock;
    }

    /**
     * Build simple redstone wire path between two points
     */
    public static int buildWirePath(Level level, BlockPos start, BlockPos end) {
        int placed = 0;

        // Simple horizontal path
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();

        // Place along X axis first
        int stepX = dx > 0 ? 1 : -1;
        for (int i = 0; i != dx; i += stepX) {
            BlockPos wirePos = start.offset(i, 0, 0);
            if (placeRedstoneDust(level, wirePos)) {
                placed++;
            }
        }

        // Then along Z axis
        int stepZ = dz > 0 ? 1 : -1;
        for (int i = 0; i != dz; i += stepZ) {
            BlockPos wirePos = start.offset(dx, 0, i);
            if (placeRedstoneDust(level, wirePos)) {
                placed++;
            }
        }

        return placed;
    }

    /**
     * Create a simple door control circuit
     * Returns true if circuit was successfully created
     */
    public static boolean createDoorCircuit(Level level, BlockPos buttonPos, BlockPos doorPos) {
        // Calculate path from button to door
        // Place button
        Direction buttonFacing = Direction.NORTH; // Default facing
        if (!placeButton(level, buttonPos, buttonFacing)) {
            return false;
        }

        // Build redstone wire path
        BlockPos wireStart = buttonPos.relative(buttonFacing.getOpposite());
        int wiresPlaced = buildWirePath(level, wireStart, doorPos.below());

        return wiresPlaced > 0;
    }

    /**
     * Get required materials for basic redstone circuit
     */
    public static String getCircuitMaterials(CircuitType type) {
        return switch (type) {
            case DOOR -> "1 button, redstone dust (varies), 1 door";
            case LAMP -> "1 lever, redstone dust (varies), 1 redstone lamp";
            case PISTON_DOOR -> "4 sticky pistons, 4 blocks, 1 button, redstone dust";
            case PRESSURE_PLATE -> "1 pressure plate, redstone dust (varies), 1 door";
        };
    }

    /**
     * Circuit types
     */
    public enum CircuitType {
        DOOR,           // Button/lever activated door
        LAMP,           // Switch for redstone lamp
        PISTON_DOOR,    // Hidden piston door
        PRESSURE_PLATE  // Pressure plate auto door
    }
}
