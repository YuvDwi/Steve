package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Farm action for planting, harvesting, and managing crops
 * Supports wheat, carrots, potatoes, beetroot with automatic replanting
 */
public class FarmAction extends BaseAction {
    private String cropType;
    private String actionType; // "plant", "harvest", "farm" (both)
    private int targetAmount;
    private int workDone;
    private int searchRadius = 16;
    private int ticksRunning;
    private static final int MAX_TICKS = 6000; // 5 minutes
    private static final int TICK_DELAY = 20; // 1 second between operations
    private int ticksSinceLastAction = 0;

    // Crop mappings
    private static final Map<String, Block> CROP_BLOCKS = new HashMap<>() {{
        put("wheat", Blocks.WHEAT);
        put("carrots", Blocks.CARROTS);
        put("potatoes", Blocks.POTATOES);
        put("beetroot", Blocks.BEETROOTS);
    }};

    private static final Map<String, Item> CROP_SEEDS = new HashMap<>() {{
        put("wheat", Items.WHEAT_SEEDS);
        put("carrots", Items.CARROT);
        put("potatoes", Items.POTATO);
        put("beetroot", Items.BEETROOT_SEEDS);
    }};

    private static final Map<String, Item> CROP_PRODUCTS = new HashMap<>() {{
        put("wheat", Items.WHEAT);
        put("carrots", Items.CARROT);
        put("potatoes", Items.POTATO);
        put("beetroot", Items.BEETROOT);
    }};

    public FarmAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        cropType = task.getStringParameter("crop", "wheat").toLowerCase();
        actionType = task.getStringParameter("type", "farm").toLowerCase(); // farm, plant, harvest
        targetAmount = task.getIntParameter("amount", 64);
        workDone = 0;
        ticksRunning = 0;
        ticksSinceLastAction = 0;

        if (!CROP_BLOCKS.containsKey(cropType)) {
            result = ActionResult.failure("Unknown crop type: " + cropType);
            return;
        }

        SteveMod.LOGGER.info("Steve '{}' starting {} action for {} (target: {})",
            steve.getSteveName(), actionType, cropType, targetAmount);

