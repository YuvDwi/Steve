package com.steve.ai.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class WarehouseConfig {

    public static class WarehouseDefinition {
        public final String name;
        public final String spawn; // "fixed" or "near_player"
        public final int x, y, z;
        public final Map<String, Integer> materials;

        public WarehouseDefinition(String name, String spawn, int x, int y, int z, Map<String, Integer> materials) {
            this.name = name;
            this.spawn = spawn;
            this.x = x;
            this.y = y;
            this.z = z;
            this.materials = materials;
        }

        public boolean isNearPlayer() {
            return "near_player".equalsIgnoreCase(spawn);
        }
    }

    private static List<WarehouseDefinition> warehouses = new ArrayList<>();

    public static List<WarehouseDefinition> getWarehouses() {
        return warehouses;
    }

    public static WarehouseDefinition getWarehouse(String name) {
        return warehouses.stream()
                .filter(w -> w.name.equals(name))
                .findFirst()
                .orElse(null);
    }

    public static void load() {
        File configFile = FMLPaths.CONFIGDIR.get().resolve("steve/warehouses.json").toFile();

        if (!configFile.exists()) {
            copyDefaultConfig(configFile);
        }

        if (!configFile.exists()) {
            SteveMod.LOGGER.info("No warehouses.json found, no warehouses configured");
            return;
        }

        try {
            String json = Files.readString(configFile.toPath());
            parseJson(json);
            SteveMod.LOGGER.info("Loaded {} warehouse(s) from config", warehouses.size());
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to load warehouses.json", e);
        }
    }

    private static void parseJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray warehouseArray = root.getAsJsonArray("warehouses");

        warehouses.clear();

        for (JsonElement element : warehouseArray) {
            JsonObject obj = element.getAsJsonObject();
            String name = obj.get("name").getAsString();
            String spawn = obj.has("spawn") ? obj.get("spawn").getAsString() : "fixed";
            int x = obj.has("x") ? obj.get("x").getAsInt() : 0;
            int y = obj.has("y") ? obj.get("y").getAsInt() : 64;
            int z = obj.has("z") ? obj.get("z").getAsInt() : 0;

            Map<String, Integer> materials = new HashMap<>();
            JsonObject matsObj = obj.getAsJsonObject("materials");
            for (Map.Entry<String, JsonElement> entry : matsObj.entrySet()) {
                materials.put(entry.getKey(), entry.getValue().getAsInt());
            }

            warehouses.add(new WarehouseDefinition(name, spawn, x, y, z, materials));
        }
    }

    private static void copyDefaultConfig(File targetFile) {
        try (InputStream in = WarehouseConfig.class.getClassLoader().getResourceAsStream("warehouses.json")) {
            if (in == null) {
                SteveMod.LOGGER.warn("Default warehouses.json not found in resources");
                return;
            }
            targetFile.getParentFile().mkdirs();
            Files.copy(in, targetFile.toPath());
            SteveMod.LOGGER.info("Created default warehouses.json at {}", targetFile);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to copy default warehouses.json", e);
        }
    }
}
