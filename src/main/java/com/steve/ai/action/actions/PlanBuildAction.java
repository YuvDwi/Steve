package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.BuildProject;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.event.plan.PlanApprovedEvent;
import com.steve.ai.event.plan.PlanCreatedEvent;
import com.steve.ai.event.plan.PlanDesignReadyEvent;
import com.steve.ai.event.plan.PlanHaltedEvent;
import com.steve.ai.event.plan.PlanLogEvent;
import com.steve.ai.event.plan.PlanPhaseChangedEvent;
import com.steve.ai.llm.react.BuildDesignFormatter;
import com.steve.ai.llm.react.BuildPhase;
import com.steve.ai.mcp.MCPToolRegistry;
import com.steve.ai.structure.BlockPlacement;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Four-phase build orchestrator.
 *
 * <p>Phases:
 * <ul>
 *   <li>FEASIBILITY — resolve NBT template list (driven by LLM via task params)</li>
 *   <li>DESIGN — load NBT, emit design doc, archive to mempalace, push dashboard event</li>
 *   <li>AWAITING_DESIGN_APPROVAL — idle, waits for {@code /steve approve} or {@code /steve halt}</li>
 *   <li>CONSTRUCTION — place every block from the loaded templates at {@code BUILD_TICK_DELAY}
 *       cadence. No second confirmation: dashboard approve kicks construction off directly.</li>
 *   <li>COMPLETED / FAILED — terminal</li>
 * </ul>
 *
 * <p>{@link BuildPhase#AWAITING_ACCEPTANCE} is kept as an enum value for source compatibility
 * but is no longer entered by the dashboard-approve flow.</p>
 */
public class PlanBuildAction extends BaseAction {

    private final BuildProject project;
    private final ActionExecutor executor;

    /** Ticks remaining before the next CONSTRUCTION block placement attempt. */
    private int constructionCooldown;

    public PlanBuildAction(SteveEntity steve, Task task, ActionExecutor executor) {
        super(steve, task);
        this.executor = executor;

        // Resolve the requested template list. Prefer "structures" (array), fall back to
        // "structure" (single string wrapped in a 1-element list), then to the player command.
        List<String> requested = task.getStringListParameter("structures");
        if (requested == null || requested.isEmpty()) {
            String single = task.getStringParameter("structure");
            requested = single != null ? List.of(single) : new ArrayList<>();
        }
        if (requested.isEmpty()) {
            requested = new ArrayList<>(List.of(task.getStringParameter("structure", "unknown")));
        }

        int cap = SteveConfig.MAX_TEMPLATES_PER_PLAN.get();
        if (requested.size() > cap) {
            SteveMod.LOGGER.warn("PlanBuildAction: LLM requested {} templates, capping to {}",
                requested.size(), cap);
            requested = new ArrayList<>(requested.subList(0, cap));
        }

        this.project = new BuildProject(steve, task.getStringParameter("structure", "unknown"), requested);

        // Fire PlanCreatedEvent so the external HTML dashboard can pick up the
        // project as soon as the action is constructed.
        publishEvent(new PlanCreatedEvent(
            project.id, steve.getSteveName(), project.command,
            project.selectedTemplates, project.phase));
    }

    public BuildProject getProject() {
        return project;
    }

    @Override
    protected void onStart() {
        SteveMod.LOGGER.info("PlanBuildAction started for Steve '{}', command='{}'",
            steve.getSteveName(), project.command);

        // Phase 1: pick templates. LLM already provided them via task.parameters.structures
        // (or legacy task.parameters.structure / fallback to player command).
        String available = String.join(",", StructureTemplateLoader.getAvailableStructures());
        SteveMod.LOGGER.info("Phase FEASIBILITY: selected templates={} (available: {})",
            project.selectedTemplates, available);
        publishLog(PlanLogEvent.Severity.INFO, "FEASIBILITY: templates=" + project.selectedTemplates);
        transitionTo(BuildPhase.DESIGN);
    }

    @Override
    protected void onTick() {
        switch (project.phase) {
            case DESIGN -> runDesign();
            case AWAITING_DESIGN_APPROVAL -> runAwaitingApproval();
            case CONSTRUCTION -> runConstruction();
            case AWAITING_ACCEPTANCE, COMPLETED, FAILED, FEASIBILITY -> { /* terminal / unused */ }
        }
    }

    @Override
    protected void onCancel() {
        if (project.phase != BuildPhase.FAILED && project.phase != BuildPhase.COMPLETED) {
            transitionTo(BuildPhase.FAILED);
        }
    }

    @Override
    public String getDescription() {
        return String.format(Locale.ROOT, "Plan build %s (#%s, %s)",
            String.join("+", project.selectedTemplates), project.id, project.phase);
    }

    // ===== Phase 2: DESIGN =====

    private void runDesign() {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            result = ActionResult.failure("PlanBuildAction must run on server level");
            return;
        }

        // 1. Resolve origin: nearest player's look-target, fallback to Steve +2
        Player nearest = project.findNearestPlayer();
        BlockPos groundPos;
        if (nearest != null) {
            var eye = nearest.getEyePosition(1.0F);
            var look = nearest.getLookAngle();
            var target = eye.add(look.scale(12));
            BlockPos lookTarget = new BlockPos((int) Math.floor(target.x), (int) Math.floor(target.y), (int) Math.floor(target.z));
            groundPos = lookTarget;
        } else {
            groundPos = steve.blockPosition().offset(2, 0, 2);
        }
        project.originPos = groundPos;

        // 2. Load each requested NBT. Skip-on-miss (graceful degradation). Each sub-template
        //    is placed along the X axis, one block past the previous template's width.
        int originX = groundPos.getX();
        int originY = groundPos.getY();
        int originZ = groundPos.getZ();
        for (String name : new ArrayList<>(project.selectedTemplates)) {
            StructureTemplateLoader.LoadedTemplate tpl = StructureTemplateLoader.loadFromNBT(serverLevel, name);
            if (tpl == null) {
                SteveMod.LOGGER.warn("PlanBuildAction: template '{}' not found, skipping", name);
                project.selectedTemplates.remove(name);
                continue;
            }
            StructureTemplateLoader.LoadedTemplate withOrigin = new StructureTemplateLoader.LoadedTemplate(
                tpl.name, tpl.blocks, tpl.width, tpl.height, tpl.depth, new BlockPos(originX, originY, originZ));
            project.templates.add(withOrigin);
            for (var tb : tpl.blocks) {
                project.materials.merge(tb.blockState.getBlock(), 1, Integer::sum);
            }
            project.totalBlocks += tpl.blocks.size();
            originX += tpl.width + 1;
        }

        if (project.templates.isEmpty()) {
            result = ActionResult.failure(
                "None of the requested NBT templates could be loaded: " + project.selectedTemplates);
            return;
        }

        // 3. Push design doc to nearest player
        String design = BuildDesignFormatter.fullDesign(project);
        if (nearest != null) {
            for (String line : design.replace("\r\n", "\n").split("\n")) {
                nearest.sendSystemMessage(Component.literal(line));
            }
        } else {
            SteveMod.LOGGER.info("Design doc (no player to message):\n{}", design);
        }

        // 3b. Mirror design to the external dashboard. We flatten every loaded
        // template's blocks into a single world-space list so the front-end can
        // render the whole structure in Three.js without knowing about per-template origins.
        List<PlanDesignReadyEvent.BlockEntry> blocks = new ArrayList<>(project.totalBlocks);
        for (var tpl : project.templates) {
            BlockPos o = tpl.origin != null ? tpl.origin : BlockPos.ZERO;
            for (var tb : tpl.blocks) {
                String id = tb.blockState.getBlock().builtInRegistryHolder()
                    .key().location().toString();
                blocks.add(new PlanDesignReadyEvent.BlockEntry(
                    o.getX() + tb.relativePos.getX(),
                    o.getY() + tb.relativePos.getY(),
                    o.getZ() + tb.relativePos.getZ(),
                    id));
            }
        }
        publishEvent(new PlanDesignReadyEvent(
            project.id, design,
            PlanDesignReadyEvent.MaterialEntry.fromBlockMap(project.materials, project.totalBlocks),
            project.totalBlocks,
            blocks));

        // 4. Archive to mempalace
        archiveToMempalace(BuildPhase.DESIGN, "design", design);

        // 5. Wait for approval (no auto-timeout — player must /steve approve or /steve halt)
        transitionTo(BuildPhase.AWAITING_DESIGN_APPROVAL);
    }

    private void runAwaitingApproval() {
        // Idle — waits indefinitely for player /steve approve or /steve halt.
    }

    private void runConstruction() {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            result = ActionResult.failure("PlanBuildAction.runConstruction must run on server level");
            return;
        }

        // Walk the same flattened order used by the dashboard snapshot, so the
        // 3D preview and the placed world are guaranteed to line up.
        int total = project.totalBlocks;
        if (project.nextBlockIndex >= total) {
            transitionTo(BuildPhase.COMPLETED);
            result = ActionResult.success(
                "Built " + project.blocksPlaced + "/" + total + " blocks for project #" + project.id);
            publishEvent(new PlanLogEvent(
                project.id,
                PlanLogEvent.Severity.INFO,
                "Construction complete: " + project.blocksPlaced + "/" + total));
            return;
        }

        int delay = Math.max(1, SteveConfig.BUILD_TICK_DELAY.get());
        if (constructionCooldown > 0) {
            constructionCooldown--;
            return;
        }

        if (placeNextBlock(serverLevel)) {
            constructionCooldown = delay;
        } else {
            // Try the same index again next tick (Steve still pathing, position
            // blocked, etc.). Don't burn the cooldown.
        }
    }

    /** Attempt to place the block at {@code project.nextBlockIndex}.
     *  @return true if a block was actually placed (resets the per-block cooldown);
     *          false if Steve is out of range, the target cell is occupied by a
     *          non-air non-liquid block, or the project has no block to place. */
    private boolean placeNextBlock(ServerLevel level) {
        int idx = project.nextBlockIndex;
        // Flatten templates × blocks into the same (templateIndex, blockIndex) order
        // that PlanDashboardServer.buildSnapshot() emits, then advance.
        int remaining = idx;
        BlockPos worldPos = null;
        net.minecraft.world.level.block.state.BlockState state = null;
        for (var tpl : project.templates) {
            if (remaining < tpl.blocks.size()) {
                BlockPos o = tpl.origin != null ? tpl.origin : BlockPos.ZERO;
                var tb = tpl.blocks.get(remaining);
                worldPos = o.offset(tb.relativePos);
                state = tb.blockState;
                break;
            }
            remaining -= tpl.blocks.size();
        }
        if (worldPos == null || state == null) {
            // Index out of range (project.totalBlocks shrunk since last tick).
            // Skip to end.
            project.nextBlockIndex = project.totalBlocks;
            return false;
        }

        // Move Steve into range first. Don't burn the index until he's there.
        if (!steve.blockPosition().closerThan(worldPos, 6.0)) {
            steve.getNavigation().moveTo(worldPos.getX() + 0.5, worldPos.getY(), worldPos.getZ() + 0.5, 1.0);
            return false;
        }

        net.minecraft.world.level.block.state.BlockState current = level.getBlockState(worldPos);
        if (!current.isAir() && !current.liquid()) {
            // Cell already occupied (e.g. worldgen placed something here) — skip.
            publishLog(PlanLogEvent.Severity.WARN,
                "Skipping " + worldPos + ": already " + current.getBlock().getName().getString());
            project.nextBlockIndex++;
            project.blocksPlaced++;
            return true;
        }

        level.setBlock(worldPos, state, 3);
        project.nextBlockIndex++;
        project.blocksPlaced++;
        if (project.blocksPlaced % 50 == 0 || project.blocksPlaced == project.totalBlocks) {
            publishLog(PlanLogEvent.Severity.INFO,
                "Construction progress: " + project.blocksPlaced + "/" + project.totalBlocks);
        }
        return true;
    }

    // ===== Player commands =====

    /** Called by ActionExecutor when player issues /steve approve. */
    public void approve() {
        if (project.phase != BuildPhase.AWAITING_DESIGN_APPROVAL) {
            SteveMod.LOGGER.warn("PlanBuildAction.approve() called in phase {} — ignoring", project.phase);
            return;
        }
        project.lastApproved = project.phase;
        SteveMod.LOGGER.info("BuildProject #{} approved at phase {}", project.id, project.phase);
        publishEvent(new PlanApprovedEvent(project.id, project.phase, "player"));
        publishLog(PlanLogEvent.Severity.INFO, "Approved by player at phase " + project.phase);
        // Drive construction directly — no second confirmation. result stays null
        // so BaseAction.isComplete() returns false and onTick keeps being invoked.
        transitionTo(BuildPhase.CONSTRUCTION);
    }

    /** Called by ActionExecutor when player issues /steve halt or timeout fires. */
    public void halt(String reason) {
        if (project.phase == BuildPhase.FAILED || project.phase == BuildPhase.COMPLETED) {
            return;
        }
        SteveMod.LOGGER.info("BuildProject #{} halted at phase {}: {}", project.id, project.phase, reason);

        // Archive halt record (design doc stays in mempalace — memory continuity)
        archiveToMempalace(BuildPhase.FAILED, "halted", BuildDesignFormatter.halted(project, reason));

        publishEvent(new PlanHaltedEvent(
            project.id, project.phase, reason,
            project.mempalaceRefs.get(BuildPhase.DESIGN),
            project.blocksPlaced, project.totalBlocks));
        publishLog(PlanLogEvent.Severity.WARN, "Halted: " + reason);

        result = ActionResult.failure(
            "Build halted at phase " + project.phase + ": " + reason
            + ". Design archived: " + project.mempalaceRefs.getOrDefault(BuildPhase.DESIGN, "(none)"),
            true);
        project.phase = BuildPhase.FAILED;
    }

    // ===== Helpers =====

    private void transitionTo(BuildPhase next) {
        BuildPhase prev = project.phase;
        project.phase = next;
        SteveMod.LOGGER.info("BuildProject #{} phase: {} -> {}", project.id, prev, next);
        publishEvent(new PlanPhaseChangedEvent(project.id, prev, next, null));
    }

    private void publishEvent(com.steve.ai.event.plan.PlanEvent event) {
        try {
            SteveMod.getPlanEventBus().publish(event);
        } catch (Exception e) {
            SteveMod.LOGGER.warn("Failed to publish plan event: {}", e.getMessage());
        }
    }

    private void publishLog(PlanLogEvent.Severity severity, String message) {
        publishEvent(new PlanLogEvent(project.id, severity, message));
    }

    private void archiveToMempalace(BuildPhase phase, String roomSuffix, String content) {
        try {
            String room = project.id + "_" + roomSuffix;
            String wing = switch (phase) {
                case DESIGN -> "build_designs";
                case FAILED -> "build_halted";
                case AWAITING_ACCEPTANCE -> "build_acceptance";
                case COMPLETED -> "built_structures";
                default -> "build_misc";
            };
            Map<String, Object> args = Map.of(
                "wing", wing,
                "room", room,
                "content", truncate(content, 8000),
                "added_by", "steve-ai"
            );
            String res = MCPToolRegistry.getInstance().callTool("mempalace:mempalace_add_drawer", args);
            String ref = "wing=" + wing + "/room=" + room;
            project.mempalaceRefs.put(phase, ref);
            SteveMod.LOGGER.info("Archived {} to mempalace {} (response: {})", phase, ref, truncate(res, 200));
        } catch (Exception e) {
            SteveMod.LOGGER.warn("Failed to archive {} to mempalace: {}", phase, e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
