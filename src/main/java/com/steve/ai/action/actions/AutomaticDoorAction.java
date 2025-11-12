package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.redstone.RedstoneHelper;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;

/**
 * Builds automatic door with pressure plate activation
 * Creates a simple redstone circuit for door automation
 */
public class AutomaticDoorAction extends BaseAction {
    private BlockPos doorPos;
    private int ticksRunning;
    private int componentsPlaced;
    private static final int MAX_TICKS = 400; // 20 seconds
    private static final int REQUIRED_REDSTONE = 5;

    private enum BuildPhase {
        PLACE_DOOR,
        PLACE_PRESSURE_PLATES,
        PLACE_REDSTONE,
        COMPLETE
    }

    private BuildPhase currentPhase = BuildPhase.PLACE_DOOR;

    public AutomaticDoorAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        ticksRunning = 0;
        componentsPlaced = 0;

        // Check materials
        if (!hasMaterials()) {
            result = ActionResult.failure("Missing materials: need door, 2 pressure plates, " +
                REQUIRED_REDSTONE + " redstone dust");
            return;
        }

        // Set door position in front of Steve
        doorPos = steve.blockPosition().relative(steve.getDirection(), 2);

        SteveMod.LOGGER.info("Steve '{}' building automatic door at {}",
            steve.getSteveName(), doorPos);

        steve.setFlying(true);
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            result = ActionResult.failure("Automatic door building timeout");
            return;
        }

        switch (currentPhase) {
            case PLACE_DOOR -> {
                if (placeDoor()) {
                    currentPhase = BuildPhase.PLACE_PRESSURE_PLATES;
                }
            }
            case PLACE_PRESSURE_PLATES -> {
                if (placePressurePlates()) {
                    currentPhase = BuildPhase.PLACE_REDSTONE;
                }
            }
            case PLACE_REDSTONE -> {
                if (placeRedstoneWiring()) {
                    currentPhase = BuildPhase.COMPLETE;
                }
            }
            case COMPLETE -> {
                steve.setFlying(false);
                result = ActionResult.success("Automatic door complete");
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false);
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Build automatic door (" + currentPhase + ")";
    }

    /**
     * Place the door
     */
    private boolean placeDoor() {
        // Check if ground is solid
        BlockPos groundPos = doorPos.below();
        if (!steve.level().getBlockState(groundPos).isSolid()) {
            // Place floor block first
            steve.level().setBlock(groundPos, Blocks.STONE.defaultBlockState(), 3);
        }

        // Place door (2 blocks tall)
        BlockPos lowerDoorPos = doorPos;
        BlockPos upperDoorPos = doorPos.above();

        if (!steve.level().getBlockState(lowerDoorPos).isAir() ||
            !steve.level().getBlockState(upperDoorPos).isAir()) {
            return false; // Space occupied
        }

        // Determine facing direction
        Direction facing = steve.getDirection();

        // Place iron door
        steve.level().setBlock(lowerDoorPos,
            Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER),
            3);

        steve.level().setBlock(upperDoorPos,
            Blocks.IRON_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),
            3);

        // Remove from inventory
        InventoryHelper.removeItem(steve, Items.IRON_DOOR, 1);

        componentsPlaced += 2;
        SteveMod.LOGGER.info("Steve '{}' placed door", steve.getSteveName());

        return true;
    }

    /**
     * Place pressure plates on both sides of door
     */
    private boolean placePressurePlates() {
        Direction facing = steve.getDirection();

        // Front pressure plate (1 block in front of door)
        BlockPos frontPlatePos = doorPos.relative(facing);
        if (RedstoneHelper.placePressurePlate(steve.level(), frontPlatePos)) {
            InventoryHelper.removeItem(steve, Items.STONE_PRESSURE_PLATE, 1);
            componentsPlaced++;
        }

        // Back pressure plate (1 block behind door)
        BlockPos backPlatePos = doorPos.relative(facing.getOpposite());
        if (RedstoneHelper.placePressurePlate(steve.level(), backPlatePos)) {
            InventoryHelper.removeItem(steve, Items.STONE_PRESSURE_PLATE, 1);
            componentsPlaced++;
        }

        SteveMod.LOGGER.info("Steve '{}' placed pressure plates", steve.getSteveName());
        return true;
    }

    /**
     * Place redstone wiring to connect pressure plates to door
     */
    private boolean placeRedstoneWiring() {
        Direction facing = steve.getDirection();

        // Front wiring: plate -> door
        BlockPos frontPlatePos = doorPos.relative(facing);
        BlockPos frontWirePos = frontPlatePos.relative(facing.getClockWise());

        // Place redstone next to plate
        if (RedstoneHelper.placeRedstoneDust(steve.level(), frontWirePos)) {
            InventoryHelper.removeItem(steve, Items.REDSTONE, 1);
            componentsPlaced++;
        }

        // Connect to door position
        BlockPos doorSidePos = doorPos.relative(facing.getClockWise());
        if (RedstoneHelper.placeRedstoneDust(steve.level(), doorSidePos)) {
            InventoryHelper.removeItem(steve, Items.REDSTONE, 1);
            componentsPlaced++;
        }

        // Back wiring: plate -> door
        BlockPos backPlatePos = doorPos.relative(facing.getOpposite());
        BlockPos backWirePos = backPlatePos.relative(facing.getClockWise());

        if (RedstoneHelper.placeRedstoneDust(steve.level(), backWirePos)) {
            InventoryHelper.removeItem(steve, Items.REDSTONE, 1);
            componentsPlaced++;
        }

        SteveMod.LOGGER.info("Steve '{}' placed redstone wiring ({} components total)",
            steve.getSteveName(), componentsPlaced);

        return true;
    }

    /**
     * Check if Steve has required materials
     */
    private boolean hasMaterials() {
        return InventoryHelper.hasItem(steve, Items.IRON_DOOR, 1) &&
               InventoryHelper.hasItem(steve, Items.STONE_PRESSURE_PLATE, 2) &&
               InventoryHelper.getItemCount(steve, Items.REDSTONE) >= REQUIRED_REDSTONE;
    }

    /**
     * Static helper to check materials
     */
    public static boolean hasMaterials(SteveEntity steve) {
        return InventoryHelper.hasItem(steve, Items.IRON_DOOR, 1) &&
               InventoryHelper.hasItem(steve, Items.STONE_PRESSURE_PLATE, 2) &&
               InventoryHelper.getItemCount(steve, Items.REDSTONE) >= 5;
    }

    /**
     * Get materials summary
     */
    public static String getMaterialsSummary(SteveEntity steve) {
        boolean hasDoor = InventoryHelper.hasItem(steve, Items.IRON_DOOR, 1);
        int plates = InventoryHelper.getItemCount(steve, Items.STONE_PRESSURE_PLATE);
        int redstone = InventoryHelper.getItemCount(steve, Items.REDSTONE);

        return String.format("Door: %s, Plates: %d/2, Redstone: %d/5",
            hasDoor ? "Yes" : "No", plates, redstone);
    }
}
