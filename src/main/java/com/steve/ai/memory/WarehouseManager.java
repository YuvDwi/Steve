package com.steve.ai.memory;

import com.steve.ai.SteveMod;
import com.steve.ai.config.WarehouseConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WarehouseManager extends SavedData {

    private static final String DATA_NAME = "steve_warehouses";
    private static final int RESTOCK_COOLDOWN_TICKS = 6000;

    private final List<WarehouseEntry> entries = new ArrayList<>();
    private final Map<String, WarehouseEntry> entriesByName = new HashMap<>();
    private long lastRestockGameTime = Long.MIN_VALUE;

    private static class WarehouseEntry {
        private final String name;
        private BlockPos pos;
        private boolean chestPlaced;
        private final boolean nearPlayer;

        WarehouseEntry(String name, BlockPos pos, boolean chestPlaced, boolean nearPlayer) {
            this.name = name;
            this.pos = pos;
            this.chestPlaced = chestPlaced;
            this.nearPlayer = nearPlayer;
        }
    }

    public WarehouseManager() {
    }

    // ── SavedData persistence ──────────────────────────────────────────

    public static WarehouseManager load(CompoundTag tag) {
        WarehouseManager manager = new WarehouseManager();
        if (tag.contains("Warehouses", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Warehouses", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                manager.addEntry(new WarehouseEntry(
                        entry.getString("Name"),
                        new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z")),
                        entry.getBoolean("Placed"),
                        entry.getBoolean("NearPlayer")
                ));
            }
        }
        manager.lastRestockGameTime = tag.getLong("LastRestockTime");
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (WarehouseEntry entry : entries) {
            CompoundTag e = new CompoundTag();
            e.putString("Name", entry.name);
            e.putInt("X", entry.pos.getX());
            e.putInt("Y", entry.pos.getY());
            e.putInt("Z", entry.pos.getZ());
            e.putBoolean("Placed", entry.chestPlaced);
            e.putBoolean("NearPlayer", entry.nearPlayer);
            list.add(e);
        }
        tag.put("Warehouses", list);
        tag.putLong("LastRestockTime", lastRestockGameTime);
        return tag;
    }

    private static WarehouseManager getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                WarehouseManager::load,
                WarehouseManager::new,
                DATA_NAME
        );
    }

    private void addEntry(WarehouseEntry entry) {
        entries.add(entry);
        entriesByName.put(entry.name, entry);
    }

    private void initFromConfig() {
        if (!entries.isEmpty()) return;

        for (WarehouseConfig.WarehouseDefinition def : WarehouseConfig.getWarehouses()) {
            BlockPos pos = def.getFixedPos().orElse(BlockPos.ZERO);
            addEntry(new WarehouseEntry(def.name, pos, false, def.isNearPlayer()));
            SteveMod.LOGGER.info("Registered warehouse '{}' (nearPlayer={})", def.name, def.isNearPlayer());
        }
        setDirty();
    }

    // ── Public static API ──────────────────────────────────────────────

    public static void init(ServerLevel level) {
        WarehouseManager self = getOrCreate(level);
        self.initFromConfig();

        for (WarehouseEntry entry : self.entries) {
            if (!entry.chestPlaced && !entry.nearPlayer) {
                if (placeChest(level, entry.pos)) {
                    entry.chestPlaced = true;
                    self.setDirty();
                    SteveMod.LOGGER.info("Placed warehouse '{}' chest at {}", entry.name, entry.pos);
                }
            }
        }
    }

    public static void onPlayerJoined(ServerLevel level, Player player) {
        WarehouseManager self = getOrCreate(level);

        for (WarehouseEntry entry : self.entries) {
            if (!entry.chestPlaced && entry.nearPlayer) {
                BlockPos playerPos = player.blockPosition();
                BlockPos placePos = findAirNear(level, playerPos, 5);
                if (placePos == null) {
                    placePos = playerPos.above();
                }
                entry.pos = placePos;

                if (placeChest(level, placePos)) {
                    entry.chestPlaced = true;
                    SteveMod.LOGGER.info("Placed warehouse '{}' chest near player at {}", entry.name, placePos);
                }
                self.setDirty();
            }
        }
    }

    public static void autoRestockAll(ServerLevel level) {
        WarehouseManager self = getOrCreate(level);

        long gameTime = level.getGameTime();
        if (gameTime - self.lastRestockGameTime < RESTOCK_COOLDOWN_TICKS) return;
        self.lastRestockGameTime = gameTime;
        self.setDirty();

        for (WarehouseEntry entry : self.entries) {
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

    public static Optional<BlockPos> findNearest(ServerLevel level, BlockPos from) {
        WarehouseManager self = getOrCreate(level);
        return self.entries.stream()
                .filter(e -> e.chestPlaced)
                .map(e -> e.pos)
                .min((a, b) -> Double.compare(a.distSqr(from), b.distSqr(from)));
    }

    // ── Private helpers ────────────────────────────────────────────────

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

    private static boolean placeChest(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;

        BlockState current = level.getBlockState(pos);
        if (!current.isAir()) {
            SteveMod.LOGGER.warn("Cannot place warehouse chest at {}, block already exists: {}", pos, current);
            return false;
        }

        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        return true;
    }

    private static Container getChestContainer(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container container) {
            return container;
        }
        return null;
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
}