        // Equip hoe for farming
        equipHoe();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastAction++;

        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Farming timeout - completed " + workDone + " operations");
            return;
        }

        if (ticksSinceLastAction < TICK_DELAY) {
            return; // Wait between actions
        }

        boolean didWork = false;

        switch (actionType) {
            case "plant" -> didWork = doPlanting();
            case "harvest" -> didWork = doHarvesting();
            case "farm" -> {
                // Do both harvesting and planting
                if (!doHarvesting()) {
                    didWork = doPlanting();
                } else {
                    didWork = true;
                }
            }
            default -> {
                result = ActionResult.failure("Unknown action type: " + actionType);
                return;
            }
        }

        if (didWork) {
            workDone++;
            ticksSinceLastAction = 0;

            if (workDone >= targetAmount) {
                result = ActionResult.success("Completed " + workDone + " farming operations for " + cropType);
                return;
            }
        } else {
            // No work found, check if we've done enough
            if (workDone > 0) {
                result = ActionResult.success("Completed " + workDone + " farming operations for " + cropType);
            } else {
                result = ActionResult.failure("No farmland or crops found nearby");
            }
        }
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return actionType + " " + cropType + " (" + workDone + "/" + targetAmount + ")";
    }

    /**
     * Plant crops on farmland
     */
    private boolean doPlanting() {
        Item seedItem = CROP_SEEDS.get(cropType);

        // Check if we have seeds
        if (!InventoryHelper.hasItem(steve, seedItem, 1)) {
            SteveMod.LOGGER.warn("Steve '{}' has no {} seeds", steve.getSteveName(), cropType);
            return false;
        }

        // Find empty farmland
        BlockPos farmlandPos = findEmptyFarmland();

        if (farmlandPos == null) {
            return false;
        }

        // Move to farmland
        steve.teleportTo(farmlandPos.getX() + 0.5, farmlandPos.getY() + 1, farmlandPos.getZ() + 0.5);

        // Plant crop
        Block cropBlock = CROP_BLOCKS.get(cropType);
        steve.level().setBlock(farmlandPos.above(), cropBlock.defaultBlockState(), 3);

        // Remove seed from inventory
        InventoryHelper.removeItem(steve, seedItem, 1);

        steve.swing(InteractionHand.MAIN_HAND, true);

        SteveMod.LOGGER.info("Steve '{}' planted {} at {}",
            steve.getSteveName(), cropType, farmlandPos);

        // Try to use bone meal if available
        if (InventoryHelper.hasItem(steve, Items.BONE_MEAL, 1)) {
            useBoneMeal(farmlandPos.above());
        }

        return true;
    }

    /**
     * Harvest fully grown crops
     */
    private boolean doHarvesting() {
        // Find mature crop
        BlockPos cropPos = findMatureCrop();

        if (cropPos == null) {
            return false;
        }

        // Move to crop
        steve.teleportTo(cropPos.getX() + 0.5, cropPos.getY(), cropPos.getZ() + 0.5);

        // Harvest crop and collect drops
        harvestCrop(cropPos);

        steve.swing(InteractionHand.MAIN_HAND, true);

        SteveMod.LOGGER.info("Steve '{}' harvested {} at {}",
            steve.getSteveName(), cropType, cropPos);

        // Auto-replant if we have seeds
        Item seedItem = CROP_SEEDS.get(cropType);
        if (InventoryHelper.hasItem(steve, seedItem, 1)) {
            Block cropBlock = CROP_BLOCKS.get(cropType);
            steve.level().setBlock(cropPos, cropBlock.defaultBlockState(), 3);
            InventoryHelper.removeItem(steve, seedItem, 1);

            SteveMod.LOGGER.info("Steve '{}' auto-replanted {} at {}",
                steve.getSteveName(), cropType, cropPos);

            // Try to use bone meal if available
            if (InventoryHelper.hasItem(steve, Items.BONE_MEAL, 1)) {
                useBoneMeal(cropPos);
            }
        }

        return true;
    }

    /**
     * Find empty farmland to plant on
     */
    private BlockPos findEmptyFarmland() {
        BlockPos stevePos = steve.blockPosition();
        List<BlockPos> farmlandPositions = new ArrayList<>();

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                for (int y = -3; y <= 3; y++) {
                    BlockPos checkPos = stevePos.offset(x, y, z);
                    BlockState state = steve.level().getBlockState(checkPos);

                    // Check if it's farmland
                    if (state.getBlock() == Blocks.FARMLAND) {
                        // Check if the block above is air (empty farmland)
                        BlockPos abovePos = checkPos.above();
                        if (steve.level().getBlockState(abovePos).isAir()) {
                            farmlandPositions.add(checkPos);
                        }
                    }
                }
            }
        }

        if (farmlandPositions.isEmpty()) {
            return null;
        }

        // Find closest farmland
        return farmlandPositions.stream()
            .min(Comparator.comparingDouble(pos -> pos.distSqr(stevePos)))
            .orElse(null);
    }

    /**
     * Find mature (fully grown) crop
     */
    private BlockPos findMatureCrop() {
        BlockPos stevePos = steve.blockPosition();
        Block targetCrop = CROP_BLOCKS.get(cropType);
        List<BlockPos> matureCrops = new ArrayList<>();

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                for (int y = -3; y <= 3; y++) {
                    BlockPos checkPos = stevePos.offset(x, y, z);
                    BlockState state = steve.level().getBlockState(checkPos);

                    if (state.getBlock() == targetCrop) {
                        // Check if crop is fully grown
                        if (isCropMature(state)) {
                            matureCrops.add(checkPos);
                        }
                    }
                }
            }
        }

        if (matureCrops.isEmpty()) {
            return null;
        }

        // Find closest mature crop
        return matureCrops.stream()
            .min(Comparator.comparingDouble(pos -> pos.distSqr(stevePos)))
            .orElse(null);
    }

    /**
     * Check if a crop is fully mature
     */
    private boolean isCropMature(BlockState state) {
        if (!(state.getBlock() instanceof CropBlock cropBlock)) {
            return false;
        }

        // Check if crop is at max age
        if (state.hasProperty(BlockStateProperties.AGE_7)) {
            return state.getValue(BlockStateProperties.AGE_7) == 7;
        } else if (state.hasProperty(BlockStateProperties.AGE_3)) {
            return state.getValue(BlockStateProperties.AGE_3) == 3;
        }

        return cropBlock.isMaxAge(state);
    }

    /**
     * Harvest crop and collect drops into inventory
     */
    private void harvestCrop(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);

        if (state.isAir()) {
            return;
        }

        // Get the tool Steve is holding
        ItemStack tool = steve.getItemInHand(InteractionHand.MAIN_HAND);

        // Get drops from the crop
        if (steve.level() instanceof ServerLevel serverLevel) {
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

        // Destroy the crop
        steve.level().destroyBlock(pos, false);
    }

    /**
     * Use bone meal to accelerate crop growth
     */
    private void useBoneMeal(BlockPos cropPos) {
        BlockState state = steve.level().getBlockState(cropPos);

        if (state.getBlock() instanceof CropBlock) {
            // Apply bone meal effect
            if (state.getBlock() instanceof BonemealableBlock bonemealable) {
                if (steve.level() instanceof ServerLevel serverLevel) {
                    if (bonemealable.isValidBonemealTarget(steve.level(), cropPos, state)) {
                        bonemealable.performBonemeal(serverLevel, steve.level().random, cropPos, state);

                        // Remove bone meal from inventory
                        InventoryHelper.removeItem(steve, Items.BONE_MEAL, 1);

                        steve.swing(InteractionHand.MAIN_HAND, true);

                        SteveMod.LOGGER.info("Steve '{}' used bone meal on {} at {}",
                            steve.getSteveName(), cropType, cropPos);
                    }
                }
            }
        }
    }

    /**
     * Equip a hoe for farming
     */
    private void equipHoe() {
        // Check if Steve already has a hoe
        for (int i = 0; i < steve.getInventory().getContainerSize(); i++) {
            ItemStack stack = steve.getInventory().getItem(i);
            if (stack.getItem() instanceof net.minecraft.world.item.HoeItem) {
                steve.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
                SteveMod.LOGGER.info("Steve '{}' equipped existing hoe", steve.getSteveName());
                return;
            }
        }

        // Give Steve a basic hoe if none found
        ItemStack hoe = new ItemStack(Items.IRON_HOE);
        steve.setItemInHand(InteractionHand.MAIN_HAND, hoe);
        SteveMod.LOGGER.info("Steve '{}' equipped new iron hoe", steve.getSteveName());
    }
}
