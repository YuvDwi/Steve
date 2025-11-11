package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places a chest at a suitable location
 * Workflow:
 * 1. Check if Steve has a chest in inventory
 * 2. Find suitable position (near Steve, on solid ground)
 * 3. Place chest
 * 4. Navigate to chest (optional)
 */
public class PlaceChestAction extends BaseAction {
    private enum PlacementPhase {
        VALIDATING,      // Check if has chest
        FINDING_SPOT,    // Find suitable position
        PLACING,         // Place the chest
        COMPLETED        // Done
    }

    private PlacementPhase phase;
    private BlockPos targetPos;
    private int ticksRunning;
    private static final int MAX_TICKS = 600; // 30 seconds

    public PlaceChestAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        phase = PlacementPhase.VALIDATING;
        ticksRunning = 0;
        targetPos = null;
        steve.getNavigation().stop();
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Chest placement timed out", false);
            return;
        }

        switch (phase) {
            case VALIDATING:
                handleValidation();
                break;

            case FINDING_SPOT:
                handleFindingSpot();
                break;

            case PLACING:
                handlePlacing();
                break;

            case COMPLETED:
                // Done
                break;
        }
    }

    private void handleValidation() {
        // Check if Steve has a chest
        if (!InventoryHelper.hasItem(steve, Items.CHEST, 1)) {
            result = ActionResult.failure("No chest in inventory", true);
            phase = PlacementPhase.COMPLETED;
            return;
        }

        phase = PlacementPhase.FINDING_SPOT;
    }

    private void handleFindingSpot() {
        // Find suitable position near Steve
        BlockPos stevePos = steve.blockPosition();

        // Try positions in a spiral pattern around Steve
        for (int radius = 1; radius <= 5; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    // Only check perimeter of current radius
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }

                    BlockPos checkPos = stevePos.offset(x, 0, z);

                    // Find ground level
                    for (int y = 3; y >= -3; y--) {
                        BlockPos groundCheck = checkPos.offset(0, y, 0);
                        BlockPos aboveGround = groundCheck.above();

                        BlockState groundState = steve.level().getBlockState(groundCheck);
                        BlockState aboveState = steve.level().getBlockState(aboveGround);

                        // Check if this is a good spot:
                        // - Ground is solid
                        // - Space above is air
                        // - Not too far from Steve
                        if (groundState.isSolidRender(steve.level(), groundCheck) &&
                            aboveState.isAir() &&
                            steve.distanceToSqr(aboveGround.getX() + 0.5, aboveGround.getY(), aboveGround.getZ() + 0.5) < 64) {

                            targetPos = aboveGround;
                            phase = PlacementPhase.PLACING;
                            return;
                        }
                    }
                }
            }
        }

        // No suitable position found - place at Steve's feet
        BlockPos fallbackPos = stevePos.below();
        if (steve.level().getBlockState(fallbackPos).isAir()) {
            targetPos = fallbackPos;
            phase = PlacementPhase.PLACING;
        } else {
            result = ActionResult.failure("Cannot find suitable position for chest", false);
            phase = PlacementPhase.COMPLETED;
        }
    }

    private void handlePlacing() {
        if (targetPos == null) {
            result = ActionResult.failure("No target position for chest", false);
            phase = PlacementPhase.COMPLETED;
            return;
        }

        // Double-check the position is still valid
        BlockState currentState = steve.level().getBlockState(targetPos);
        if (!currentState.isAir()) {
            result = ActionResult.failure("Target position is no longer empty", false);
            phase = PlacementPhase.COMPLETED;
            return;
        }

        // Place the chest
        BlockState chestState = Blocks.CHEST.defaultBlockState();
        steve.level().setBlock(targetPos, chestState, 3);

        // Remove chest from inventory
        boolean removed = InventoryHelper.removeItem(steve, Items.CHEST, 1);

        if (removed) {
            result = ActionResult.success("Placed chest at " + targetPos.toShortString());
            // Store the chest position in task parameters for retrieval by other actions
            task.getParameters().put("chest_position", targetPos);
        } else {
            // This shouldn't happen as we checked earlier
            result = ActionResult.failure("Failed to remove chest from inventory", false);
        }

        phase = PlacementPhase.COMPLETED;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        if (phase == PlacementPhase.PLACING && targetPos != null) {
            return "Placing chest at " + targetPos.toShortString();
        }
        return "Place chest";
    }

    /**
     * Get the position where the chest was placed (after completion)
     * @return BlockPos of placed chest, or null if not yet placed
     */
    public BlockPos getPlacedChestPosition() {
        return targetPos;
    }
}
