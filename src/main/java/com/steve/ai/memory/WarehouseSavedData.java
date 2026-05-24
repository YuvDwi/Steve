package com.steve.ai.memory;

import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WarehouseSavedData extends SavedData {

    private static final String DATA_NAME = "steve_warehouses";

    private final List<WarehouseEntry> entries = new ArrayList<>();

    public static class WarehouseEntry {
        public String name;
        public BlockPos pos;
        public boolean chestPlaced;
        public boolean nearPlayer;

        public WarehouseEntry(String name, BlockPos pos, boolean chestPlaced, boolean nearPlayer) {
            this.name = name;
            this.pos = pos;
            this.chestPlaced = chestPlaced;
            this.nearPlayer = nearPlayer;
        }
    }

    public WarehouseSavedData() {
    }

    public static WarehouseSavedData load(CompoundTag tag) {
        WarehouseSavedData data = new WarehouseSavedData();
        if (tag.contains("Warehouses", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Warehouses", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                String name = entry.getString("Name");
                BlockPos pos = new BlockPos(entry.getInt("X"), entry.getInt("Y"), entry.getInt("Z"));
                boolean placed = entry.getBoolean("Placed");
                boolean nearPlayer = entry.getBoolean("NearPlayer");
                data.entries.add(new WarehouseEntry(name, pos, placed, nearPlayer));
            }
        }
        return data;
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
        return tag;
    }

    public static WarehouseSavedData getOrCreate(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                WarehouseSavedData::load,
                WarehouseSavedData::new,
                DATA_NAME
        );
    }

    public void initFromConfig() {
        if (!entries.isEmpty()) return;

        for (WarehouseConfig.WarehouseDefinition def : WarehouseConfig.getWarehouses()) {
            BlockPos pos = new BlockPos(def.x, def.y, def.z);
            entries.add(new WarehouseEntry(def.name, pos, false, def.isNearPlayer()));
            SteveMod.LOGGER.info("Registered warehouse '{}' at {} (nearPlayer={})", def.name, pos, def.isNearPlayer());
        }
        setDirty();
    }

    public List<WarehouseEntry> getEntries() {
        return entries;
    }

    public Optional<BlockPos> findNearest(BlockPos from) {
        return entries.stream()
                .filter(e -> e.chestPlaced)
                .map(e -> e.pos)
                .min((a, b) -> Double.compare(a.distSqr(from), b.distSqr(from)));
    }

    public void markPlaced(String name) {
        for (WarehouseEntry entry : entries) {
            if (entry.name.equals(name)) {
                entry.chestPlaced = true;
                setDirty();
                return;
            }
        }
    }

    public boolean isRegistered(BlockPos pos) {
        return entries.stream().anyMatch(e -> e.pos.equals(pos));
    }
}
