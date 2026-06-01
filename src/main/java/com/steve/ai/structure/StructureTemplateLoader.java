package com.steve.ai.structure;

import com.steve.ai.SteveMod;
import com.steve.ai.mcp.MCPClientWrapper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads Minecraft structure templates from NBT files for sequential block-by-block placement
 */
public class StructureTemplateLoader {

    public static class TemplateBlock {
        public final BlockPos relativePos;
        public final BlockState blockState;

        public TemplateBlock(BlockPos relativePos, BlockState blockState) {
            this.relativePos = relativePos;
            this.blockState = blockState;
        }
    }

    public static class LoadedTemplate {
        public final String name;
        public final List<TemplateBlock> blocks;
        public final int width;
        public final int height;
        public final int depth;

        public LoadedTemplate(String name, List<TemplateBlock> blocks, int width, int height, int depth) {
            this.name = name;
            this.blocks = blocks;
            this.width = width;
            this.height = height;
            this.depth = depth;
        }
    }

    /**
     * Load a structure from an NBT file from resources
     */
    public static LoadedTemplate loadFromNBT(ServerLevel level, String structureName) {
        File structuresDir = FMLPaths.CONFIGDIR.get().resolve("steve/structures").toFile();
        String[] possibleNames = {
                structureName + ".nbt",
                structureName.toLowerCase().replace(" ", "_") + ".nbt",
                structureName.replaceAll("(\\w)(\\p{Upper})", "$1_$2").toLowerCase() + ".nbt"
        };

        for (String fileName : possibleNames) {
            File file = new File(structuresDir, fileName);
            if (file.exists()) {
                SteveMod.LOGGER.info("Found structure: {}", file);
                return loadFromFile(file, structureName);
            }
        }

        SteveMod.LOGGER.warn("Structure '{}' not found in {}. Available structures: {}",
                structureName, structuresDir, getAvailableStructures());
        return null;
    }

    /**
     * Load from a custom NBT file
     */
    private static LoadedTemplate loadFromFile(File file, String name) {
        try (InputStream inputStream = new FileInputStream(file)) {
            CompoundTag nbt = NbtIo.readCompressed(inputStream);
            return parseNBTStructure(nbt, name);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load structure from file: {}", file, e);
            return null;
        }
    }

    /**
     * Load from Minecraft's native StructureTemplate
     * Note: This is a simplified version that works with NBT directly
     */
    private static LoadedTemplate loadFromMinecraftTemplate(StructureTemplate template, String name) {
        List<TemplateBlock> blocks = new ArrayList<>();

        var size = template.getSize();
        int width = size.getX();
        int height = size.getY();
        int depth = size.getZ();

        // This method is here for future compatibility with Minecraft's template system

        SteveMod.LOGGER.warn("Direct template loading not fully implemented, please use NBT files directly");
        return null;
    }

    /**
     * Parse a structure from raw NBT data
     */
    private static LoadedTemplate parseNBTStructure(CompoundTag nbt, String name) {
        List<TemplateBlock> blocks = new ArrayList<>();

        var sizeList = nbt.getList("size", 3); // 3 = TAG_Int
        int width = sizeList.getInt(0);
        int height = sizeList.getInt(1);
        int depth = sizeList.getInt(2);

        var paletteList = nbt.getList("palette", 10); // 10 = TAG_Compound
        List<BlockState> palette = new ArrayList<>();

        for (int i = 0; i < paletteList.size(); i++) {
            CompoundTag blockTag = paletteList.getCompound(i);
            String blockName = blockTag.getString("Name");

            try {
                ResourceLocation blockLocation = new ResourceLocation(blockName);
                Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockLocation);
                palette.add(block.defaultBlockState());
            } catch (Exception e) {
                SteveMod.LOGGER.warn("Unknown block in structure: {}", blockName);
                palette.add(Blocks.AIR.defaultBlockState());
            }
        }

        var blocksList = nbt.getList("blocks", 10);
        for (int i = 0; i < blocksList.size(); i++) {
            CompoundTag blockTag = blocksList.getCompound(i);

            int paletteIndex = blockTag.getInt("state");
            var posList = blockTag.getList("pos", 3);

            BlockPos pos = new BlockPos(
                    posList.getInt(0),
                    posList.getInt(1),
                    posList.getInt(2)
            );

            BlockState state = palette.get(paletteIndex);
            if (!state.isAir()) {
                blocks.add(new TemplateBlock(pos, state));
            }
        }

        SteveMod.LOGGER.info("Loaded {} blocks from NBT '{}' ({}x{}x{})", blocks.size(), name, width, height, depth);
        return new LoadedTemplate(name, blocks, width, height, depth);
    }

    /**
     * Get list of available structure templates from both classpath and file system
     */
    public static List<String> getAvailableStructures() {
        List<String> structures = new ArrayList<>();

        File structuresDir = FMLPaths.CONFIGDIR.get().resolve("steve/structures").toFile();
        if (structuresDir.exists() && structuresDir.isDirectory()) {
            File[] files = structuresDir.listFiles((dir, name) -> name.endsWith(".nbt"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName().replace(".nbt", "");
                    structures.add(name);
                    registerStructureToMempalace(file, name);
                }
            }
        }

        SteveMod.LOGGER.info("Available NBT structures: {}", structures);
        return structures;
    }

    /**
     * Register structure template to mempalace
     */
    private static void registerStructureToMempalace(File file, String name) {
        try {
            LoadedTemplate template = loadFromFile(file, name);
            if (template == null) return;

            MCPClientWrapper client = new MCPClientWrapper("mempalace", "http://localhost:6060");
            client.initialize();

            String content = String.format("Structure '%s' %dx%dx%d with %d blocks",
                template.name, template.width, template.height, template.depth, template.blocks.size());

            client.callTool("mempalace_add_drawer", Map.of(
                "wing", "structure_templates",
                "room", template.name,
                "content", content,
                "added_by", "steve-ai"
            ));

            client.close();
            SteveMod.LOGGER.info("Registered structure template '{}' to mempalace", template.name);
        } catch (Exception e) {
            SteveMod.LOGGER.warn("Failed to register structure to mempalace: {}", e.getMessage());
        }
    }
}

