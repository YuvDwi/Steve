package com.steve.ai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.steve.ai.SteveMod;
import com.steve.ai.action.BuildProject;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.dashboard.PlanDashboardServer;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.llm.react.BuildDesignFormatter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /steve} 根命令的 Brigadier 命令树。
 *
 * <p>三个功能分组：</p>
 * <ul>
 *   <li><b>Steve 生命周期</b>：{@code spawn / remove / list / stop} — 管理 Steve 实体</li>
 *   <li><b>LLM 任务派发</b>：{@code tell} — 给指定 Steve 发自然语言指令（启动 ReAct）</li>
 *   <li><b>先规划再施工工作流</b>：{@code plan / approve / halt / status} — 四阶段施工流程
 *       （可研 → 设计 → 施工 → 验收）</li>
 * </ul>
 */
public class SteveCommands {

    /**
     * 把完整的 {@code /steve} 命令树注册到 dispatcher。
     *
     * <p>命令清单：</p>
     * <table>
     *   <tr><th>命令</th><th>功能</th></tr>
     *   <tr><td>{@code /steve spawn <name>}</td>
     *       <td>在玩家面前 3 格处生成一个 Steve 实体</td></tr>
     *   <tr><td>{@code /steve remove <name>}</td>
     *       <td>移除已存在的 Steve</td></tr>
     *   <tr><td>{@code /steve list}</td>
     *       <td>列出所有活跃的 Steve</td></tr>
     *   <tr><td>{@code /steve stop <name>}</td>
     *       <td>强制取消 Steve 当前动作并清空任务队列</td></tr>
     *   <tr><td>{@code /steve tell <name> <command>}</td>
     *       <td>给指定 Steve 发自然语言指令（LLM ReAct）</td></tr>
     *   <tr><td>{@code /steve plan <description>}</td>
     *       <td>对最近的 Steve 走 LLM 规划模式 — 输出设计书，等待
     *           {@code /steve approve} 后才开始放方块</td></tr>
     *   <tr><td>{@code /steve approve}</td>
     *       <td>批准最近的 Steve 当前 BuildProject 待批准的阶段</td></tr>
     *   <tr><td>{@code /steve halt}</td>
     *       <td>中止最近的 Steve 当前 BuildProject，设计书留在 mempalace</td></tr>
     *   <tr><td>{@code /steve status}</td>
     *       <td>打印最近的 Steve 当前 BuildProject 的阶段、模板、进度</td></tr>
     * </table>
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("steve")
            // ----- Steve 生命周期 -----
            .then(Commands.literal("spawn")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::spawnSteve)))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::removeSteve)))
            .then(Commands.literal("list")
                .executes(SteveCommands::listSteves))
            .then(Commands.literal("stop")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(SteveCommands::stopSteve)))
            // ----- LLM 任务派发 -----
            .then(Commands.literal("tell")
                .then(Commands.argument("name", StringArgumentType.string())
                    .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(SteveCommands::tellSteve))))
            // ----- 先规划再施工工作流 -----
            // /steve plan <description> — LLM 选模板、出设计书、等玩家 approve
            .then(Commands.literal("plan")
                .then(Commands.argument("description", StringArgumentType.greedyString())
                    .executes(ctx -> SteveCommands.planBuild(
                        StringArgumentType.getString(ctx, "description"), ctx))))
            // 这三个不带 name 参数 — 自动作用于玩家附近有活跃 BuildProject 的最近 Steve
            .then(Commands.literal("approve")
                .executes(SteveCommands::approveBuild))
            .then(Commands.literal("halt")
                .executes(SteveCommands::haltBuild))
            .then(Commands.literal("status")
                .executes(SteveCommands::buildStatus))
            // ----- External HTML plan dashboard -----
            // /steve dashboard         — start the embedded HTTP server and print the URL
            // /steve dashboard stop    — stop the server
            .then(Commands.literal("dashboard")
                .executes(SteveCommands::startDashboard)
                .then(Commands.literal("stop")
                    .executes(SteveCommands::stopDashboard)))
        );
    }

    /**
     * {@code /steve spawn <name>} — 在玩家面前 3 格处生成一个 Steve 实体（控制台执行时
     * 退化为向东 3 格）。如果名字已存在或单玩家 Steve 数量达到上限则失败。
     */
    private static int spawnSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        ServerLevel serverLevel = source.getLevel();
        if (serverLevel == null) {
            source.sendFailure(Component.literal("Command must be run on server"));
            return 0;
        }

        SteveManager manager = SteveMod.getSteveManager();
        
        Vec3 sourcePos = source.getPosition();
        if (source.getEntity() != null) {
            Vec3 lookVec = source.getEntity().getLookAngle();
            sourcePos = sourcePos.add(lookVec.x * 3, 0, lookVec.z * 3);
        } else {
            sourcePos = sourcePos.add(3, 0, 0);
        }
        Vec3 spawnPos = sourcePos;
        
