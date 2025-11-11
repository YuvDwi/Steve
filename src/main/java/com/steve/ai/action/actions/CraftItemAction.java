package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import com.steve.ai.util.RecipeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Optional;

/**
 * Handles crafting of items
 * Workflow:
 * 1. Check if recipe exists
 * 2. Check if all ingredients available in inventory
 * 3. If crafting table required, find or place one
 * 4. Navigate to crafting table
 * 5. Execute crafting
 * 6. Remove ingredients from inventory
 * 7. Add result to inventory
 */
public class CraftItemAction extends BaseAction {
    private enum CraftingPhase {
        VALIDATING,           // Check recipe and ingredients
        FINDING_TABLE,        // Search for crafting table
        PLACING_TABLE,        // Place crafting table if not found
        NAVIGATING_TO_TABLE,  // Move to crafting table
        CRAFTING,             // Perform crafting
        COMPLETED             // Done
    }

    private String itemName;
    private int quantity;
    private int ticksRunning;
    private int craftedCount;

    private CraftingPhase phase;
    private Item targetItem;
    private BlockPos craftingTablePos;

    // Timeouts
    private static final int MAX_TICKS = 12000; // 10 minutes
    private static final int NAVIGATION_TIMEOUT = 600; // 30 seconds
    private int navigationStartTick;

    public CraftItemAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item");
        quantity = task.getIntParameter("quantity", 1);
        ticksRunning = 0;
        craftedCount = 0;
        phase = CraftingPhase.VALIDATING;

        // Parse item name
        ResourceLocation itemId = ResourceLocation.tryParse(itemName);
        if (itemId == null) {
            // Try with minecraft: namespace
            itemId = new ResourceLocation("minecraft", itemName);
        }

        targetItem = BuiltInRegistries.ITEM.get(itemId);

        if (targetItem == null || targetItem == Items.AIR) {
            result = ActionResult.failure("Unknown item: " + itemName, false);
            phase = CraftingPhase.COMPLETED;
            return;
        }

        // Validate recipe exists
        ServerLevel level = (ServerLevel) steve.level();
        if (!RecipeHelper.hasRecipe(level, targetItem)) {
            result = ActionResult.failure("No crafting recipe for " + itemName, false);
            phase = CraftingPhase.COMPLETED;
            return;
        }

