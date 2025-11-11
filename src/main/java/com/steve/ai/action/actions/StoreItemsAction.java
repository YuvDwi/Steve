package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stores items from Steve's inventory into a nearby chest
 * Workflow:
 * 1. Find nearest chest (or place one if needed)
 * 2. Navigate to chest
 * 3. Transfer items to chest
 * 4. Return success with count of items stored
 */
public class StoreItemsAction extends BaseAction {
    private enum StoragePhase {
        FINDING_CHEST,    // Search for nearby chest
        PLACING_CHEST,    // Place chest if none found
        NAVIGATING,       // Move to chest
        STORING,          // Transfer items
        COMPLETED         // Done
    }

    private StoragePhase phase;
    private BlockPos chestPos;
    private Item itemToStore; // null = store all
    private int ticksRunning;
    private int navigationStartTick;
    private int itemsStored;

    private static final int MAX_TICKS = 6000; // 5 minutes
    private static final int NAVIGATION_TIMEOUT = 600; // 30 seconds
    private static final int SEARCH_RADIUS = 16; // Search radius for chests

    public StoreItemsAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        phase = StoragePhase.FINDING_CHEST;
        ticksRunning = 0;
        itemsStored = 0;
        chestPos = null;

        // Check if specific item was requested
        String itemName = task.getStringParameter("item", null);
        if (itemName != null && !itemName.isEmpty()) {
            // Parse item (simplified - could use registry lookup)
            // For now, null means "store all"
            itemToStore = null; // TODO: Parse item name
        } else {
            itemToStore = null; // Store all items
        }

        steve.getNavigation().stop();
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Storage operation timed out", false);
            return;
        }

        switch (phase) {
            case FINDING_CHEST:
                handleFindingChest();
                break;

            case PLACING_CHEST:
                handlePlacingChest();
                break;

            case NAVIGATING:
                handleNavigation();
                break;

            case STORING:
                handleStoring();
                break;

            case COMPLETED:
                // Done
                break;
        }
    }

    private void handleFindingChest() {
        // Search for nearby chest
        chestPos = InventoryHelper.findNearestChest(
            steve.level(),
            steve.blockPosition(),
            SEARCH_RADIUS
        );

        if (chestPos != null) {
            // Found chest - navigate to it
            phase = StoragePhase.NAVIGATING;
            navigationStartTick = ticksRunning;
            steve.getNavigation().moveTo(
                chestPos.getX() + 0.5,
                chestPos.getY(),
                chestPos.getZ() + 0.5,
                1.0
            );
        } else {
            // No chest found - need to place one
            if (InventoryHelper.hasItem(steve, Items.CHEST, 1)) {
                phase = StoragePhase.PLACING_CHEST;
            } else {
                result = ActionResult.failure("No chest nearby and none in inventory", true);
                phase = StoragePhase.COMPLETED;
            }
        }
    }

    private void handlePlacingChest() {
        // Find suitable position to place chest (2 blocks in front)
        BlockPos stevePos = steve.blockPosition();
        BlockPos placePos = stevePos.relative(steve.getDirection());

        // Check if position is valid
        BlockState targetState = steve.level().getBlockState(placePos);
        BlockState belowState = steve.level().getBlockState(placePos.below());

        if (targetState.isAir() && belowState.isSolidRender(steve.level(), placePos.below())) {
            // Place chest
            steve.level().setBlock(placePos, Blocks.CHEST.defaultBlockState(), 3);
            InventoryHelper.removeItem(steve, Items.CHEST, 1);

            chestPos = placePos;
            phase = StoragePhase.NAVIGATING;
            navigationStartTick = ticksRunning;
            steve.getNavigation().moveTo(
                chestPos.getX() + 0.5,
                chestPos.getY(),
                chestPos.getZ() + 0.5,
                1.0
            );
        } else {
            // Try adjacent positions
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    BlockPos tryPos = stevePos.offset(dx, 0, dz);
                    BlockState tryState = steve.level().getBlockState(tryPos);
                    BlockState tryBelow = steve.level().getBlockState(tryPos.below());

                    if (tryState.isAir() && tryBelow.isSolidRender(steve.level(), tryPos.below())) {
                        steve.level().setBlock(tryPos, Blocks.CHEST.defaultBlockState(), 3);
                        InventoryHelper.removeItem(steve, Items.CHEST, 1);

                        chestPos = tryPos;
                        phase = StoragePhase.STORING; // Already close enough
                        return;
                    }
                }
            }

            // Still couldn't place
            result = ActionResult.failure("Cannot find suitable position for chest", false);
            phase = StoragePhase.COMPLETED;
        }
    }

    private void handleNavigation() {
        // Check if reached chest
        if (steve.blockPosition().distSqr(chestPos) <= 9) { // Within 3 blocks
            steve.getNavigation().stop();
            phase = StoragePhase.STORING;
            return;
        }

        // Check navigation timeout
        if (ticksRunning - navigationStartTick > NAVIGATION_TIMEOUT) {
            result = ActionResult.failure("Could not reach chest", false);
            phase = StoragePhase.COMPLETED;
            return;
        }

        // Re-navigate if path lost
        if (!steve.getNavigation().isInProgress()) {
            steve.getNavigation().moveTo(
                chestPos.getX() + 0.5,
                chestPos.getY(),
                chestPos.getZ() + 0.5,
                1.0
            );
        }
    }

    private void handleStoring() {
        // Verify chest still exists
        BlockState chestState = steve.level().getBlockState(chestPos);
        if (!chestState.is(Blocks.CHEST) && !chestState.is(Blocks.BARREL)) {
            result = ActionResult.failure("Chest disappeared", false);
            phase = StoragePhase.COMPLETED;
            return;
        }

        // Transfer items to chest
        itemsStored = InventoryHelper.transferToChest(steve, chestPos, itemToStore);

        if (itemsStored > 0) {
            result = ActionResult.success("Stored " + itemsStored + " items in chest at " + chestPos.toShortString());
        } else {
            result = ActionResult.failure("No items to store or chest is full", false);
        }

        phase = StoragePhase.COMPLETED;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        switch (phase) {
            case FINDING_CHEST:
                return "Finding chest for storage";
            case PLACING_CHEST:
                return "Placing storage chest";
            case NAVIGATING:
                return "Moving to chest";
            case STORING:
                return "Storing items (" + itemsStored + " stored)";
            default:
                return "Store items";
        }
    }

    /**
     * Get the number of items successfully stored
     * @return Count of items stored
     */
    public int getItemsStored() {
        return itemsStored;
    }

    /**
     * Get the chest position used for storage
     * @return BlockPos of chest
     */
    public BlockPos getChestPosition() {
        return chestPos;
    }
}
