package com.steve.ai.action.plan;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.util.BlockPlacer;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.event.plan.PlanLogEvent;
import com.steve.ai.structure.ModuleTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 推进 {@code project.nextBlockIndex} 走完所有 placedModules 的方块，
 * 每 tick 放一个，受 {@code BUILD_TICK_DELAY} 节流。复用了
 * {@link BlockPlacer}（寻路 + 材料 + 占用 + 挥手动画）。
 */
public class BuildConstructionLoop {

    private final SteveEntity steve;
    private final BuildProject project;

    /** 距离下一次方块放置尝试的剩余 tick 数。 */
    private int constructionCooldown;

    public BuildConstructionLoop(SteveEntity steve, BuildProject project) {
        this.steve = steve;
        this.project = project;
    }

    /**
     * 每 tick 调用一次。
     *
     * @return true 表示所有方块放置完成；false 表示仍在进行中。
     */
    public boolean tick(ServerLevel level) {
        if (project.nextBlockIndex >= project.totalBlocks) {
            return true;
        }

        int delay = Math.max(1, SteveConfig.BUILD_TICK_DELAY.get());
        if (constructionCooldown > 0) {
            constructionCooldown--;
            return false;
        }

        if (placeNextBlock(level)) {
            constructionCooldown = delay;
        }
        // false path: 仍在寻路 / 寻路失败，不消耗冷却，下 tick 重试
        return false;
    }

    private boolean placeNextBlock(ServerLevel level) {
        int idx = project.nextBlockIndex;
        int remaining = idx;
        BlockPos worldPos = null;
        net.minecraft.world.level.block.state.BlockState state = null;
        for (var pm : project.placedModules) {
            if (remaining < pm.template.blocks.size()) {
                var tb = pm.template.blocks.get(remaining);
                worldPos = ModuleTransform.apply(tb.relativePos, pm.worldOrigin, pm.facing);
                state = tb.blockState;
                break;
            }
            remaining -= pm.template.blocks.size();
        }
        if (worldPos == null || state == null) {
            project.nextBlockIndex = project.totalBlocks;
            return false;
        }

        BlockPlacer.PlaceResult pr = BlockPlacer.tryPlace(steve, worldPos, state);
        switch (pr) {
            case NAVIGATING -> { return false; }
            case OCCUPIED -> {
                publishLog(PlanLogEvent.Severity.WARN,
                    "Skipping " + worldPos + ": occupied");
                project.nextBlockIndex++;
                project.blocksPlaced++;
                return true;
            }
            case NO_MATERIAL -> {
                publishLog(PlanLogEvent.Severity.WARN,
                    "Skipping " + worldPos + ": no " + state.getBlock().getName().getString());
                project.nextBlockIndex++;
                project.blocksPlaced++;
                return true;
            }
            case PLACED -> {
                project.nextBlockIndex++;
                project.blocksPlaced++;
                if (project.blocksPlaced % 50 == 0 || project.blocksPlaced == project.totalBlocks) {
                    publishLog(PlanLogEvent.Severity.INFO,
                        "Construction progress: " + project.blocksPlaced + "/" + project.totalBlocks);
                }
                return true;
            }
        }
        return false;
    }

    private void publishLog(PlanLogEvent.Severity severity, String message) {
        try {
            SteveMod.getPlanEventBus().publish(
                new PlanLogEvent(project.id, severity, message));
        } catch (Exception e) {
            SteveMod.LOGGER.warn("Failed to publish plan log: {}", e.getMessage());
        }
    }
}
