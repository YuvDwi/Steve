package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Builds nether portals automatically
 * Handles obsidian placement and portal lighting
 */
public class PortalBuilderAction extends BaseAction {
    private BlockPos portalBasePos;
    private int ticksRunning;
    private int blocksPlaced;
    private static final int MAX_TICKS = 600; // 30 seconds
    private static final int REQUIRED_OBSIDIAN = 10; // Minimum for portal frame

    // Portal frame positions (relative to base)
    private static final int[][] PORTAL_FRAME = {
        // Bottom
        {0, 0, 0}, {1, 0, 0}, {2, 0, 0}, {3, 0, 0},
        // Left side
        {0, 1, 0}, {0, 2, 0}, {0, 3, 0}, {0, 4, 0},
        // Right side
        {3, 1, 0}, {3, 2, 0}, {3, 3, 0}, {3, 4, 0},
        // Top
        {0, 4, 0}, {1, 4, 0}, {2, 4, 0}, {3, 4, 0}
    };

    public PortalBuilderAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        ticksRunning = 0;
        blocksPlaced = 0;

        // Check if we have obsidian
        int obsidianCount = InventoryHelper.getItemCount(steve, Items.OBSIDIAN);
        if (obsidianCount < REQUIRED_OBSIDIAN) {
            result = ActionResult.failure("Need at least " + REQUIRED_OBSIDIAN + " obsidian (have: " + obsidianCount + ")");
            return;
        }

        // Check if we have flint and steel
        if (!InventoryHelper.hasItem(steve, Items.FLINT_AND_STEEL, 1)) {
            result = ActionResult.failure("Need flint and steel to light portal");
            return;
        }

        // Set portal base position in front of Steve
        portalBasePos = steve.blockPosition().relative(steve.getDirection(), 2);

        SteveMod.LOGGER.info("Steve '{}' building nether portal at {}",
            steve.getSteveName(), portalBasePos);

        // Enable flying for easier building
        steve.setFlying(true);
    }

    @Override
    protected void onTick() {
        ticksRunning++;

        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            result = ActionResult.failure("Portal building timeout");
            return;
        }

        // Build portal frame
        if (blocksPlaced < PORTAL_FRAME.length) {
            placeNextObsidian();
            return;
        }

        // Light the portal
        if (!isPortalLit()) {
            lightPortal();
            return;
        }

        // Portal complete
        steve.setFlying(false);
        result = ActionResult.success("Nether portal built and lit");
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false);
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Build nether portal (" + blocksPlaced + "/" + PORTAL_FRAME.length + " blocks)";
    }

    /**
     * Place next obsidian block in portal frame
     */
    private void placeNextObsidian() {
        if (blocksPlaced >= PORTAL_FRAME.length) {
            return;
        }

        int[] offset = PORTAL_FRAME[blocksPlaced];
        BlockPos targetPos = portalBasePos.offset(offset[0], offset[1], offset[2]);

        // Check if block already exists
        if (!steve.level().getBlockState(targetPos).isAir() &&
            steve.level().getBlockState(targetPos).getBlock() != Blocks.OBSIDIAN) {
            // Skip if something else is there
            blocksPlaced++;
            return;
        }

        // Move to position
        steve.teleportTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5);

        // Place obsidian
        ItemStack obsidian = new ItemStack(Items.OBSIDIAN);
        steve.setItemInHand(InteractionHand.MAIN_HAND, obsidian);

        steve.level().setBlock(targetPos, Blocks.OBSIDIAN.defaultBlockState(), 3);
        steve.swing(InteractionHand.MAIN_HAND, true);

        // Remove from inventory
        InventoryHelper.removeItem(steve, Items.OBSIDIAN, 1);

        blocksPlaced++;

        SteveMod.LOGGER.debug("Steve '{}' placed obsidian {}/{}",
            steve.getSteveName(), blocksPlaced, PORTAL_FRAME.length);
    }

    /**
     * Light the portal with flint and steel
     */
    private void lightPortal() {
        // Light position (inside bottom of portal)
        BlockPos lightPos = portalBasePos.offset(1, 1, 0);

        // Move to lighting position
        steve.teleportTo(lightPos.getX() + 0.5, lightPos.getY(), lightPos.getZ() + 0.5);

        // Equip flint and steel
        ItemStack flintAndSteel = new ItemStack(Items.FLINT_AND_STEEL);
        steve.setItemInHand(InteractionHand.MAIN_HAND, flintAndSteel);

        // Create fire
        BlockPos firePos = lightPos;
        steve.level().setBlock(firePos, Blocks.FIRE.defaultBlockState(), 3);
        steve.swing(InteractionHand.MAIN_HAND, true);

        SteveMod.LOGGER.info("Steve '{}' lit nether portal", steve.getSteveName());
    }

    /**
     * Check if portal is lit (has nether portal blocks)
     */
    private boolean isPortalLit() {
        // Check interior positions for portal blocks
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 2; x++) {
                BlockPos checkPos = portalBasePos.offset(x, y, 0);
                if (steve.level().getBlockState(checkPos).getBlock() == Blocks.NETHER_PORTAL) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if Steve has materials for portal
     */
    public static boolean hasMaterials(SteveEntity steve) {
        return InventoryHelper.getItemCount(steve, Items.OBSIDIAN) >= REQUIRED_OBSIDIAN &&
               InventoryHelper.hasItem(steve, Items.FLINT_AND_STEEL, 1);
    }

    /**
     * Get materials summary
     */
    public static String getMaterialsSummary(SteveEntity steve) {
        int obsidian = InventoryHelper.getItemCount(steve, Items.OBSIDIAN);
        boolean hasFlint = InventoryHelper.hasItem(steve, Items.FLINT_AND_STEEL, 1);

        return String.format("Obsidian: %d/%d, Flint & Steel: %s",
            obsidian, REQUIRED_OBSIDIAN, hasFlint ? "Yes" : "No");
    }
}
