package com.steve.ai.action;

import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages collaborative building where multiple Steves work on DIFFERENT SECTIONS of the same structure
 */
public class CollaborativeBuildManager {

    public static class CollaborativeBuild {

        public final String structureId;
        public final List<BlockPlacement> buildPlan;
        private final List<BuildSection> sections;
        private final Map<String, Integer> steveToSectionMap;
        private final AtomicInteger nextSectionIndex;
        public final Set<String> participatingSteves;
        public final BlockPos startPos;

        public CollaborativeBuild(String structureId, List<BlockPlacement> buildPlan, BlockPos startPos) {
            this.structureId = structureId;
            this.buildPlan = buildPlan;
            this.participatingSteves = ConcurrentHashMap.newKeySet();
            this.startPos = startPos;
            this.steveToSectionMap = new ConcurrentHashMap<>();
            this.nextSectionIndex = new AtomicInteger(0);
            this.sections = divideBuildIntoSections(buildPlan);

            SteveMod.LOGGER.info(
                "Divided '{}' into {} sections for collaborative building",
                structureId,
                sections.size()
            );
        }

        /**
         * Divide the build into 4 QUADRANTS (NW, NE, SW, SE)
         * Each quadrant is sorted BOTTOM-TO-TOP so each Steve builds their quadrant from the ground up
         */
        private List<BuildSection> divideBuildIntoSections(List<BlockPlacement> plan) {
            if (plan.isEmpty()) {
                return new ArrayList<>();
            }

            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

            for (BlockPlacement placement : plan) {
                minX = Math.min(minX, placement.pos.getX());
                maxX = Math.max(maxX, placement.pos.getX());
                minZ = Math.min(minZ, placement.pos.getZ());
                maxZ = Math.max(maxZ, placement.pos.getZ());
            }

            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;

            List<BlockPlacement> northWest = new ArrayList<>();
            List<BlockPlacement> northEast = new ArrayList<>();
            List<BlockPlacement> southWest = new ArrayList<>();
            List<BlockPlacement> southEast = new ArrayList<>();

            for (BlockPlacement placement : plan) {
                int x = placement.pos.getX();
                int z = placement.pos.getZ();

                if (x <= centerX && z <= centerZ) {
                    northWest.add(placement);
                } else if (x > centerX && z <= centerZ) {
                    northEast.add(placement);
                } else if (x <= centerX && z > centerZ) {
                    southWest.add(placement);
                } else {
                    southEast.add(placement);
                }
            }

            Comparator<BlockPlacement> bottomToTop =
                Comparator.comparingInt(p -> p.pos.getY());

            northWest.sort(bottomToTop);
            northEast.sort(bottomToTop);
            southWest.sort(bottomToTop);
            southEast.sort(bottomToTop);

            List<BuildSection> sectionList = new ArrayList<>();
            if (!northWest.isEmpty()) sectionList.add(new BuildSection(0, northWest, "NORTH-WEST"));
            if (!northEast.isEmpty()) sectionList.add(new BuildSection(1, northEast, "NORTH-EAST"));
            if (!southWest.isEmpty()) sectionList.add(new BuildSection(2, southWest, "SOUTH-WEST"));
            if (!southEast.isEmpty()) sectionList.add(new BuildSection(3, southEast, "SOUTH-EAST"));

            SteveMod.LOGGER.info(
                "Divided structure into {} quadrants (BOTTOM-TO-TOP): NW={}, NE={}, SW={}, SE={} blocks",
                sectionList.size(),
                northWest.size(),
                northEast.size(),
                southWest.size(),
                southEast.size()
            );

            return sectionList;
        }

        public int getTotalBlocks() {
            return buildPlan.size();
        }

        public int getBlocksPlaced() {
            int total = 0;
            for (BuildSection section : sections) {
                total += section.getBlocksPlaced();
            }
            return total;
        }

        public boolean isComplete() {
            for (BuildSection section : sections) {
                if (!section.isComplete()) {
                    return false;
                }
            }
            return true;
        }

        public int getProgressPercentage() {
            return (getBlocksPlaced() * 100) / buildPlan.size();
        }
    }

    /**
     * A section of the build that one Steve works on (represents a spatial quadrant)
     */
    public static class BuildSection {

        public final int yLevel; // Used as section ID
        public final String sectionName;
        private final List<BlockPlacement> blocks;
        private final AtomicInteger nextBlockIndex;

        public BuildSection(int sectionId, List<BlockPlacement> blocks, String sectionName) {
            this.yLevel = sectionId;
            this.sectionName = sectionName;
            this.blocks = blocks;
            this.nextBlockIndex = new AtomicInteger(0);
        }

