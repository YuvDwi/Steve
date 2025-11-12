package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Retrieves items from a chest into Steve's inventory
 * Workflow:
 * 1. Parse item name from parameters
 * 2. Find nearest chest
 * 3. Navigate to chest
 * 4. Retrieve requested items
 */
public class RetrieveItemsAction extends BaseAction {
    private enum RetrievalPhase {
        VALIDATING,       // Validate item name
        FINDING_CHEST,    // Search for chest with items
        NAVIGATING,       // Move to chest
        RETRIEVING,       // Take items from chest
        COMPLETED         // Done
    }

    private RetrievalPhase phase;
    private BlockPos chestPos;
    private Item targetItem;
    private int targetQuantity;
    private int ticksRunning;
    private int navigationStartTick;
    private int itemsRetrieved;

    private static final int MAX_TICKS = 6000; // 5 minutes
    private static final int NAVIGATION_TIMEOUT = 600; // 30 seconds
    private static final int SEARCH_RADIUS = 16; // Search radius for chests

    public RetrieveItemsAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        phase = RetrievalPhase.VALIDATING;
        ticksRunning = 0;
        itemsRetrieved = 0;
        chestPos = null;
        targetQuantity = task.getIntParameter("quantity", 1);

        steve.getNavigation().stop();
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Retrieval operation timed out", false);
            return;
        }

        switch (phase) {
            case VALIDATING:
                handleValidation();
                break;

            case FINDING_CHEST:
                handleFindingChest();
                break;

            case NAVIGATING:
                handleNavigation();
                break;

            case RETRIEVING:
                handleRetrieving();
                break;

            case COMPLETED:
                // Done
                break;
        }
    }

    private void handleValidation() {
        // Parse item name
        String itemName = task.getStringParameter("item");

        if (itemName == null || itemName.isEmpty()) {
            result = ActionResult.failure("No item specified for retrieval", false);
            phase = RetrievalPhase.COMPLETED;
            return;
        }

        // Parse item resource location
        ResourceLocation itemId = ResourceLocation.tryParse(itemName);
        if (itemId == null) {
            itemId = new ResourceLocation("minecraft", itemName);
        }

        targetItem = BuiltInRegistries.ITEM.get(itemId);

        if (targetItem == null || targetItem == Items.AIR) {
            result = ActionResult.failure("Unknown item: " + itemName, false);
            phase = RetrievalPhase.COMPLETED;
            return;
        }

        phase = RetrievalPhase.FINDING_CHEST;
    }

    private void handleFindingChest() {
        // Search for nearest chest
        // Note: This finds ANY chest, not necessarily one with the target item
        // A more advanced implementation would check chest contents
        chestPos = InventoryHelper.findNearestChest(
            steve.level(),
            steve.blockPosition(),
            SEARCH_RADIUS
        );

        if (chestPos != null) {
            // Found chest - navigate to it
            phase = RetrievalPhase.NAVIGATING;
            navigationStartTick = ticksRunning;
            steve.getNavigation().moveTo(
                chestPos.getX() + 0.5,
                chestPos.getY(),
                chestPos.getZ() + 0.5,
                1.0
            );
        } else {
            result = ActionResult.failure("No chest found within " + SEARCH_RADIUS + " blocks", true);
            phase = RetrievalPhase.COMPLETED;
        }
    }

    private void handleNavigation() {
        // Check if reached chest
        if (steve.blockPosition().distSqr(chestPos) <= 9) { // Within 3 blocks
            steve.getNavigation().stop();
            phase = RetrievalPhase.RETRIEVING;
            return;
        }

        // Check navigation timeout
        if (ticksRunning - navigationStartTick > NAVIGATION_TIMEOUT) {
            result = ActionResult.failure("Could not reach chest", false);
            phase = RetrievalPhase.COMPLETED;
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

    private void handleRetrieving() {
        // Verify chest still exists
        BlockState chestState = steve.level().getBlockState(chestPos);
        if (!chestState.is(Blocks.CHEST) && !chestState.is(Blocks.BARREL)) {
            result = ActionResult.failure("Chest disappeared", false);
            phase = RetrievalPhase.COMPLETED;
            return;
        }

        // Check if inventory has space
        if (InventoryHelper.isInventoryFull(steve)) {
            result = ActionResult.failure("Inventory is full, cannot retrieve items", false);
            phase = RetrievalPhase.COMPLETED;
            return;
        }

        // Retrieve items from chest
        itemsRetrieved = InventoryHelper.retrieveFromChest(
            steve,
            chestPos,
            targetItem,
            targetQuantity
        );

        if (itemsRetrieved > 0) {
            // Update chest memory after retrieving
            steve.getMemory().updateChest(chestPos);

            result = ActionResult.success(
                "Retrieved " + itemsRetrieved + "x " +
                targetItem.getDescriptionId() + " from chest"
            );
        } else {
            result = ActionResult.failure(
                "Chest does not contain " + targetItem.getDescriptionId() +
                " or inventory is full",
                false
            );
        }

        phase = RetrievalPhase.COMPLETED;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        switch (phase) {
            case VALIDATING:
                return "Validating retrieval request";
            case FINDING_CHEST:
                return "Finding chest with " + (targetItem != null ? targetItem.getDescriptionId() : "items");
            case NAVIGATING:
                return "Moving to chest";
            case RETRIEVING:
                return "Retrieving " + targetQuantity + "x " +
                       (targetItem != null ? targetItem.getDescriptionId() : "items");
            default:
                return "Retrieve items";
        }
    }

    /**
     * Get the number of items successfully retrieved
     * @return Count of items retrieved
     */
    public int getItemsRetrieved() {
        return itemsRetrieved;
    }

    /**
     * Get the chest position used for retrieval
     * @return BlockPos of chest
     */
    public BlockPos getChestPosition() {
        return chestPos;
    }
}
