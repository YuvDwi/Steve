package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.CollaborativeBuildManager;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.StructureRegistry;
import com.steve.ai.structure.BlockPlacement;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BuildStructureAction extends BaseAction {

    private String structureType;
    private List<BlockPlacement> buildPlan;
    private int currentBlockIndex;
    private List<Block> buildMaterials;
    private int ticksRunning;
    private CollaborativeBuildManager.CollaborativeBuild collaborativeBuild; // For multi-Steve collaboration
    private boolean isCollaborative;
    private WarehouseRefillHandler refillHandler = new WarehouseRefillHandler();
    private static final int MAX_TICKS = 120000;

    public BuildStructureAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        structureType = task.getStringParameter("structure").toLowerCase();
        currentBlockIndex = 0;
        ticksRunning = 0;
        collaborativeBuild = CollaborativeBuildManager.findActiveBuild(structureType);
        if (collaborativeBuild != null) {
            isCollaborative = true;
            
            steve.setFlying(true);
            
            SteveMod.LOGGER.info("Steve '{}' JOINING collaborative build of '{}' ({}% complete) - FLYING & INVULNERABLE ENABLED", 
                steve.getSteveName(), structureType, collaborativeBuild.getProgressPercentage());
            
            buildMaterials = new ArrayList<>();
            buildMaterials.add(Blocks.OAK_PLANKS); // Default material
            buildMaterials.add(Blocks.COBBLESTONE);
            buildMaterials.add(Blocks.GLASS_PANE);
            
            return; // Skip structure generation, just join the existing build
        }
        
        isCollaborative = false;
        
        buildMaterials = new ArrayList<>();
        Object blocksParam = task.getParameter("blocks");
        if (blocksParam instanceof List) {
            List<?> blocksList = (List<?>) blocksParam;
            for (Object blockObj : blocksList) {
                Block block = parseBlock(blockObj.toString());
                if (block != Blocks.AIR) {
                    buildMaterials.add(block);
                }
            }
        }
        
        if (buildMaterials.isEmpty()) {
            String materialName = task.getStringParameter("material", "oak_planks");
            Block block = parseBlock(materialName);
            buildMaterials.add(block != Blocks.AIR ? block : Blocks.OAK_PLANKS);
        }
        
        Object dimensionsParam = task.getParameter("dimensions");
        int width = 9;  // Increased from 5
        int height = 6; // Increased from 4
        int depth = 9;  // Increased from 5
        
        if (dimensionsParam instanceof List) {
            List<?> dims = (List<?>) dimensionsParam;
            if (dims.size() >= 3) {
                width = ((Number) dims.get(0)).intValue();
                height = ((Number) dims.get(1)).intValue();
                depth = ((Number) dims.get(2)).intValue();
            }
        } else {
            width = task.getIntParameter("width", 5);
            height = task.getIntParameter("height", 4);
            depth = task.getIntParameter("depth", 5);
        }
        
        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        BlockPos groundPos;
        
        if (nearestPlayer != null) {
            net.minecraft.world.phys.Vec3 eyePos = nearestPlayer.getEyePosition(1.0F);
            net.minecraft.world.phys.Vec3 lookVec = nearestPlayer.getLookAngle();
            
            net.minecraft.world.phys.Vec3 targetPos = eyePos.add(lookVec.scale(12));
            
            BlockPos lookTarget = new BlockPos(
                (int)Math.floor(targetPos.x),
                (int)Math.floor(targetPos.y),
                (int)Math.floor(targetPos.z)
            );
            
            groundPos = findGroundLevel(lookTarget);
            
            if (groundPos == null) {
                groundPos = findGroundLevel(nearestPlayer.blockPosition().offset(
                    (int)Math.round(lookVec.x * 10),
                    0,
                    (int)Math.round(lookVec.z * 10)
                ));
            }
            
            SteveMod.LOGGER.info("Building in player's field of view at {} (looking from {} towards {})", 
                groundPos, eyePos, targetPos);
        } else {
            BlockPos buildPos = steve.blockPosition().offset(2, 0, 2);
            groundPos = findGroundLevel(buildPos);
        }
        
        if (groundPos == null) {
            result = ActionResult.failure("Cannot find suitable ground for building in your field of view");
            return;
        }
        
        SteveMod.LOGGER.info("Found ground at Y={} (Build starting at {})", groundPos.getY(), groundPos);
        
        BlockPos clearPos = groundPos;
        
        buildPlan = tryLoadFromTemplate(structureType, clearPos);

        if (buildPlan != null) {
            SteveMod.LOGGER.info("Loaded '{}' from NBT template with {} blocks", structureType, buildPlan.size());
        }
        
        if (buildPlan == null || buildPlan.isEmpty()) {
            result = ActionResult.failure("Cannot generate build plan for: " + structureType);
            return;
        }

        // Check if Steve has enough materials (skip in creative mode)
        boolean creative = SteveConfig.CREATIVE_MODE.get();
        if (!creative) {
            Map<Block, Integer> materialsNeeded = countMaterialsNeeded(buildPlan);
            for (Map.Entry<Block, Integer> entry : materialsNeeded.entrySet()) {
                Block block = entry.getKey();
                int needed = entry.getValue();
                int available = steve.getBlockCount(block);
                if (available < needed) {
                    SteveMod.LOGGER.warn("Steve '{}' needs {} {} but only has {} in inventory",
                        steve.getSteveName(), needed, block.getName().getString(), available);
                }
            }
        } else {
            SteveMod.LOGGER.info("Steve '{}' building in CREATIVE MODE (unlimited materials)", steve.getSteveName());
        }

        StructureRegistry.register(clearPos, width, height, depth, structureType);
        
        collaborativeBuild = CollaborativeBuildManager.findActiveBuild(structureType);
        
        if (collaborativeBuild != null) {
            isCollaborative = true;
            SteveMod.LOGGER.info("Steve '{}' JOINING existing {} collaborative build at {}", 
                steve.getSteveName(), structureType, collaborativeBuild.startPos);
        } else {
            List<BlockPlacement> collaborativeBlocks = new ArrayList<>();
            for (BlockPlacement bp : buildPlan) {
                collaborativeBlocks.add(new BlockPlacement(bp.pos, bp.block));
            }
            
            collaborativeBuild = CollaborativeBuildManager.registerBuild(structureType, collaborativeBlocks, clearPos);
            isCollaborative = true;
            SteveMod.LOGGER.info("Steve '{}' CREATED new {} collaborative build at {}", 
                steve.getSteveName(), structureType, clearPos);
        }
        
        steve.setFlying(true);
        
        SteveMod.LOGGER.info("Steve '{}' starting COLLABORATIVE build of {} at {} with {} blocks using materials: {} [FLYING ENABLED]", 
            steve.getSteveName(), structureType, clearPos, buildPlan.size(), buildMaterials);
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            result = ActionResult.failure("Building timeout");
            return;
        }

        if (refillHandler.isRefilling()) {
            boolean stillRefilling = refillHandler.tick(steve);
            if (!stillRefilling) {
                SteveMod.LOGGER.info("Steve '{}' warehouse refill complete, resuming build", steve.getSteveName());
            }
            return;
        }
        
        if (isCollaborative && collaborativeBuild != null) {
            if (collaborativeBuild.isComplete()) {
                CollaborativeBuildManager.completeBuild(collaborativeBuild.structureId);
                steve.setFlying(false);
                steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                result = ActionResult.success("Built " + structureType + " collaboratively!");
                return;
            }
            
            int buildDelay = SteveConfig.BUILD_TICK_DELAY.get();
            if (ticksRunning % buildDelay != 0) return;

            BlockPlacement placement =
                CollaborativeBuildManager.getNextBlock(collaborativeBuild, steve.getSteveName());

            if (placement == null) {
                if (ticksRunning % 20 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' has no more blocks! Build {}% complete",
                        steve.getSteveName(), collaborativeBuild.getProgressPercentage());
                }
            } else {
                BlockPos pos = placement.pos;

                // Check material (skip in creative mode)
                boolean creative = SteveConfig.CREATIVE_MODE.get();
                if (!creative) {
                    if (!steve.hasBlock(placement.block, 1)) {
                        Map<Block, Integer> needed = new HashMap<>();
                        needed.put(placement.block, 64);
                        boolean started = refillHandler.startRefill(steve, needed);
                        if (!started) {
                            if (ticksRunning % 60 == 0) {
                                SteveMod.LOGGER.warn("Steve '{}' has no {} and no warehouse available!",
                                    steve.getSteveName(), placement.block.getName().getString());
                            }
                        }
                    } else {
                        // Consume material from inventory
                        steve.removeBlockFromInventory(placement.block, 1);
                    }
                }

                double distance = Math.sqrt(steve.blockPosition().distSqr(pos));
                if (distance > 5) {
                    steve.teleportTo(pos.getX() + 2, pos.getY(), pos.getZ() + 2);
                    SteveMod.LOGGER.info("Steve '{}' teleported to block at {}", steve.getSteveName(), pos);
                }

                steve.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

                steve.swing(InteractionHand.MAIN_HAND, true);

                steve.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(placement.block.asItem()));

                BlockState blockState = placement.block.defaultBlockState();
                steve.level().setBlock(pos, blockState, 3);

                SteveMod.LOGGER.info("Steve '{}' PLACED BLOCK at {} - Total: {}/{}",
                    steve.getSteveName(), pos, collaborativeBuild.getBlocksPlaced(),
                    collaborativeBuild.getTotalBlocks());

                // Particles and sound
                if (steve.level() instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(
                        new BlockParticleOption(ParticleTypes.BLOCK, blockState),
                        pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        15, 0.4, 0.4, 0.4, 0.15
                    );

                    var soundType = blockState.getSoundType(steve.level(), pos, steve);
                    steve.level().playSound(null, pos, soundType.getPlaceSound(),
                        SoundSource.BLOCKS, 1.0f, soundType.getPitch());
                }
            }
            
            if (ticksRunning % 100 == 0 && collaborativeBuild.getBlocksPlaced() > 0) {
                int percentComplete = collaborativeBuild.getProgressPercentage();
                SteveMod.LOGGER.info("{} build progress: {}/{} ({}%) - {} Steves working", 
                    structureType, 
                    collaborativeBuild.getBlocksPlaced(), 
                    collaborativeBuild.getTotalBlocks(), 
                    percentComplete,
                    collaborativeBuild.participatingSteves.size());
            }
        } else {
            steve.setFlying(false);
            steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            result = ActionResult.failure("Build system error: not in collaborative mode");
        }
    }

    @Override
    protected void onCancel() {
        if (refillHandler.isRefilling()) {
            refillHandler.cancel(steve);
        }
        steve.setFlying(false);
        steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Build " + structureType + " (" + currentBlockIndex + "/" + (buildPlan != null ? buildPlan.size() : 0) + ")";
    }

    /**
     * Count how many of each block type is needed for the build plan
     */
    private Map<Block, Integer> countMaterialsNeeded(List<BlockPlacement> plan) {
        Map<Block, Integer> counts = new HashMap<>();
        for (BlockPlacement bp : plan) {
            counts.merge(bp.block, 1, Integer::sum);
        }
        return counts;
    }
    
    private Block getMaterial(int index) {
        return buildMaterials.get(index % buildMaterials.size());
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        ResourceLocation resourceLocation = new ResourceLocation(blockName);
        Block block = BuiltInRegistries.BLOCK.get(resourceLocation);
        return block != null ? block : Blocks.AIR;
    }
    
    /**
     * Find the actual ground level from a starting position
     * Scans downward to find solid ground, or upward if underground
     */
    private BlockPos findGroundLevel(BlockPos startPos) {
        int maxScanDown = 20; // Scan up to 20 blocks down
        int maxScanUp = 10;   // Scan up to 10 blocks up if we're underground
        
        // First, try scanning downward to find ground
        for (int i = 0; i < maxScanDown; i++) {
            BlockPos checkPos = startPos.below(i);
            BlockPos belowPos = checkPos.below();
            
            if (steve.level().getBlockState(checkPos).isAir() && 
                isSolidGround(belowPos)) {
                return checkPos; // This is ground level
            }
        }
        
        // Scan upward to find the surface
        for (int i = 1; i < maxScanUp; i++) {
            BlockPos checkPos = startPos.above(i);
            BlockPos belowPos = checkPos.below();
            
            if (steve.level().getBlockState(checkPos).isAir() && 
                isSolidGround(belowPos)) {
                return checkPos;
            }
        }
        
        // but make sure there's something solid below
        BlockPos fallbackPos = startPos;
        while (!isSolidGround(fallbackPos.below()) && fallbackPos.getY() > -64) {
            fallbackPos = fallbackPos.below();
        }
        
        return fallbackPos;
    }
    
    /**
     * Check if a position has solid ground suitable for building
     */
    private boolean isSolidGround(BlockPos pos) {
        var blockState = steve.level().getBlockState(pos);
        var block = blockState.getBlock();
        
        // Not solid if it's air or liquid
        if (blockState.isAir() || block == Blocks.WATER || block == Blocks.LAVA) {
            return false;
        }
        
        return blockState.isSolid();
    }
    
    /**
     * Find a suitable building site with flat, clear ground
     * Searches for an area that is:
     * - Relatively flat (max 2 block height difference)
     * - Clear of obstructions (trees, rocks, etc.)
     * - Has enough vertical space for the structure
     */
    private BlockPos findSuitableBuildingSite(BlockPos startPos, int width, int height, int depth) {
        int maxSearchRadius = 10;
        int searchStep = 3; // Small steps to stay nearby
        
        if (isAreaSuitable(startPos, width, height, depth)) {
            return startPos;
        }        // Search in expanding circles
        for (int radius = searchStep; radius < maxSearchRadius; radius += searchStep) {
            for (int angle = 0; angle < 360; angle += 45) { // Check every 45 degrees
                double radians = Math.toRadians(angle);
                int offsetX = (int) (Math.cos(radians) * radius);
                int offsetZ = (int) (Math.sin(radians) * radius);
                
                BlockPos testPos = new BlockPos(
                    startPos.getX() + offsetX,
                    startPos.getY(),
                    startPos.getZ() + offsetZ
                );
                
                BlockPos groundPos = findGroundLevel(testPos);
                if (groundPos != null && isAreaSuitable(groundPos, width, height, depth)) {
                    SteveMod.LOGGER.info("Found suitable flat ground at {} ({}m away)", groundPos, radius);
                    return groundPos;
                }
            }
        }
        
        SteveMod.LOGGER.warn("Could not find suitable flat ground within {}m", maxSearchRadius);
        return null;
    }
    
    /**
     * Check if an area is suitable for building
     * - Must be relatively flat (max 2 block height variation)
     * - Must be clear of obstructions above ground
     * - Must have solid ground below
     */
    private boolean isAreaSuitable(BlockPos startPos, int width, int height, int depth) {
        // Sample key points in the build area to check terrain
        int samples = 0;
        int maxSamples = 9; // Check 9 points (corners + center + midpoints)
        int unsuitable = 0;
        
        BlockPos[] checkPoints = {
            startPos,                                    // Front-left corner
            startPos.offset(width - 1, 0, 0),           // Front-right corner
            startPos.offset(0, 0, depth - 1),           // Back-left corner
            startPos.offset(width - 1, 0, depth - 1),   // Back-right corner
            startPos.offset(width / 2, 0, depth / 2),   // Center
            startPos.offset(width / 2, 0, 0),           // Front-center
            startPos.offset(width / 2, 0, depth - 1),   // Back-center
            startPos.offset(0, 0, depth / 2),           // Left-center
            startPos.offset(width - 1, 0, depth / 2)    // Right-center
        };
        
        int minY = startPos.getY();
        int maxY = startPos.getY();
        
        for (BlockPos checkPos : checkPoints) {
            samples++;
            
            if (!isSolidGround(checkPos.below())) {
                unsuitable++;
                continue;
            }
            
            BlockPos actualGround = findGroundLevel(checkPos);
            if (actualGround != null) {
                minY = Math.min(minY, actualGround.getY());
                maxY = Math.max(maxY, actualGround.getY());
            }
            
            for (int y = 1; y <= Math.min(height, 3); y++) {
                BlockPos abovePos = checkPos.above(y);
                var blockState = steve.level().getBlockState(abovePos);
                
                if (!blockState.isAir()) {
                    Block block = blockState.getBlock();
                    if (block != Blocks.GRASS && block != Blocks.TALL_GRASS && 
                        block != Blocks.FERN && block != Blocks.DEAD_BUSH &&
                        block != Blocks.DANDELION && block != Blocks.POPPY) {
                        unsuitable++;
                        break;
                    }
                }
            }
        }
        
        int heightVariation = maxY - minY;
        if (heightVariation > 2) {
            SteveMod.LOGGER.debug("Area at {} too uneven ({}m height difference)", startPos, heightVariation);
            return false;
        }
        
        // Area is suitable if less than 30% of samples are problematic
        boolean suitable = unsuitable < (maxSamples * 0.3);
        
        if (!suitable) {
            SteveMod.LOGGER.debug("Area at {} has too many obstructions ({}/{})", startPos, unsuitable, samples);
        }
        
        return suitable;
    }
    
    /**
     * Try to load structure from NBT template file
     * Returns null if no template found (falls back to procedural generation)
     */
    private List<BlockPlacement> tryLoadFromTemplate(String structureName, BlockPos startPos) {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        
        var template = StructureTemplateLoader.loadFromNBT(serverLevel, structureName);
        if (template == null) {
            return null;
        }
        
        List<BlockPlacement> blocks = new ArrayList<>();
        for (var templateBlock : template.blocks) {
            BlockPos worldPos = startPos.offset(templateBlock.relativePos);
            Block block = templateBlock.blockState.getBlock();
            blocks.add(new BlockPlacement(worldPos, block));
        }
        
        return blocks;
    }
    
    /**
     * Find the nearest player to build in front of
     */
    private net.minecraft.world.entity.player.Player findNearestPlayer() {
        java.util.List<? extends net.minecraft.world.entity.player.Player> players = steve.level().players();
        
        if (players.isEmpty()) {
            return null;
        }
        
        net.minecraft.world.entity.player.Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (net.minecraft.world.entity.player.Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            
            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        
        return nearest;
    }
    
}

