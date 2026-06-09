package com.steve.ai.action.plan;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.react.BuildPhase;
import com.steve.ai.structure.PlacedModule;
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
 *
 * <p>The module-composition refactor replaced the legacy
 * {@code List<LoadedTemplate> templates} with
 * {@link #placedModules}: each entry is a {@link PlacedModule} that
 * carries the loaded template <em>and</em> its world origin and facing.
 * Three downstream consumers (CONSTRUCTION block placement, dashboard
 * 3D snapshot, design-ready event payload) iterate this list and
 * compute world coordinates through
 * {@code ModuleTransform.apply(...)} — so the 3D preview and the placed
 * world cannot diverge.</p>
 */
public class BuildProject {

    public final String id;
    public final SteveEntity steve;
    public final String command;
    public final long createdAtMs;

    /** Names of the modules the LLM selected, in chain order. Survives even
     *  if a template fails to load — used in mempalace archives. */
    public final List<String> selectedTemplates = new ArrayList<>();

    /** Resolved module placements: each entry pairs a loaded NBT template
     *  with its world origin and Y-rotation. The CONSTRUCTION phase and the
     *  dashboard snapshot both iterate this list. */
    public final List<PlacedModule> placedModules = new ArrayList<>();

    public int currentModuleIndex = 0;
    public BlockPos originPos;
    public final Map<Block, Integer> materials = new LinkedHashMap<>();

    public BuildPhase phase = BuildPhase.FEASIBILITY;
    public BuildPhase lastApproved;

    public int blocksPlaced;
    public int totalBlocks;

    /** Next world-space block index to place during CONSTRUCTION. Walks the
     *  same flattened order used by the dashboard snapshot (placedModules
     *  in project.placedModules order, blocks in PlacedModule.template.blocks
     *  order). */
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
