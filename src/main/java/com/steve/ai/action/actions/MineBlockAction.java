package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MineBlockAction extends BaseAction {
    private Block targetBlock;
    private int targetQuantity;
    private int minedCount;
    private BlockPos currentTarget;
    private int searchRadius = 8; // Small search radius - stay near player
    private int ticksRunning;
    private int ticksSinceLastTorch = 0;
    private BlockPos miningStartPos; // Fixed mining spot in front of player
    private BlockPos currentTunnelPos; // Current position in the tunnel
    private int miningDirectionX = 0; // Direction to mine (-1, 0, or 1)
    private int miningDirectionZ = 0; // Direction to mine (-1, 0, or 1)
    private int ticksSinceLastMine = 0; // Delay between mining blocks
    private static final int MAX_TICKS = 24000; // 20 minutes for deep mining
    private static final int TORCH_INTERVAL = 100; // Place torch every 5 seconds (100 ticks)
    private static final int MIN_LIGHT_LEVEL = 8;
    private static final int MINING_DELAY = 10;
    private static final int MAX_MINING_RADIUS = 5;
    
    // Ore depth mappings for intelligent mining
    private static final Map<String, Integer> ORE_DEPTHS = new HashMap<>() {{
        put("iron_ore", 64);  // Iron spawns well at Y=64 and below
        put("deepslate_iron_ore", -16); // Deep iron
        put("coal_ore", 96);
        put("copper_ore", 48);
        put("gold_ore", 32);
        put("deepslate_gold_ore", -16);
        put("diamond_ore", -59);
        put("deepslate_diamond_ore", -59);
        put("redstone_ore", 16);
        put("deepslate_redstone_ore", -32);
        put("lapis_ore", 0);
        put("deepslate_lapis_ore", -16);
        put("emerald_ore", 256); // Mountain biomes
    }};

    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        targetQuantity = task.getIntParameter("quantity", 8); // Mine reasonable amount by default
        minedCount = 0;
        ticksRunning = 0;
        ticksSinceLastTorch = 0;
        ticksSinceLastMine = 0;
        
        targetBlock = parseBlock(blockName);
        
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }
        
        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        if (nearestPlayer != null) {
            net.minecraft.world.phys.Vec3 eyePos = nearestPlayer.getEyePosition(1.0F);
            net.minecraft.world.phys.Vec3 lookVec = nearestPlayer.getLookAngle();
            
            double angle = Math.atan2(lookVec.z, lookVec.x) * 180.0 / Math.PI;
            angle = (angle + 360) % 360;
            
            if (angle >= 315 || angle < 45) {
                miningDirectionX = 1; miningDirectionZ = 0; // East (+X)
            } else if (angle >= 45 && angle < 135) {
                miningDirectionX = 0; miningDirectionZ = 1; // South (+Z)
            } else if (angle >= 135 && angle < 225) {
                miningDirectionX = -1; miningDirectionZ = 0; // West (-X)
            } else {
                miningDirectionX = 0; miningDirectionZ = -1; // North (-Z)
            }
            
            net.minecraft.world.phys.Vec3 targetPos = eyePos.add(lookVec.scale(3));
            
            BlockPos lookTarget = new BlockPos(
                (int)Math.floor(targetPos.x),
                (int)Math.floor(targetPos.y),
                (int)Math.floor(targetPos.z)
            );
            
            miningStartPos = lookTarget;
            for (int y = lookTarget.getY(); y > lookTarget.getY() - 20 && y > -64; y--) {
                BlockPos groundCheck = new BlockPos(lookTarget.getX(), y, lookTarget.getZ());
                if (steve.level().getBlockState(groundCheck).isSolid()) {
                    miningStartPos = groundCheck.above(); // Stand on top of solid block
                    break;
                }
            }
            
            currentTunnelPos = miningStartPos;
            steve.teleportTo(miningStartPos.getX() + 0.5, miningStartPos.getY(), miningStartPos.getZ() + 0.5);
            
            String[] dirNames = {"North", "East", "South", "West"};
            int dirIndex = miningDirectionZ == -1 ? 0 : (miningDirectionX == 1 ? 1 : (miningDirectionZ == 1 ? 2 : 3));
            SteveMod.LOGGER.info("Steve '{}' mining {} in ONE direction: {}", 
                steve.getSteveName(), targetBlock.getName().getString(), dirNames[dirIndex]);
        } else {
            miningStartPos = steve.blockPosition();
            currentTunnelPos = miningStartPos;
            miningDirectionX = 1; // Default to East
            miningDirectionZ = 0;
        }
        
        steve.setFlying(true);
        
        equipIronPickaxe();
        
        SteveMod.LOGGER.info("Steve '{}' mining {} - staying at {} [SLOW & VISIBLE]", 
            steve.getSteveName(), targetBlock.getName().getString(), miningStartPos);
        
        // Look for ore nearby
        findNextBlock();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastTorch++;
        ticksSinceLastMine++;
        
        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            result = ActionResult.failure("Mining timeout - only found " + minedCount + " blocks");
            return;
        }
        
        if (ticksSinceLastTorch >= TORCH_INTERVAL) {
            placeTorchIfDark();
            ticksSinceLastTorch = 0;
        }
        
        if (ticksSinceLastMine < MINING_DELAY) {
            return; // Still waiting
        }
        
        if (currentTarget == null) {
            findNextBlock();
            
            if (currentTarget == null) {
                if (minedCount >= targetQuantity) {
                    // Found enough ore, mission accomplished
                    steve.setFlying(false);
                    steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                    return;
                } else {
                    mineNearbyBlock();
                    return;
                }
            }
        }
        
        if (steve.level().getBlockState(currentTarget).getBlock() == targetBlock) {
            steve.teleportTo(currentTarget.getX() + 0.5, currentTarget.getY(), currentTarget.getZ() + 0.5);

            steve.swing(InteractionHand.MAIN_HAND, true);

            // Mine block and collect drops into inventory
            mineBlockAndCollect(currentTarget);
            minedCount++;
            ticksSinceLastMine = 0; // Reset delay timer

            // Check if inventory is getting full
            if (InventoryHelper.isInventoryFull(steve)) {
                SteveMod.LOGGER.warn("Steve '{}' inventory is >90% full during mining!", steve.getSteveName());
                // TODO: Auto-storage will be added in next task
            }

            SteveMod.LOGGER.info("Steve '{}' moved to ore and mined {} at {} - Total: {}/{}",
                steve.getSteveName(), targetBlock.getName().getString(), currentTarget,
                minedCount, targetQuantity);

            if (minedCount >= targetQuantity) {
                steve.setFlying(false);
                steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                return;
            }

            currentTarget = null;
        } else {
            currentTarget = null;
        }
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false);
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return "Mine " + targetQuantity + " " + targetBlock.getName().getString() + " (" + minedCount + " found)";
    }

    /**
     * Check light level and place torch if too dark
     */
    private void placeTorchIfDark() {
        BlockPos stevePos = steve.blockPosition();
        int lightLevel = steve.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, stevePos);
        
        if (lightLevel < MIN_LIGHT_LEVEL) {
            BlockPos torchPos = findTorchPosition(stevePos);
            
            if (torchPos != null && steve.level().getBlockState(torchPos).isAir()) {
                steve.level().setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
                SteveMod.LOGGER.info("Steve '{}' placed torch at {} (light level was {})", 
                    steve.getSteveName(), torchPos, lightLevel);
                
                steve.swing(InteractionHand.MAIN_HAND, true);
            }
        }
    }
    
    /**
     * Find a good position to place a torch (on floor or wall)
     */
    private BlockPos findTorchPosition(BlockPos center) {
        BlockPos floorPos = center.below();
        if (steve.level().getBlockState(floorPos).isSolid() && 
            steve.level().getBlockState(center).isAir()) {
            return center;
        }
        
        BlockPos[] wallPositions = {
            center.north(), center.south(), center.east(), center.west()
        };
        
        for (BlockPos wallPos : wallPositions) {
            if (steve.level().getBlockState(wallPos).isSolid() && 
                steve.level().getBlockState(center).isAir()) {
                return center;
            }
        }
        
        return null;
    }

    /**
     * Mine forward in ONE DIRECTION - creates a straight tunnel!
     * Steve progresses forward block by block
     */
    private void mineNearbyBlock() {
        BlockPos centerPos = currentTunnelPos;
        BlockPos abovePos = centerPos.above();
        BlockPos belowPos = centerPos.below();
        
        BlockState centerState = steve.level().getBlockState(centerPos);
        if (!centerState.isAir() && centerState.getBlock() != Blocks.BEDROCK) {
            steve.teleportTo(centerPos.getX() + 0.5, centerPos.getY(), centerPos.getZ() + 0.5);
            steve.swing(InteractionHand.MAIN_HAND, true);
            mineBlockAndCollect(centerPos);
            SteveMod.LOGGER.info("Steve '{}' mining tunnel at {}", steve.getSteveName(), centerPos);
        }

        BlockState aboveState = steve.level().getBlockState(abovePos);
        if (!aboveState.isAir() && aboveState.getBlock() != Blocks.BEDROCK) {
            steve.swing(InteractionHand.MAIN_HAND, true);
            mineBlockAndCollect(abovePos);
        }

        BlockState belowState = steve.level().getBlockState(belowPos);
        if (!belowState.isAir() && belowState.getBlock() != Blocks.BEDROCK) {
            steve.swing(InteractionHand.MAIN_HAND, true);
            mineBlockAndCollect(belowPos);
        }
        
        currentTunnelPos = currentTunnelPos.offset(miningDirectionX, 0, miningDirectionZ);
        
        ticksSinceLastMine = 0; // Reset delay
    }

    /**
     * Find ore blocks in the tunnel ahead
     * Searches forward in the mining direction
     */
    private void findNextBlock() {
        List<BlockPos> foundBlocks = new ArrayList<>();
        
        for (int distance = 0; distance < 20; distance++) {
            BlockPos checkPos = currentTunnelPos.offset(miningDirectionX * distance, 0, miningDirectionZ * distance);
            
            for (int y = -1; y <= 1; y++) {
                BlockPos orePos = checkPos.offset(0, y, 0);
                if (steve.level().getBlockState(orePos).getBlock() == targetBlock) {
                    foundBlocks.add(orePos);
                }
            }
        }
        
        if (!foundBlocks.isEmpty()) {
            currentTarget = foundBlocks.stream()
                .min((a, b) -> Double.compare(a.distSqr(currentTunnelPos), b.distSqr(currentTunnelPos)))
                .orElse(null);
            
            if (currentTarget != null) {
                SteveMod.LOGGER.info("Steve '{}' found {} ahead in tunnel at {}", 
                    steve.getSteveName(), targetBlock.getName().getString(), currentTarget);
            }
        }
    }

    /**
     * Equip an iron pickaxe for mining
     */
    private void equipIronPickaxe() {
        // Give Steve an iron pickaxe if he doesn't have one
        net.minecraft.world.item.ItemStack pickaxe = new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.IRON_PICKAXE
        );
        steve.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, pickaxe);
        SteveMod.LOGGER.info("Steve '{}' equipped iron pickaxe for mining", steve.getSteveName());
    }

    /**
     * Find the nearest player to determine mining direction
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
                nearestDistance = distance;
                nearest = player;
            }
        }
        
        return nearest;
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");

        Map<String, String> resourceToOre = new HashMap<>() {{
            put("iron", "iron_ore");
            put("diamond", "diamond_ore");
            put("coal", "coal_ore");
            put("gold", "gold_ore");
            put("copper", "copper_ore");
            put("redstone", "redstone_ore");
            put("lapis", "lapis_ore");
            put("emerald", "emerald_ore");
            // Wood/tree mappings
            put("wood", "oak_log");
            put("tree", "oak_log");
            put("log", "oak_log");
            put("oak", "oak_log");
            put("birch", "birch_log");
            put("spruce", "spruce_log");
            put("jungle", "jungle_log");
            put("acacia", "acacia_log");
            put("dark_oak", "dark_oak_log");
            // Common block mappings
            put("stone", "stone");
            put("cobblestone", "cobblestone");
            put("dirt", "dirt");
            put("sand", "sand");
            put("gravel", "gravel");
        }};

        if (resourceToOre.containsKey(blockName)) {
            blockName = resourceToOre.get(blockName);
        }

        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }

        ResourceLocation resourceLocation = new ResourceLocation(blockName);
        return BuiltInRegistries.BLOCK.get(resourceLocation);
    }

    /**
     * Mine a block and collect drops directly into Steve's inventory
     * Instead of dropping items into world, add them to inventory
     */
    private void mineBlockAndCollect(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);

        if (state.isAir() || state.getBlock() == Blocks.BEDROCK) {
            return;
        }

        // Get the tool Steve is holding
        ItemStack tool = steve.getItemInHand(InteractionHand.MAIN_HAND);

        // Get drops from the block
        if (steve.level() instanceof ServerLevel serverLevel) {
            // Build loot context
            LootParams.Builder builder = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, tool)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, steve.level().getBlockEntity(pos));

            List<ItemStack> drops = state.getDrops(builder);

            // Add drops to Steve's inventory
            for (ItemStack drop : drops) {
                if (!drop.isEmpty()) {
                    boolean added = InventoryHelper.addItem(steve, drop.copy());
                    if (!added) {
                        // Inventory full - drop to world
                        Block.popResource(steve.level(), pos, drop);
                        SteveMod.LOGGER.warn("Steve '{}' inventory full, dropped {} to world",
                            steve.getSteveName(), drop.getItem().getDescriptionId());
                    }
                }
            }
        }

        // Destroy the block (no drops, we handled them)
        steve.level().destroyBlock(pos, false);
    }
}