        public BlockPlacement getNextBlock() {
            int index = nextBlockIndex.getAndIncrement();
            if (index < blocks.size()) {
                return blocks.get(index);
            }
            return null;
        }

        public int getBlocksPlaced() {
            return Math.min(nextBlockIndex.get(), blocks.size());
        }

        public boolean isComplete() {
            return nextBlockIndex.get() >= blocks.size();
        }

        public int getTotalBlocks() {
            return blocks.size();
        }
    }

    public static class BlockPlacement {
        public final BlockPos pos;
        public final Block block;

        public BlockPlacement(BlockPos pos, Block block) {
            this.pos = pos;
            this.block = block;
        }
    }

    private static final Map<String, CollaborativeBuild> activeBuilds = new ConcurrentHashMap<>();

    /**
     * Register a new collaborative build project
     */
    public static CollaborativeBuild registerBuild(
        String structureType,
        List<BlockPlacement> buildPlan,
        BlockPos startPos
    ) {
        String structureId = structureType + "_" + System.currentTimeMillis();
        CollaborativeBuild build =
            new CollaborativeBuild(structureId, buildPlan, startPos);

        activeBuilds.put(structureId, build);

        SteveMod.LOGGER.info(
            "Registered collaborative build '{}' at {} with {} blocks",
            structureType,
            startPos,
            buildPlan.size()
        );

        return build;
    }

    /**
     * Get the next block for a Steve to place (each Steve works on their own section)
     * Returns null if Steve's section is complete
     *
     * ✔ Auto-cleanup when the build is complete
     */
    public static BlockPlacement getNextBlock(
        CollaborativeBuild build,
        String steveName
    ) {
        if (build.isComplete()) {
            // Auto-cleanup quando completo
            completeBuild(build.structureId);
            return null;
        }

        build.participatingSteves.add(steveName);

        Integer sectionIndex = build.steveToSectionMap.get(steveName);
        if (sectionIndex == null) {
            sectionIndex = assignSteveToSection(build, steveName);
            if (sectionIndex == null) {
                return null;
            }
        }

        BuildSection section = build.sections.get(sectionIndex);
        BlockPlacement block = section.getNextBlock();

        if (block == null) {
            if (sectionIndex != null) {
                section = build.sections.get(sectionIndex);
                block = section.getNextBlock();
                if (block != null) {
                }
            }
        }

        return block;
    }

    /**
     * Assign a Steve to a section (quadrant) that needs work
     */
    private static Integer assignSteveToSection(
        CollaborativeBuild build,
        String steveName
    ) {
        for (int i = 0; i < build.sections.size(); i++) {
            BuildSection section = build.sections.get(i);
            if (!section.isComplete()) {
                boolean alreadyAssigned =
                    build.steveToSectionMap.containsValue(i);

                if (!alreadyAssigned) {
                    build.steveToSectionMap.put(steveName, i);
                    SteveMod.LOGGER.info(
                        "Assigned Steve '{}' to {} quadrant - will build {} blocks BOTTOM-TO-TOP",
                        steveName,
                        section.sectionName,
                        section.getTotalBlocks()
                    );
                    return i;
                }
            }
        }

        for (int i = 0; i < build.sections.size(); i++) {
            BuildSection section = build.sections.get(i);
            if (!section.isComplete()) {
                build.steveToSectionMap.put(steveName, i);
                SteveMod.LOGGER.info(
                    "Steve '{}' helping with {} quadrant ({} blocks remaining)",
                    steveName,
                    section.sectionName,
                    section.getTotalBlocks() - section.getBlocksPlaced()
                );
                return i;
            }
        }

        return null;
    }

    public static CollaborativeBuild getBuild(String structureId) {
        return activeBuilds.get(structureId);
    }

    public static void completeBuild(String structureId) {
        CollaborativeBuild build = activeBuilds.remove(structureId);
        if (build != null) {
            SteveMod.LOGGER.info(
                "Collaborative build '{}' completed by {} Steves",
                structureId,
                build.participatingSteves.size()
            );
        }
    }

    public static CollaborativeBuild findActiveBuild(String structureType) {
        for (CollaborativeBuild build : activeBuilds.values()) {
            if (build.structureId.startsWith(structureType)
                && !build.isComplete()) {
                return build;
            }
        }
        return null;
    }

    public static void cleanupCompletedBuilds() {
        activeBuilds.entrySet().removeIf(
            entry -> entry.getValue().isComplete()
        );
    }
}
