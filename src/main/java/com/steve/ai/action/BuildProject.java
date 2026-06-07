package com.steve.ai.action;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.react.BuildPhase;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks the full state of a single build project driven by PlanBuildAction.
 *
 * <p>One project is created per intercepted "build" task. It moves through
 * {@link BuildPhase} transitions, with each phase's outputs persisted to mempalace.</p>
 */
public class BuildProject {

    public final String id;
    public final SteveEntity steve;
    public final String command;
    public final long createdAtMs;

    public final List<String> selectedTemplates = new ArrayList<>();
    public final List<StructureTemplateLoader.LoadedTemplate> templates = new ArrayList<>();
    public int currentTemplateIndex = 0;
    public BlockPos originPos;
    public final Map<Block, Integer> materials = new LinkedHashMap<>();

    public BuildPhase phase = BuildPhase.FEASIBILITY;
    public BuildPhase lastApproved;

    public int blocksPlaced;
    public int totalBlocks;

    /** Next world-space block index to place during CONSTRUCTION. Walks the
     *  same flattened order used by the dashboard snapshot (templates in
     *  project.templates order, blocks in LoadedTemplate.blocks order). */
    public int nextBlockIndex;

    public long phaseDeadlineMs;

    public final Map<BuildPhase, String> mempalaceRefs = new HashMap<>();

    public BuildProject(SteveEntity steve, String command) {
        this(steve, command, List.of(command));
    }

    public BuildProject(SteveEntity steve, String command, List<String> requestedTemplates) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.steve = steve;
        this.command = command;
        this.createdAtMs = System.currentTimeMillis();
        this.selectedTemplates.addAll(requestedTemplates);
    }

    public Player findNearestPlayer() {
        List<? extends Player> players = steve.level().players();
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Player player : players) {
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

    public Map<Block, Integer> countMaterials(List<com.steve.ai.structure.BlockPlacement> plan) {
        Map<Block, Integer> counts = new LinkedHashMap<>();
        for (var bp : plan) {
            counts.merge(bp.block, 1, Integer::sum);
        }
        return counts;
    }

    public int getIdHash() {
        // short hash for log readability
        return Math.abs(id.hashCode() % 100000);
    }
}