        steve.getNavigation().stop();
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        // Timeout check
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Crafting timed out after " + (MAX_TICKS / 20) + " seconds", true);
            return;
        }

        ServerLevel level = (ServerLevel) steve.level();

        switch (phase) {
            case VALIDATING:
                handleValidation(level);
                break;

            case FINDING_TABLE:
                handleFindingTable(level);
                break;

            case PLACING_TABLE:
                handlePlacingTable(level);
                break;

            case NAVIGATING_TO_TABLE:
                handleNavigation(level);
                break;

            case CRAFTING:
                handleCrafting(level);
                break;

            case COMPLETED:
                // Done
                break;
        }
    }

    private void handleValidation(ServerLevel level) {
        // Check if Steve has all ingredients
        Map<Item, Integer> missing = RecipeHelper.getMissingIngredients(level, steve, targetItem);

        if (!missing.isEmpty()) {
            StringBuilder msg = new StringBuilder("Missing ingredients: ");
            for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
                msg.append(entry.getValue()).append("x ")
                   .append(entry.getKey().getDescriptionId()).append(", ");
            }
            result = ActionResult.failure(msg.toString(), true);
            phase = CraftingPhase.COMPLETED;
            return;
        }

        // Check if crafting table required
        if (RecipeHelper.requiresCraftingTable(level, targetItem)) {
            phase = CraftingPhase.FINDING_TABLE;
        } else {
            // Can craft in inventory (2x2)
            phase = CraftingPhase.CRAFTING;
        }
    }

    private void handleFindingTable(ServerLevel level) {
        // Search for crafting table within 16 blocks
        BlockPos stevePos = steve.blockPosition();
        craftingTablePos = null;

        for (int x = -16; x <= 16; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -16; z <= 16; z++) {
                    BlockPos pos = stevePos.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (state.is(Blocks.CRAFTING_TABLE)) {
                        craftingTablePos = pos;
                        phase = CraftingPhase.NAVIGATING_TO_TABLE;
                        navigationStartTick = ticksRunning;
                        steve.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
                        return;
                    }
                }
            }
        }

        // No crafting table found - need to place one
        if (InventoryHelper.hasItem(steve, Items.CRAFTING_TABLE, 1)) {
            phase = CraftingPhase.PLACING_TABLE;
        } else {
            result = ActionResult.failure("No crafting table nearby and none in inventory", true);
            phase = CraftingPhase.COMPLETED;
        }
    }

    private void handlePlacingTable(ServerLevel level) {
        // Find suitable position to place crafting table (2 blocks in front)
        BlockPos stevePos = steve.blockPosition();
        BlockPos placePos = stevePos.relative(steve.getDirection());

        // Check if position is air and has solid block below
        if (level.getBlockState(placePos).isAir() &&
            level.getBlockState(placePos.below()).isSolidRender(level, placePos.below())) {

            // Place crafting table
            level.setBlock(placePos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);

            // Remove from inventory
            InventoryHelper.removeItem(steve, Items.CRAFTING_TABLE, 1);

            craftingTablePos = placePos;
            phase = CraftingPhase.NAVIGATING_TO_TABLE;
            navigationStartTick = ticksRunning;
            steve.getNavigation().moveTo(placePos.getX() + 0.5, placePos.getY(), placePos.getZ() + 0.5, 1.0);
        } else {
            result = ActionResult.failure("Cannot find suitable position to place crafting table", true);
            phase = CraftingPhase.COMPLETED;
        }
    }

    private void handleNavigation(ServerLevel level) {
        // Check if reached crafting table
        if (steve.blockPosition().distSqr(craftingTablePos) <= 9) { // Within 3 blocks
            steve.getNavigation().stop();
            phase = CraftingPhase.CRAFTING;
            return;
        }

        // Check navigation timeout
        if (ticksRunning - navigationStartTick > NAVIGATION_TIMEOUT) {
            result = ActionResult.failure("Could not reach crafting table", true);
            phase = CraftingPhase.COMPLETED;
            return;
        }

        // Re-navigate if path lost
        if (!steve.getNavigation().isInProgress()) {
            steve.getNavigation().moveTo(
                craftingTablePos.getX() + 0.5,
                craftingTablePos.getY(),
                craftingTablePos.getZ() + 0.5,
                1.0
            );
        }
    }

    private void handleCrafting(ServerLevel level) {
        // Check if we need to craft more
        if (craftedCount >= quantity) {
            result = ActionResult.success("Crafted " + craftedCount + "x " + itemName);
            phase = CraftingPhase.COMPLETED;
            return;
        }

        // Check if still has ingredients
        if (!RecipeHelper.hasAllIngredients(level, steve, targetItem)) {
            if (craftedCount > 0) {
                result = ActionResult.success("Crafted " + craftedCount + "x " + itemName + " (ran out of ingredients)");
            } else {
                result = ActionResult.failure("Missing ingredients for crafting", true);
            }
            phase = CraftingPhase.COMPLETED;
            return;
        }

        // Perform one crafting operation
        boolean success = executeCrafting(level);

        if (success) {
            craftedCount++;

            // Small delay between crafts (10 ticks = 0.5 seconds)
            if (ticksRunning % 10 != 0) {
                return;
            }
        } else {
            result = ActionResult.failure("Crafting execution failed", true);
            phase = CraftingPhase.COMPLETED;
        }
    }

    private boolean executeCrafting(ServerLevel level) {
        // Get recipe
        RecipeHolder<CraftingRecipe> holder = RecipeHelper.findRecipe(level, targetItem);
        if (holder == null) {
            return false;
        }

        CraftingRecipe recipe = holder.value();

        // Get required ingredients
        Map<Item, Integer> ingredients = RecipeHelper.getRequiredIngredients(level, targetItem);

        // Remove ingredients from Steve's inventory
        for (Map.Entry<Item, Integer> entry : ingredients.entrySet()) {
            if (!InventoryHelper.removeItem(steve, entry.getKey(), entry.getValue())) {
                // Failed to remove - shouldn't happen as we checked earlier
                return false;
            }
        }

        // Get result item stack
        ItemStack result = recipe.getResultItem(level.registryAccess()).copy();

        // Add result to inventory
        boolean added = InventoryHelper.addItem(steve, result);

        if (!added) {
            // Inventory full - restore ingredients
            for (Map.Entry<Item, Integer> entry : ingredients.entrySet()) {
                InventoryHelper.addItem(steve, new ItemStack(entry.getKey(), entry.getValue()));
            }
            return false;
        }

        return true;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        if (phase == CraftingPhase.CRAFTING && craftedCount > 0) {
            return "Crafting " + itemName + " (" + craftedCount + "/" + quantity + ")";
        }
        return "Craft " + quantity + "x " + itemName;
    }
}

