package com.steve.ai.action.plan;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.event.plan.PlanDesignReadyEvent;
import com.steve.ai.llm.react.BuildDesignFormatter;
import com.steve.ai.structure.ModuleTransform;
import com.steve.ai.structure.PlacedModule;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 加载 NBT 模板解析到 {@code project.placedModules}，然后推送设计文档
 * （聊天 + dashboard 3D 快照）。Phase 0/1 后的两个动作；phase 转换
 * 由调用方（PlanBuildAction）负责。
 */
public class BuildDesignGenerator {

    private final SteveEntity steve;
    private final BuildProject project;
    private final List<Map<String, Object>> specs;

    public BuildDesignGenerator(SteveEntity steve, BuildProject project, List<Map<String, Object>> specs) {
        this.steve = steve;
        this.project = project;
        this.specs = specs;
    }

    /**
     * 加载所有 NBT 并解析到 project.placedModules。失败回填 survivors。
     * 返回 true 表示至少有一个模块成功加载。
     */
    public boolean loadAndPlace(ServerLevel level) {
        Player nearest = project.findNearestPlayer();

        BlockPos groundPos;
        if (nearest != null) {
            var eye = nearest.getEyePosition(1.0F);
            var look = nearest.getLookAngle();
            var target = eye.add(look.scale(12));
            groundPos = new BlockPos(
                (int) Math.floor(target.x),
                (int) Math.floor(target.y),
                (int) Math.floor(target.z));
        } else {
            groundPos = steve.blockPosition().offset(2, 0, 2);
        }
        project.originPos = groundPos;

        BlockPos prevExit = groundPos;
        PlacedModule.Facing prevFacing = PlacedModule.Facing.S;
        List<String> survivors = new ArrayList<>();

        for (Map<String, Object> spec : specs) {
            Object n = spec.get("name");
            String name = n == null ? null : n.toString();
            if (name == null) continue;
            int dx = BuildModuleSpecParser.readInt(spec, "dx", 0);
            int dy = BuildModuleSpecParser.readInt(spec, "dy", 0);
            int dz = BuildModuleSpecParser.readInt(spec, "dz", 0);
            PlacedModule.Facing facing = BuildModuleSpecParser.readFacing(spec, "facing", PlacedModule.Facing.S);

            StructureTemplateLoader.LoadedTemplate tpl = StructureTemplateLoader.loadFromNBT(level, name);
            if (tpl == null) {
                SteveMod.LOGGER.warn("PlanBuildAction: template '{}' not found, skipping", name);
                continue;
            }

            BlockPos localIn = new BlockPos(dx, dy, dz);
            BlockPos worldIn = ModuleTransform.apply(localIn, prevExit, prevFacing);

            project.placedModules.add(new PlacedModule(tpl, worldIn, facing));
            survivors.add(name);
            for (var tb : tpl.blocks) {
                project.materials.merge(tb.blockState.getBlock(), 1, Integer::sum);
            }
            project.totalBlocks += tpl.blocks.size();

            prevExit = ModuleTransform.apply(
                ModuleTransform.exitAnchor(tpl, facing), worldIn, facing);
            prevFacing = facing;
        }
        project.selectedTemplates.clear();
        project.selectedTemplates.addAll(survivors);
        return !project.placedModules.isEmpty();
    }

    /** 推送设计文档（聊天给最近玩家）和 dashboard 3D 快照事件。 */
    public void publishDesign() {
        Player nearest = project.findNearestPlayer();
        String design = BuildDesignFormatter.fullDesign(project);
        if (nearest != null) {
            for (String line : design.replace("\r\n", "\n").split("\n")) {
                nearest.sendSystemMessage(Component.literal(line));
            }
        } else {
            SteveMod.LOGGER.info("Design doc (no player to message):\n{}", design);
        }

        List<PlanDesignReadyEvent.BlockEntry> blocks = new ArrayList<>(project.totalBlocks);
        for (var pm : project.placedModules) {
            for (var tb : pm.template.blocks) {
                BlockPos worldPos = ModuleTransform.apply(
                    tb.relativePos, pm.worldOrigin, pm.facing);
                String id = tb.blockState.getBlock().builtInRegistryHolder()
                    .key().location().toString();
                blocks.add(new PlanDesignReadyEvent.BlockEntry(
                    worldPos.getX(), worldPos.getY(), worldPos.getZ(), id));
            }
        }
        SteveMod.getPlanEventBus().publish(new PlanDesignReadyEvent(
            project.id, design,
            PlanDesignReadyEvent.MaterialEntry.fromBlockMap(project.materials, project.totalBlocks),
            project.totalBlocks,
            blocks));
    }
}
