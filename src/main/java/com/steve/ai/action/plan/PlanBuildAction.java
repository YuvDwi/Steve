package com.steve.ai.action.plan;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.action.actions.BaseAction;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.event.plan.PlanApprovedEvent;
import com.steve.ai.event.plan.PlanCreatedEvent;
import com.steve.ai.event.plan.PlanHaltedEvent;
import com.steve.ai.event.plan.PlanLogEvent;
import com.steve.ai.event.plan.PlanPhaseChangedEvent;
import com.steve.ai.llm.react.BuildPhase;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 四阶段构建编排器。
 *
 * <p>状态机由本类管理（FEASIBILITY → DESIGN → AWAITING_DESIGN_APPROVAL →
 * CONSTRUCTION → COMPLETED/FAILED），具体职责委托给：
 * <ul>
 *   <li>{@link BuildModuleSpecParser} — 解析 LLM 输入的模块规格</li>
 *   <li>{@link BuildDesignGenerator} — DESIGN 阶段：NBT 加载、位置解析、推送设计文档</li>
 *   <li>{@link BuildConstructionLoop} — CONSTRUCTION 阶段：tick 节奏放方块</li>
 *   <li>{@link ProjectArchiveService} — mempalace 归档 / JSON 序列化</li>
 * </ul>
 *
 * <p>本类保留：构造器（构建 BuildProject）、状态转换、玩家命令（{@link #approve()}、
 * {@link #halt(String)}）、事件总线基础设施（{@link #transitionTo} / {@link #publishEvent}）。
 */
public class PlanBuildAction extends BaseAction {

    private final BuildProject project;

    private BuildDesignGenerator designGenerator;
    private BuildConstructionLoop constructionLoop;

    public PlanBuildAction(SteveEntity steve, Task task, ActionExecutor executor) {
        super(steve, task);

        List<Map<String, Object>> moduleList = BuildModuleSpecParser.parse(task);
        List<String> names = BuildModuleSpecParser.extractNames(moduleList);
        String label = names.isEmpty() ? "unknown" : names.get(0);
        this.project = new BuildProject(steve, label, names);
        this.designGenerator = new BuildDesignGenerator(steve, project, moduleList);

        // 触发 PlanCreatedEvent 以便仪表盘能立即识别该项目
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
            case AWAITING_ACCEPTANCE, COMPLETED, FAILED, FEASIBILITY -> { /* 终止 / 未使用 */ }
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

    // ===== 阶段 2：DESIGN =====

    private void runDesign() {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            result = ActionResult.failure("PlanBuildAction must run on server level");
            return;
        }

        if (!designGenerator.loadAndPlace(serverLevel)) {
            result = ActionResult.failure(
                "None of the requested NBT templates could be loaded");
            return;
        }

        designGenerator.publishDesign();

        // 归档到 mempalace（结构化 JSON：{modules[], materials, totalBlocks, ...}）
        String ref = ProjectArchiveService.archive(project, BuildPhase.DESIGN, "design",
            ProjectArchiveService.serialize(project, steve.getSteveName(), BuildPhase.DESIGN, null));
        if (ref != null) {
            project.mempalaceRefs.put(BuildPhase.DESIGN, ref);
        }

        transitionTo(BuildPhase.AWAITING_DESIGN_APPROVAL);
    }

    private void runAwaitingApproval() {
        // 空闲 —— 无限期等待玩家的 /steve approve 或 /steve halt
    }

    private void runConstruction() {
        if (!(steve.level() instanceof ServerLevel serverLevel)) {
            result = ActionResult.failure("PlanBuildAction.runConstruction must run on server level");
            return;
        }

        if (constructionLoop == null) {
            constructionLoop = new BuildConstructionLoop(steve, project);
        }

        if (constructionLoop.tick(serverLevel)) {
            transitionTo(BuildPhase.COMPLETED);
            result = ActionResult.success(
                "Built " + project.blocksPlaced + "/" + project.totalBlocks + " blocks for project #" + project.id);
            publishLog(PlanLogEvent.Severity.INFO,
                "Construction complete: " + project.blocksPlaced + "/" + project.totalBlocks);
        }
    }

    // ===== 玩家命令 =====

    public void approve() {
        if (project.phase != BuildPhase.AWAITING_DESIGN_APPROVAL) {
            SteveMod.LOGGER.warn("PlanBuildAction.approve() called in phase {} — ignoring", project.phase);
            return;
        }
        project.lastApproved = project.phase;
        SteveMod.LOGGER.info("BuildProject #{} approved at phase {}", project.id, project.phase);
        publishEvent(new PlanApprovedEvent(project.id, project.phase, "player"));
        publishLog(PlanLogEvent.Severity.INFO, "Approved by player at phase " + project.phase);
        transitionTo(BuildPhase.CONSTRUCTION);
    }

    public void halt(String reason) {
        if (project.phase == BuildPhase.FAILED || project.phase == BuildPhase.COMPLETED) {
            return;
        }
        SteveMod.LOGGER.info("BuildProject #{} halted at phase {}: {}", project.id, project.phase, reason);

        // 归档停止记录（设计文档保留在 mempalace —— 内存连续性）
        String ref = ProjectArchiveService.archive(project, BuildPhase.FAILED, "halted",
            ProjectArchiveService.serialize(project, steve.getSteveName(), project.phase, reason));
        if (ref != null) {
            project.mempalaceRefs.put(BuildPhase.FAILED, ref);
        }

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

    // ===== 事件基础设施 =====

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
}
