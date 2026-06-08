package com.steve.ai.event.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The design doc text + material breakdown + raw block list are now available. */
public final class PlanDesignReadyEvent implements PlanEvent {
    private final String projectId;
    private final String design;
    private final List<MaterialEntry> materials;
    private final int totalBlocks;
    private final List<BlockEntry> blocks;
    private final Instant timestamp;

    public PlanDesignReadyEvent(String projectId, String design,
                                List<MaterialEntry> materials, int totalBlocks,
                                List<BlockEntry> blocks) {
        this.projectId = projectId;
        this.design = design;
        this.materials = materials == null ? List.of() : List.copyOf(materials);
        this.totalBlocks = totalBlocks;
        this.blocks = blocks == null ? List.of() : List.copyOf(blocks);
        this.timestamp = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public String getDesign() { return design; }
    public List<MaterialEntry> getMaterials() { return materials; }
    public int getTotalBlocks() { return totalBlocks; }
    public List<BlockEntry> getBlocks() { return blocks; }
    public Instant getTimestamp() { return timestamp; }

    /** Single block in the design, in world (relative-to-origin) coordinates.
     *  {@code blockId} is the namespace:path registry key of the block (e.g.
     *  {@code minecraft:oak_planks}) — kept as a string so the dashboard can
     *  deserialize without depending on Minecraft classpath. */
    public static final class BlockEntry {
        private final int x;
        private final int y;
        private final int z;
        private final String blockId;

        public BlockEntry(int x, int y, int z, String blockId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockId = blockId;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public String getBlockId() { return blockId; }
    }

    /** Material row for the dashboard UI. Keys from {@code BuildProject.materials}
     *  are flattened to a human-readable name + count + percent. */
    public static final class MaterialEntry {
        private final String name;
        private final int count;
        private final int percent;

        public MaterialEntry(String name, int count, int percent) {
            this.name = name;
            this.count = count;
            this.percent = percent;
        }

        public String getName() { return name; }
        public int getCount() { return count; }
        public int getPercent() { return percent; }

        public static List<MaterialEntry> fromBlockMap(Map<net.minecraft.world.level.block.Block, Integer> mat,
                                                       int total) {
            return mat.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    String name = e.getKey().getName().getString();
                    int n = e.getValue();
                    int pct = total > 0 ? (n * 100 / total) : 0;
                    return new MaterialEntry(name, n, pct);
                })
                .toList();
        }
    }
}
