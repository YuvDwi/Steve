package com.steve.ai.memory;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.Optional;

public class WarehouseManager {

    public static void init(ServerLevel level) {
        WarehouseSavedData data = WarehouseSavedData.getOrCreate(level);
        data.initFromConfig();

        for (WarehouseSavedData.WarehouseEntry entry : data.getEntries()) {
            if (!entry.chestPlaced && !entry.nearPlayer) {
                if (placeChest(level, entry.pos)) {
                    data.markPlaced(entry.name);
                    SteveMod.LOGGER.info("Placed warehouse '{}' chest at {}", entry.name, entry.pos);
                }
            }
        }
    }

    public static void onPlayerJoined(ServerLevel level, Player player) {
        WarehouseSavedData data = WarehouseSavedData.getOrCreate(level);

        for (WarehouseSavedData.WarehouseEntry entry : data.getEntries()) {
            if (!entry.chestPlaced && entry.nearPlayer) {
                BlockPos playerPos = player.blockPosition();
                BlockPos placePos = findAirNear(level, playerPos, 5);
                if (placePos == null) {
                    placePos = playerPos.above();
                }
                entry.pos = placePos;

                if (placeChest(level, placePos)) {
                    data.markPlaced(entry.name);
                    SteveMod.LOGGER.info("Placed warehouse '{}' chest near player at {}", entry.name, placePos);
                }
            }
        }
    }

    private static BlockPos findNearestPlayerPos(ServerLevel level) {
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player player : level.players()) {
            if (nearest == null || player.blockPosition().distSqr(BlockPos.ZERO) < nearestDist) {
                nearest = player;
                nearestDist = player.blockPosition().distSqr(BlockPos.ZERO);
            }
        }
        return nearest != null ? nearest.blockPosition() : null;
    }

    private static BlockPos findAirNear(ServerLevel level, BlockPos center, int radius) {
        for (int dy = 0; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos check = center.offset(dx, dy, dz);
                    if (level.isLoaded(check) && level.getBlockState(check).isAir()
                            && level.getBlockState(check.below()).isSolid()) {
                        return check;
                    }
                }
            }
        }
        return null;
    }

    public static boolean placeChest(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;

        BlockState current = level.getBlockState(pos);
        if (!current.isAir()) {
            SteveMod.LOGGER.warn("Cannot place warehouse chest at {}, block already exists: {}", pos, current);
            return false;
        }

        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        return true;
    }

    public static Container getChestContainer(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            return container;
        }
        return null;
    }

    public static int withdrawItem(ServerLevel level, BlockPos warehousePos,
                                    SteveEntity steve, Block block, int maxCount) {
        Container chest = getChestContainer(level, warehousePos);
        if (chest == null) return 0;

        ItemStack target = new ItemStack(block.asItem());
        int remaining = maxCount;

        for (int i = 0; i < chest.getContainerSize() && remaining > 0; i++) {
            ItemStack slot = chest.getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameTags(slot, target)) continue;

            int take = Math.min(remaining, slot.getCount());
            ItemStack extracted = slot.split(take);
            int notAdded = steve.addItemToInventory(extracted).getCount();

            if (notAdded > 0) {
                slot.grow(notAdded);
            }

            remaining -= (take - notAdded);
        }

        chest.setChanged();
        return maxCount - remaining;
    }

    public static int depositItem(ServerLevel level, BlockPos warehousePos,
                                   SteveEntity steve, Block block, int maxCount) {
        Container chest = getChestContainer(level, warehousePos);
        if (chest == null) return 0;

        ItemStack target = new ItemStack(block.asItem());
        int remaining = maxCount;

        for (int i = 0; i < steve.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack slot = steve.getInventory().getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameTags(slot, target)) continue;

            int take = Math.min(remaining, slot.getCount());
            ItemStack toDeposit = slot.copy();
            toDeposit.setCount(take);
            int notAdded = addItemToContainer(chest, toDeposit);
            int deposited = take - notAdded;

            slot.shrink(deposited);
            remaining -= deposited;
        }

        chest.setChanged();
        return maxCount - remaining;
    }

    public static void autoRestockAll(ServerLevel level) {
        WarehouseSavedData data = WarehouseSavedData.getOrCreate(level);

        for (WarehouseSavedData.WarehouseEntry entry : data.getEntries()) {
            if (!entry.chestPlaced) continue;

            Container chest = getChestContainer(level, entry.pos);
            if (chest == null) continue;

            WarehouseConfig.WarehouseDefinition def = WarehouseConfig.getWarehouse(entry.name);
            if (def == null) continue;

            for (Map.Entry<String, Integer> mat : def.materials.entrySet()) {
                restockMaterial(chest, mat.getKey(), mat.getValue());
            }
        }
    }

    private static void restockMaterial(Container chest, String materialId, int targetCount) {
        ResourceLocation rl = new ResourceLocation("minecraft", materialId);
        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == Blocks.AIR) return;

        ItemStack target = new ItemStack(block.asItem());
        int currentCount = countInContainer(chest, target);

        if (currentCount >= targetCount) return;

        int deficit = targetCount - currentCount;
        ItemStack toAdd = new ItemStack(block.asItem(), deficit);
        int notAdded = addItemToContainer(chest, toAdd);

        if (notAdded > 0) {
            SteveMod.LOGGER.debug("Warehouse restock: chest full, could not add all {} ({} left over)",
                    materialId, notAdded);
        }
    }

    private static int countInContainer(Container chest, ItemStack target) {
        int total = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack slot = chest.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, target)) {
                total += slot.getCount();
            }
        }
        return total;
    }

    private static int addItemToContainer(Container chest, ItemStack stack) {
        ItemStack remaining = stack.copy();

        for (int i = 0; i < chest.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = chest.getItem(i);
            if (slot.isEmpty()) {
                chest.setItem(i, remaining.copy());
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameTags(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int toAdd = Math.min(space, remaining.getCount());
                slot.grow(toAdd);
                remaining.shrink(toAdd);
            }
        }

        chest.setChanged();
        return remaining.getCount();
    }

    public static Optional<BlockPos> findNearest(ServerLevel level, BlockPos from) {
        WarehouseSavedData data = WarehouseSavedData.getOrCreate(level);
        return data.findNearest(from);
    }
}
