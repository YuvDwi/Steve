package com.steve.ai.util;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.TierSortingRegistry;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Inventory management utility for Steve entities
 * Provides comprehensive inventory operations including:
 * - Item queries (checking existence, counting)
 * - Item manipulation (add, remove, transfer)
 * - Tool management (selection, durability checking)
 * - Chest operations (finding, storing, retrieving)
 */
public class InventoryHelper {

    /**
     * Check if Steve has at least the specified count of an item
     * @param steve The Steve entity
     * @param item The item to check for
     * @param count Minimum count required
     * @return true if Steve has >= count of the item
     */
    public static boolean hasItem(SteveEntity steve, Item item, int count) {
        return getItemCount(steve, item) >= count;
    }

    /**
     * Count total number of a specific item in Steve's inventory
     * @param steve The Steve entity
     * @param item The item to count
     * @return Total count across all stacks
     */
    public static int getItemCount(SteveEntity steve, Item item) {
        ItemStackHandler inventory = steve.getInventory();
        int totalCount = 0;

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                totalCount += stack.getCount();
            }
        }

        return totalCount;
    }

    /**
     * Add an item stack to Steve's inventory
     * @param steve The Steve entity
     * @param stack The item stack to add
     * @return true if successfully added (or partially added), false if inventory full
     */
    public static boolean addItem(SteveEntity steve, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        ItemStackHandler inventory = steve.getInventory();
        ItemStack remaining = stack.copy();

        // Try to merge with existing stacks first
        for (int i = 0; i < inventory.getSlots() && !remaining.isEmpty(); i++) {
            remaining = inventory.insertItem(i, remaining, false);
        }

        // Return true if we managed to insert at least some items
        return remaining.getCount() < stack.getCount();
    }

    /**
     * Remove a specific quantity of an item from Steve's inventory
     * @param steve The Steve entity
     * @param item The item to remove
     * @param count Number to remove
     * @return true if successfully removed the full count
     */
    public static boolean removeItem(SteveEntity steve, Item item, int count) {
        ItemStackHandler inventory = steve.getInventory();
        int remaining = count;

        for (int i = 0; i < inventory.getSlots() && remaining > 0; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int toRemove = Math.min(remaining, stack.getCount());
                inventory.extractItem(i, toRemove, false);
                remaining -= toRemove;
            }
        }

        return remaining == 0;
    }

    /**
     * Calculate inventory fullness as a percentage
     * @param steve The Steve entity
     * @return Value between 0.0 (empty) and 1.0 (full)
     */
    public static float getInventoryFullness(SteveEntity steve) {
        ItemStackHandler inventory = steve.getInventory();
        int usedSlots = 0;

        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                usedSlots++;
            }
        }

        return (float) usedSlots / inventory.getSlots();
    }

    /**
     * Check if inventory is considered full (>90% full)
     * @param steve The Steve entity
     * @return true if inventory is mostly full
     */
    public static boolean isInventoryFull(SteveEntity steve) {
        return getInventoryFullness(steve) > 0.9f;
    }

    /**
     * Find the best tool in inventory for breaking a specific block
     * @param steve The Steve entity
     * @param blockState The block to break
     * @return The best tool ItemStack, or ItemStack.EMPTY if none found
     */
    @Nullable
    public static ItemStack findBestTool(SteveEntity steve, BlockState blockState) {
        ItemStackHandler inventory = steve.getInventory();
        ItemStack bestTool = ItemStack.EMPTY;
        float bestSpeed = 1.0f;

        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            float speed = stack.getDestroySpeed(blockState);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestTool = stack;
            }
        }

        return bestTool;
    }

    /**
     * Check if a tool needs repair (durability < 10%)
     * @param tool The tool to check
     * @return true if durability is low
     */
    public static boolean needsToolRepair(ItemStack tool) {
        if (!tool.isDamageableItem()) {
            return false;
        }

        int maxDurability = tool.getMaxDamage();
        int currentDurability = maxDurability - tool.getDamageValue();

        return currentDurability < (maxDurability * 0.1);
    }

    /**
     * Find the nearest chest block within a radius
     * @param level The level/world
     * @param center Center position to search from
     * @param radius Search radius
     * @return BlockPos of nearest chest, or null if none found
     */
    @Nullable
    public static BlockPos findNearestChest(Level level, BlockPos center, int radius) {
        BlockPos nearestChest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (state.is(Blocks.CHEST) || state.is(Blocks.BARREL)) {
                        double distance = center.distSqr(pos);
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearestChest = pos;
                        }
                    }
                }
            }
        }

        return nearestChest;
    }

    /**
     * Transfer items from Steve's inventory to a chest
     * @param steve The Steve entity
     * @param chestPos Position of the chest
     * @param item The item to transfer (null = transfer all)
     * @return Number of items transferred
     */
    public static int transferToChest(SteveEntity steve, BlockPos chestPos, @Nullable Item item) {
        Level level = steve.level();
        BlockEntity blockEntity = level.getBlockEntity(chestPos);

        if (!(blockEntity instanceof Container container)) {
            return 0;
        }

        ItemStackHandler steveInventory = steve.getInventory();
        int transferred = 0;

        for (int i = 0; i < steveInventory.getSlots(); i++) {
            ItemStack stack = steveInventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (item != null && stack.getItem() != item) continue;

            // Try to add to chest
            for (int j = 0; j < container.getContainerSize(); j++) {
                ItemStack chestStack = container.getItem(j);

                if (chestStack.isEmpty()) {
                    // Empty slot - place entire stack
                    container.setItem(j, stack.copy());
                    transferred += stack.getCount();
                    steveInventory.setStackInSlot(i, ItemStack.EMPTY);
                    break;
                } else if (ItemStack.isSameItemSameTags(chestStack, stack)) {
                    // Merge with existing stack
                    int maxStack = chestStack.getMaxStackSize();
                    int canAdd = Math.min(maxStack - chestStack.getCount(), stack.getCount());

                    if (canAdd > 0) {
                        chestStack.grow(canAdd);
                        stack.shrink(canAdd);
                        transferred += canAdd;
                        container.setItem(j, chestStack);

                        if (stack.isEmpty()) {
                            steveInventory.setStackInSlot(i, ItemStack.EMPTY);
                            break;
                        }
                    }
                }
            }
        }

        container.setChanged();
        return transferred;
    }

    /**
     * Retrieve items from a chest to Steve's inventory
     * @param steve The Steve entity
     * @param chestPos Position of the chest
     * @param item The item to retrieve
     * @param count Number of items to retrieve
     * @return Number of items actually retrieved
     */
    public static int retrieveFromChest(SteveEntity steve, BlockPos chestPos, Item item, int count) {
        Level level = steve.level();
        BlockEntity blockEntity = level.getBlockEntity(chestPos);

        if (!(blockEntity instanceof Container container)) {
            return 0;
        }

        int remaining = count;

        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || stack.getItem() != item) continue;

            int toTake = Math.min(remaining, stack.getCount());
            ItemStack taken = stack.split(toTake);

            if (addItem(steve, taken)) {
                remaining -= toTake;
                container.setItem(i, stack);
            } else {
                // Couldn't add - put back
                stack.grow(toTake);
                container.setItem(i, stack);
                break;
            }
        }

        container.setChanged();
        return count - remaining;
    }

    /**
     * Check if Steve has the required tool tier for a block
     * @param steve The Steve entity
     * @param blockState The block to check
     * @return true if Steve has appropriate tool
     */
    public static boolean hasRequiredTool(SteveEntity steve, BlockState blockState) {
        ItemStack bestTool = findBestTool(steve, blockState);
        if (bestTool.isEmpty()) {
            return blockState.requiresCorrectToolForDrops() == false;
        }

        return bestTool.isCorrectToolForDrops(blockState);
    }

    /**
     * Get count of empty slots in inventory
     * @param steve The Steve entity
     * @return Number of empty slots
     */
    public static int getEmptySlotCount(SteveEntity steve) {
        ItemStackHandler inventory = steve.getInventory();
        int emptySlots = 0;

        for (int i = 0; i < inventory.getSlots(); i++) {
            if (inventory.getStackInSlot(i).isEmpty()) {
                emptySlots++;
            }
        }

        return emptySlots;
    }
}

