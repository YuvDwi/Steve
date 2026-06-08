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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.steve.ai.mcp.MCPToolRegistry;
import com.steve.ai.structure.BlockPlacement;
import com.steve.ai.structure.ModuleTransform;
import com.steve.ai.structure.PlacedModule;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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

    /** JSON serializer for mempalace archive payloads. {@code disableHtmlEscaping}
     *  keeps block IDs like {@code minecraft:stone} readable (no {@code :} for
     *  the colon) — mirrors the pattern in {@code PlanEventJson.GSON}. */
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public PlanBuildAction(SteveEntity steve, Task task, ActionExecutor executor) {
        super(steve, task);
        this.executor = executor;

        // Module-composition protocol is the only accepted shape. The
        // LLM emits a list of {name, dx, dy, dz, facing} objects under
        // parameters.structures. For the single-template convenience form
        // (parameters.structure), we wrap it into a one-element module
        // spec with default offsets and facing S.
        List<Map<String, Object>> moduleList = task.getModuleListParameter("structures");
        if (moduleList == null || moduleList.isEmpty()) {
            String single = task.getStringParameter("structure");
            if (single == null || single.isEmpty()) {
                single = task.getStringParameter("structure", "unknown");
            }
            moduleList = new ArrayList<>(1);
            Map<String, Object> spec = new java.util.HashMap<>();
            spec.put("name", single);
            // dx/dy/dz/facing absent -> defaults applied in runDesign.
            moduleList.add(spec);
        }

        // Plan-mode fallback: if the LLM returned exactly one module (legacy
        // `structure: "X"` wrap or a one-entry structures array), try to
        // compose same-type siblings from StructureTemplateLoader. LLM-emitted
        // multi-entry lists skip this block entirely — we only rescue the
        // "LLM ignored the ≥2 rule" case. The single-piece convenience path
        // ("放 房子_1") is preserved when no siblings exist. Logic is in a
        // static helper so tests can exercise it without instantiating the
        // full action (which needs SteveEntity and a real event bus).
        moduleList = expandSingleStructureFallback(moduleList,
            () -> SteveConfig.MAX_TEMPLATES_PER_PLAN.get());

        int cap = SteveConfig.MAX_TEMPLATES_PER_PLAN.get();
        if (moduleList.size() > cap) {
            SteveMod.LOGGER.warn("PlanBuildAction: LLM requested {} modules, capping to {}",
                moduleList.size(), cap);
            moduleList = new ArrayList<>(moduleList.subList(0, cap));
        }

        // Extract a name list for the project record (mempalace archive,
        // dashboard display). The full module specs are stashed on the
        // instance for runDesign to walk.
        List<String> names = new ArrayList<>(moduleList.size());
        for (Map<String, Object> m : moduleList) {
            Object n = m.get("name");
            if (n != null) names.add(n.toString());
        }
        String label = names.isEmpty() ? "unknown" : names.get(0);
        this.project = new BuildProject(steve, label, names);
        this.pendingModuleSpecs = moduleList;

        // Fire PlanCreatedEvent so the external HTML dashboard can pick up the
        // project as soon as the action is constructed.
        publishEvent(new PlanCreatedEvent(
            project.id, steve.getSteveName(), project.command,
            project.selectedTemplates, project.phase));
    }

    /** Module specs from the {name, dx, dy, dz, facing} protocol, set in
     *  the constructor and walked by {@link #runDesign()}. Never null:
     *  even a single-structure request is wrapped into a one-element
     *  module spec at construction time. */
    private List<Map<String, Object>> pendingModuleSpecs;

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

        // 2. Walk the spec list. For each entry, load the NBT, resolve
        //    its world origin via ModuleTransform.apply(localIn,
        //    prevExit, prevFacing), and append a PlacedModule to the
        //    project. Skip-on-miss: a single failed NBT drops the
        //    entry from selectedTemplates and the world keeps building.
        List<Map<String, Object>> specs = pendingModuleSpecs;
        BlockPos prevExit = groundPos;
        PlacedModule.Facing prevFacing = PlacedModule.Facing.S;
        List<String> survivors = new ArrayList<>();

        for (Map<String, Object> spec : specs) {
            Object n = spec.get("name");
            String name = n == null ? null : n.toString();
            if (name == null) continue;
            int dx = readInt(spec, "dx", 0);
            int dy = readInt(spec, "dy", 0);
            int dz = readInt(spec, "dz", 0);
            PlacedModule.Facing facing = readFacing(spec, "facing", PlacedModule.Facing.S);

            StructureTemplateLoader.LoadedTemplate tpl = StructureTemplateLoader.loadFromNBT(serverLevel, name);
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

            // Advance the cursor to this module's world exit.
            prevExit = ModuleTransform.apply(
                ModuleTransform.exitAnchor(tpl, facing), worldIn, facing);
            prevFacing = facing;
        }
        project.selectedTemplates.clear();
        project.selectedTemplates.addAll(survivors);

        if (project.placedModules.isEmpty()) {
            result = ActionResult.failure(
                "None of the requested NBT templates could be loaded: " + specs);
            return;
        }

        // 4. Push design doc to nearest player
        String design = BuildDesignFormatter.fullDesign(project);
        if (nearest != null) {
            for (String line : design.replace("\r\n", "\n").split("\n")) {
                nearest.sendSystemMessage(Component.literal(line));
            }
        } else {
            SteveMod.LOGGER.info("Design doc (no player to message):\n{}", design);
        }

        // 4b. Mirror design to the external dashboard. We flatten every
        //     loaded module's blocks into a single world-space list so the
        //     front-end can render the whole structure in Three.js without
        //     knowing about per-module origins or facings — every block's
        //     world position goes through ModuleTransform.apply.
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
        publishEvent(new PlanDesignReadyEvent(
            project.id, design,
            PlanDesignReadyEvent.MaterialEntry.fromBlockMap(project.materials, project.totalBlocks),
            project.totalBlocks,
            blocks));

        // 5. Archive to mempalace (structured JSON: {modules[], materials, totalBlocks, ...})
        archiveToMempalace(BuildPhase.DESIGN, "design", serializeProject(project, steve.getSteveName(), BuildPhase.DESIGN, null));

        // 6. Wait for approval (no auto-timeout — player must /steve approve or /steve halt)
        transitionTo(BuildPhase.AWAITING_DESIGN_APPROVAL);
    }

    /** Coerce a JSON / map value to an int, with a default. Handles both
     *  {@code Number} (Gson emits {@code Double} for integer numerics in
     *  some paths) and {@code String} inputs — the latter for the rare
     *  case where the LLM serialises an int as a quoted string. */
    static int readInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return def; }
        }
        return def;
    }

    static PlacedModule.Facing readFacing(Map<String, Object> m, String key, PlacedModule.Facing def) {
        Object v = m.get(key);
        if (v == null) return def;
        String s = v.toString().trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "N", "NORTH" -> PlacedModule.Facing.N;
            case "E", "EAST"  -> PlacedModule.Facing.E;
            case "S", "SOUTH" -> PlacedModule.Facing.S;
            case "W", "WEST"  -> PlacedModule.Facing.W;
            default -> def;
        };
    }

    /** Serialize a {@link BuildProject} as a structured JSON object for mempalace.
     *  Shape: {schemaVersion, projectId, command, phase, steveName, createdAtMs,
     *  origin, modules[], materials, totalBlocks, [halted{...}]}.
     *  The {@code modules} array contains one entry per {@link PlacedModule} with
     *  the full NBT blocks already resolved to world coordinates through
     *  {@link ModuleTransform#apply}. {@code haltReasonOrNull} non-null adds a
     *  {@code halted} object with the halt metadata and omits the (large) per-block
     *  list — the HALT drawer is metadata-only because the full block layout is
     *  already archived in the DESIGN drawer for the same project.
     *
     *  <p>{@code steveName} is taken as a parameter (rather than read from
     *  {@code p.steve.getSteveName()}) so this method is testable without a live
     *  Minecraft server: callers pass {@code project.steve.getSteveName()}.</p> */
    static JsonObject serializeProject(BuildProject p, String steveName, BuildPhase phase, String haltReasonOrNull) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("projectId", p.id);
        root.addProperty("command", p.command);
        root.addProperty("phase", phase.name());
        root.addProperty("steveName", steveName);
        root.addProperty("createdAtMs", p.createdAtMs);
        if (p.originPos != null) {
            root.add("origin", blockPosToJson(p.originPos));
        }

        JsonArray modules = new JsonArray();
        boolean includeBlocks = haltReasonOrNull == null;
        for (PlacedModule pm : p.placedModules) {
            JsonObject m = new JsonObject();
            m.addProperty("name", pm.template.name);
            m.addProperty("facing", pm.facing.name());
            m.add("worldOrigin", blockPosToJson(pm.worldOrigin));
            m.add("worldExit", blockPosToJson(pm.worldExit()));
            m.addProperty("width", pm.template.width);
            m.addProperty("height", pm.template.height);
            m.addProperty("depth", pm.template.depth);
            if (includeBlocks) {
                JsonArray blocks = new JsonArray();
                for (var tb : pm.template.blocks) {
                    BlockPos worldPos = ModuleTransform.apply(tb.relativePos, pm.worldOrigin, pm.facing);
                    JsonObject b = new JsonObject();
                    b.addProperty("x", worldPos.getX());
                    b.addProperty("y", worldPos.getY());
                    b.addProperty("z", worldPos.getZ());
                    b.addProperty("blockId",
                        tb.blockState.getBlock().builtInRegistryHolder().key().location().toString());
                    blocks.add(b);
                }
                m.add("blocks", blocks);
            }
            modules.add(m);
        }
        root.add("modules", modules);

        JsonObject materials = new JsonObject();
        for (var e : p.materials.entrySet()) {
            String id = BuiltInRegistries.BLOCK.getKey(e.getKey()).toString();
            materials.addProperty(id, e.getValue());
        }
        root.add("materials", materials);

        root.addProperty("totalBlocks", p.totalBlocks);

        if (haltReasonOrNull != null) {
            JsonObject halted = new JsonObject();
            halted.addProperty("reason", haltReasonOrNull);
            halted.addProperty("blocksPlaced", p.blocksPlaced);
            halted.addProperty("totalBlocks", p.totalBlocks);
            halted.addProperty("fromPhase", p.phase.name());
            root.add("halted", halted);
        }

        return root;
    }

    private static JsonObject blockPosToJson(BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
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
        // Flatten placedModules × blocks into the same (moduleIndex, blockIndex)
        // order that runDesign / PlanDashboardServer.buildSnapshot emit, so the
        // 3D preview and the placed world are guaranteed to line up. World
        // coordinates go through ModuleTransform.apply — the single source of
        // truth for rotation.
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

        // Archive halt record (design doc stays in mempalace — memory continuity).
        // Same JSON schema as DESIGN, plus a `halted` object; per-block list is
        // omitted because the DESIGN drawer already carries the full layout.
        archiveToMempalace(BuildPhase.FAILED, "halted", serializeProject(project, steve.getSteveName(), project.phase, reason));

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

    private void archiveToMempalace(BuildPhase phase, String roomSuffix, com.google.gson.JsonObject payload) {
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
                "content", GSON.toJson(payload),
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

    /**
     * Plan-mode fallback: if {@code moduleList} has exactly one entry, try to
     * compose same-type siblings from {@link StructureTemplateLoader}. Returns
     * a new list when expansion fires; returns the input list unchanged
     * otherwise. Package-private + static so the rule can be tested without a
     * live {@code SteveEntity} or event bus.
     *
     * <p>{@code capSupplier} reads {@code MAX_TEMPLATES_PER_PLAN} at call
     * time so test code can inject a different cap without poking the static
     * config value.</p>
     */
    static List<Map<String, Object>> expandSingleStructureFallback(
            List<Map<String, Object>> moduleList,
            java.util.function.IntSupplier capSupplier) {
        if (moduleList == null || moduleList.size() != 1) {
            return moduleList;
        }
        Object nameObj = moduleList.get(0).get("name");
        if (nameObj == null) {
            return moduleList;
        }
        String headName = nameObj.toString();
        List<String> siblings =
            StructureTemplateLoader.getSiblingStructuresOfSameType(headName);
        if (siblings == null) {
            SteveMod.LOGGER.warn(
                "PlanBuildAction: single structure '{}' not found in StructureTemplateLoader, keeping 1-element list.",
                headName);
            return moduleList;
        }
        if (siblings.size() <= 1) {
            return moduleList;
        }
        return composeFromSiblings(headName, siblings,
            StructureTemplateLoader.getTypeFor(headName), capSupplier);
    }

    /**
     * Pure expansion logic: given the head template name, a pre-fetched list
     * of same-type siblings (in registration order, including the head), and
     * the type label, build a new expanded list with {@code headName} first
     * and the rest appended in order, capped by {@code capSupplier}.
     *
     * <p>Static + side-effect free (modulo logging) so unit tests can call it
     * directly without mocking {@code StructureTemplateLoader}.</p>
     */
    static List<Map<String, Object>> composeFromSiblings(
            String headName,
            List<String> siblings,
            String typeName,
            java.util.function.IntSupplier capSupplier) {
        // headName first, then the rest in registration order.
        List<String> ordered = new ArrayList<>(siblings.size());
        ordered.add(headName);
        for (String s : siblings) {
            if (!s.equals(headName)) ordered.add(s);
        }
        int fallbackCap = capSupplier.getAsInt();
        int keep = Math.min(ordered.size(), fallbackCap);
        if (ordered.size() > fallbackCap) {
            SteveMod.LOGGER.warn(
                "PlanBuildAction: same-type expansion produced {} templates, capping to {}",
                ordered.size(), fallbackCap);
        }
        List<Map<String, Object>> expanded = new ArrayList<>(keep);
        for (int i = 0; i < keep; i++) {
            Map<String, Object> spec = new java.util.HashMap<>();
            spec.put("name", ordered.get(i));
            expanded.add(spec);
        }
        SteveMod.LOGGER.warn(
            "PlanBuildAction: LLM returned single structure '{}' for plan-mode, "
          + "auto-expanding to {} templates of type '{}' (LLM ignored ≥2 rule).",
            headName, expanded.size(), typeName);
        return expanded;
    }
}