        SteveEntity steve = manager.spawnSteve(serverLevel, spawnPos, name);
        if (steve != null) {
            source.sendSuccess(() -> Component.literal("Spawned Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn Steve. Name may already exist or max limit reached."));
            return 0;
        }
    }

    /**
     * {@code /steve remove <name>} — 按名字移除一个 Steve 实体。如果当前没有该名字的
     * Steve 则返回失败提示。
     */
    private static int removeSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        if (manager.removeSteve(name)) {
            source.sendSuccess(() -> Component.literal("Removed Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    /**
     * {@code /steve list} — 列出所有活跃的 Steve 名字和数量。只读，无副作用，不广播给其他玩家。
     */
    private static int listSteves(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        SteveManager manager = SteveMod.getSteveManager();
        
        var names = manager.getSteveNames();
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active Steves"), false);
        } else {
            source.sendSuccess(() -> Component.literal("Active Steves (" + names.size() + "): " + String.join(", ", names)), false);
        }
        return 1;
    }

    /**
     * {@code /steve stop <name>} — 硬中断指定 Steve 当前正在做的任何事情：停止当前动作、
     * 清空记忆中的任务队列、广播完成状态。与 {@code /steve halt} 的区别：stop 是通用的硬
     * 中断，halt 是 build-aware 的并会归档到 mempalace。
     */
    private static int stopSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        
        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);
        
        if (steve != null) {
            steve.getActionExecutor().stopCurrentAction();
            steve.getMemory().clearTaskQueue();
            source.sendSuccess(() -> Component.literal("Stopped Steve: " + name), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    /**
     * {@code /steve tell <name> <command>} — 把自然语言指令派发给指定的 Steve。LLM 调用
     * 在后台线程执行（非阻塞），聊天线程立即返回。Steve 的 ReAct agent 之后会按步驱动响应。
     */
    private static int tellSteve(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String command = StringArgumentType.getString(context, "command");
        CommandSourceStack source = context.getSource();

        SteveManager manager = SteveMod.getSteveManager();
        SteveEntity steve = manager.getSteve(name);

        if (steve != null) {
            // Disabled command feedback message
            // source.sendSuccess(() -> Component.literal("Instructing " + name + ": " + command), true);

            new Thread(() -> {
                steve.getActionExecutor().processNaturalLanguageCommand(command);
            }).start();

            return 1;
        } else {
            source.sendFailure(Component.literal("Steve not found: " + name));
            return 0;
        }
    }

    // ===== Plan-then-build subcommands =====

    /**
     * 找玩家附近、且当前有活跃 {@link BuildProject} 的最近 Steve。{@code /steve approve}、
     * {@code /steve halt}、{@code /steve status} 这三个命令都靠这个 helper 隐式选目标
     * （都不带 name 参数）。
     *
     * @return 最近的、有活跃 build 的 Steve；没有则返回 {@code null}
     */
    private static SteveEntity findSteveWithActiveBuild(Player player) {
        SteveManager manager = SteveMod.getSteveManager();
        if (player == null) return null;
        SteveEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (String name : manager.getSteveNames()) {
            SteveEntity s = manager.getSteve(name);
            if (s == null) continue;
            if (s.getActionExecutor().getActiveBuildProject() == null) continue;
            double d = player.distanceTo(s);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = s;
            }
        }
        return nearest;
    }

    /** Locate the nearest Steve to the issuing player, regardless of whether
     *  that Steve is currently busy. Mirrors {@link #findSteveWithActiveBuild}
     *  but drops the "has active build" filter — {@code /steve plan} is for
     *  kicking off a new build, so the Steve may be idle or already mid-task. */
    private static SteveEntity findNearestSteve(Player player) {
        SteveManager manager = SteveMod.getSteveManager();
        if (player == null) return null;
        SteveEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (String name : manager.getSteveNames()) {
            SteveEntity s = manager.getSteve(name);
            if (s == null) continue;
            double d = player.distanceTo(s);
            if (d < nearestDist) {
                nearestDist = d;
                nearest = s;
            }
        }
        return nearest;
    }

    /**
     * {@code /steve approve} — 批准最近 Steve 当前 BuildProject 待批准的阶段。当前用于
     * {@code AWAITING_DESIGN_APPROVAL}（PR2 落地后还会用于 {@code AWAITING_ACCEPTANCE}）。
     * 如果没有 Steve 在等待批准则是 no-op。
     */
    private static int approveBuild(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayer();
        SteveEntity steve = findSteveWithActiveBuild(player);
        if (steve == null) {
            source.sendFailure(Component.literal("No Steve currently awaiting approval"));
            return 0;
        }
        steve.getActionExecutor().approveCurrentBuild();
        source.sendSuccess(() -> Component.literal("Approved build for " + steve.getSteveName()), true);
        return 1;
    }

    /**
     * {@code /steve halt} — 在任意阶段中止最近 Steve 的活跃 BuildProject。BuildProject
     * 转为 {@code FAILED}，中止原因归档到 mempalace 的 {@code build_halted} wing，
     * {@code build_designs} wing 的设计书保留（记忆连续）。已放置的方块**不**回滚 —
     * 那是单独的 undo 流程。
     */
    private static int haltBuild(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayer();
        SteveEntity steve = findSteveWithActiveBuild(player);
        if (steve == null) {
            source.sendFailure(Component.literal("No Steve currently in a build to halt"));
            return 0;
        }
        steve.getActionExecutor().haltCurrentBuild("player halted via /steve halt");
        source.sendSuccess(() -> Component.literal("Halted build for " + steve.getSteveName() + " (design archived)"), true);
        return 1;
    }

    /**
     * {@code /steve status} — 打印最近 Steve 当前 BuildProject 的一行状态摘要：阶段、选中模板、
     * 施工进度（已放/总块数）。只读 debug 命令。即使没有活跃 project 也返回成功 (1)，
     * 并输出 "No active build project." 提示。
     */
    private static int buildStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayer();
        SteveEntity steve = findSteveWithActiveBuild(player);
        if (steve == null) {
            source.sendSuccess(() -> Component.literal("No active build project."), false);
            return 1;
        }
        BuildProject project = steve.getActionExecutor().getActiveBuildProject();
        String text = BuildDesignFormatter.header(project) + System.lineSeparator()
                    + "状态: " + project.phase
                    + ", 模板: " + (project.selectedTemplates.isEmpty()
                        ? "(none)"
                        : String.join("+", project.selectedTemplates))
                    + ", 进度: " + project.blocksPlaced + "/" + project.totalBlocks;
        for (String line : text.split("\n")) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    // ===== /steve plan <description> — LLM-driven plan-mode entry =====

    /**
     * Brigadier command body for {@code /steve plan <description>}: locate the
     * nearest Steve to the issuing player and hand the description to
     * {@link com.steve.ai.action.ActionExecutor#startPlannedBuild}. The actual
     * plan-mode prompt template lives in {@code PromptBuilder.buildPlanPrompt}
     * — this method is just command-layer plumbing.
     */
    private static int planBuild(String description, CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Player player = source.getPlayer();
        SteveEntity steve = findNearestSteve(player);
        if (steve == null) {
            source.sendFailure(Component.literal("No Steve found nearby — /steve spawn <name> first"));
            return 0;
        }
        steve.getActionExecutor().startPlannedBuild(description);
        source.sendSuccess(() -> Component.literal(
            "Planning '" + description + "' for " + steve.getSteveName()
            + " — LLM will pick template and emit design doc, then wait for /steve approve"),
            true);
        return 1;
    }

    // ===== /steve dashboard — embedded HTTP server for the external plan UI =====

    /**
     * {@code /steve dashboard} — 启动一个嵌入式 HTTP server,服务 127.0.0.1 上的 plan
     * dashboard 页面。幂等：已启动时只打印 URL 提示。**不**自动打开浏览器——按设计决策。
     */
    private static int startDashboard(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PlanDashboardServer existing = SteveMod.getDashboardServer();
        String frontendUrl = SteveConfig.DASHBOARD_FRONTEND_URL.get();
        if (existing != null && existing.isRunning()) {
            source.sendSuccess(() -> Component.literal(
                "Plan dashboard already running. Open " + frontendUrl + " in your browser."),
                false);
            return 1;
        }
        int port = SteveConfig.DASHBOARD_PORT.get();
        PlanDashboardServer server = new PlanDashboardServer(port);
        try {
            server.start();
            SteveMod.setDashboardServer(server);
            source.sendSuccess(() -> Component.literal(
                "Plan dashboard backend started on 127.0.0.1:" + port
                    + ". Open " + frontendUrl + " in your browser."),
                true);
            return 1;
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to start plan dashboard: {}", e.getMessage(), e);
            source.sendFailure(Component.literal("Failed to start plan dashboard: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * {@code /steve dashboard stop} — 关闭嵌入式 HTTP server。
     */
    private static int stopDashboard(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PlanDashboardServer server = SteveMod.getDashboardServer();
        if (server == null) {
            source.sendFailure(Component.literal("Plan dashboard is not running"));
            return 0;
        }
        try {
            server.stop();
        } finally {
            SteveMod.setDashboardServer(null);
        }
        source.sendSuccess(() -> Component.literal("Plan dashboard stopped"), true);
        return 1;
    }
}

