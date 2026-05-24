package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WarehouseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Optional;

public class WarehouseRefillHandler {

    public enum RefillState {
        IDLE,
        NAVIGATING_TO_CHEST,
        WITHDRAWING,
        RETURNING_TO_BUILD
    }

    private RefillState state = RefillState.IDLE;
    private BlockPos warehousePos;
    private BlockPos buildReturnPos;
    private Map<Block, Integer> neededMaterials;
    private int ticksInState;
    private int totalWithdrawn;

    private static final int MAX_NAVIGATE_TICKS = 600;
    private static final int MAX_WITHDRAW_TICKS = 40;
    private static final double ARRIVAL_DISTANCE = 3.0;
    private static final double RETURN_DISTANCE = 5.0;

    public boolean startRefill(SteveEntity steve, Map<Block, Integer> needed) {
        if (!(steve.level() instanceof ServerLevel serverLevel)) return false;

        Optional<BlockPos> nearest = WarehouseManager.findNearest(serverLevel, steve.blockPosition());
        if (nearest.isEmpty()) {
            SteveMod.LOGGER.warn("Steve '{}' needs materials but no warehouse found", steve.getSteveName());
            return false;
        }

        this.warehousePos = nearest.get();
        this.buildReturnPos = steve.blockPosition();
        this.neededMaterials = needed;
        this.ticksInState = 0;
        this.totalWithdrawn = 0;
        this.state = RefillState.NAVIGATING_TO_CHEST;

        SteveMod.LOGGER.info("Steve '{}' going to warehouse at {} for materials", steve.getSteveName(), warehousePos);
        return true;
    }

    public boolean tick(SteveEntity steve) {
        ticksInState++;

        switch (state) {
            case NAVIGATING_TO_CHEST -> {
                return tickNavigating(steve);
            }
            case WITHDRAWING -> {
                return tickWithdrawing(steve);
            }
            case RETURNING_TO_BUILD -> {
                return tickReturning(steve);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean tickNavigating(SteveEntity steve) {
        if (ticksInState > MAX_NAVIGATE_TICKS) {
            SteveMod.LOGGER.warn("Steve '{}' warehouse navigation timeout", steve.getSteveName());
            state = RefillState.RETURNING_TO_BUILD;
            ticksInState = 0;
            return true;
        }

        double distance = Math.sqrt(steve.blockPosition().distSqr(warehousePos));
        if (distance <= ARRIVAL_DISTANCE) {
            state = RefillState.WITHDRAWING;
            ticksInState = 0;
            return true;
        }

        if (distance > 10) {
            steve.teleportTo(warehousePos.getX() + 2, warehousePos.getY(), warehousePos.getZ() + 2);
        } else {
            steve.getNavigation().moveTo(warehousePos.getX() + 0.5, warehousePos.getY(), warehousePos.getZ() + 0.5, 1.0);
        }
        return true;
    }

    private boolean tickWithdrawing(SteveEntity steve) {
        if (ticksInState > MAX_WITHDRAW_TICKS) {
            SteveMod.LOGGER.info("Steve '{}' finished withdrawing ({} items total)", steve.getSteveName(), totalWithdrawn);
            state = RefillState.RETURNING_TO_BUILD;
            ticksInState = 0;
            return true;
        }

        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            state = RefillState.RETURNING_TO_BUILD;
            ticksInState = 0;
            return true;
        }

        for (Map.Entry<Block, Integer> entry : neededMaterials.entrySet()) {
            if (steve.getBlockCount(entry.getKey()) >= entry.getValue()) continue;

            int needed = entry.getValue() - steve.getBlockCount(entry.getKey());
            int withdrawn = WarehouseManager.withdrawItem(serverLevel, warehousePos, steve, entry.getKey(), needed);
            if (withdrawn > 0) {
                totalWithdrawn += withdrawn;
                SteveMod.LOGGER.info("Steve '{}' withdrew {} {} from warehouse",
                        steve.getSteveName(), withdrawn, entry.getKey().getName().getString());
            }
        }

        state = RefillState.RETURNING_TO_BUILD;
        ticksInState = 0;
        return true;
    }

    private boolean tickReturning(SteveEntity steve) {
        if (ticksInState > MAX_NAVIGATE_TICKS) {
            SteveMod.LOGGER.warn("Steve '{}' return navigation timeout", steve.getSteveName());
            state = RefillState.IDLE;
            return false;
        }

        double distance = Math.sqrt(steve.blockPosition().distSqr(buildReturnPos));
        if (distance <= RETURN_DISTANCE) {
            SteveMod.LOGGER.info("Steve '{}' returned to build site, resuming", steve.getSteveName());
            state = RefillState.IDLE;
            return false;
        }

        if (distance > 10) {
            steve.teleportTo(buildReturnPos.getX() + 2, buildReturnPos.getY(), buildReturnPos.getZ() + 2);
        } else {
            steve.getNavigation().moveTo(buildReturnPos.getX() + 0.5, buildReturnPos.getY(), buildReturnPos.getZ() + 0.5, 1.0);
        }
        return true;
    }

    public void cancel(SteveEntity steve) {
        state = RefillState.IDLE;
        steve.getNavigation().stop();
    }

    public boolean isRefilling() {
        return state != RefillState.IDLE;
    }

    public RefillState getState() {
        return state;
    }
}
