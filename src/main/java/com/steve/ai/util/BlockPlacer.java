package com.steve.ai.util;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockPlacer {

    public enum PlaceResult { PLACED, NAVIGATING, OCCUPIED, NO_MATERIAL }

    private BlockPlacer() {}

    public static PlaceResult tryPlace(SteveEntity steve, BlockPos targetPos, BlockState state) {
        if (!steve.blockPosition().closerThan(targetPos, 5.0)) {
            steve.getNavigation().moveTo(
                targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 1.0);
            return PlaceResult.NAVIGATING;
        }

        BlockState current = steve.level().getBlockState(targetPos);
        if (!current.isAir() && !current.liquid()) {
            return PlaceResult.OCCUPIED;
        }

        boolean creative = SteveConfig.CREATIVE_MODE.get();
        if (!creative) {
            if (!steve.hasBlock(state.getBlock(), 1)) {
                return PlaceResult.NO_MATERIAL;
            }
            steve.removeBlockFromInventory(state.getBlock(), 1);
        }

        steve.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(state.getBlock().asItem()));
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.level().setBlock(targetPos, state, 3);

        return PlaceResult.PLACED;
    }
}
