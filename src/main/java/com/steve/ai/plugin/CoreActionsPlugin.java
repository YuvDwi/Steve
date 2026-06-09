package com.steve.ai.plugin;

import com.steve.ai.action.ActionExecutor;
import com.steve.ai.action.actions.*;
import com.steve.ai.action.plan.PlanBuildAction;
import com.steve.ai.di.ServiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.steve.ai.plugin.ActionSchema.required;
import static com.steve.ai.plugin.ActionSchema.optional;

/**
 * Core plugin that registers all built-in Steve AI actions.
 *
 * <p>This plugin is loaded first (priority 1000) and provides the fundamental
 * actions that Steve can perform: mining, building, combat, pathfinding, etc.</p>
 *
 * <p><b>Registered Actions:</b></p>
 * <ul>
 *   <li><b>pathfind</b>: Navigate to coordinates (x, y, z)</li>
 *   <li><b>mine</b>: Mine blocks (block type, quantity)</li>
 *   <li><b>place</b>: Place blocks at coordinates</li>
 *   <li><b>craft</b>: Craft items (item, quantity)</li>
 *   <li><b>attack</b>: Attack entities (target)</li>
 *   <li><b>follow</b>: Follow a player</li>
 *   <li><b>gather</b>: Gather resources (resource, quantity)</li>
 *   <li><b>build</b>: Build structures (structure type, blocks, dimensions)</li>
 * </ul>
 *
 * @since 1.1.0
 * @see ActionPlugin
 */
public class CoreActionsPlugin implements ActionPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(CoreActionsPlugin.class);

    private static final String PLUGIN_ID = "core-actions";
    private static final String VERSION = "1.0.0";

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void onLoad(ActionRegistry registry, ServiceContainer container) {
        LOGGER.info("Loading CoreActionsPlugin v{}", VERSION);

        int priority = getPriority();

        registry.register("pathfind",
            new ActionSchema("pathfind", "Navigate to coordinates",
                List.of(required("x", "int", "X坐标"), required("y", "int", "Y坐标"), required("z", "int", "Z坐标")),
                "{\"x\": <int>, \"y\": <int>, \"z\": <int>}"),
            (steve, task, ctx) -> new PathfindAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("mine",
            new ActionSchema("mine", "Mine blocks",
                List.of(required("block", "string", "资源名"), required("quantity", "int", "数量")),
                "{\"block\": \"<资源名>\", \"quantity\": <int>}"),
            (steve, task, ctx) -> new MineBlockAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("gather",
            new ActionSchema("gather", "Gather resources",
                List.of(required("resource", "string", "资源名"), required("quantity", "int", "数量")),
                "{\"resource\": \"<资源名>\", \"quantity\": <int>}"),
            (steve, task, ctx) -> new GatherResourceAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("place",
            new ActionSchema("place", "Place a block at coordinates",
                List.of(required("block", "string", "方块名"), required("x", "int", "X"), required("y", "int", "Y"), required("z", "int", "Z")),
                "{\"block\": \"<方块名>\", \"x\": <int>, \"y\": <int>, \"z\": <int>}"),
            (steve, task, ctx) -> new PlaceBlockAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("craft",
            new ActionSchema("craft", "Craft items",
                List.of(required("item", "string", "物品"), required("quantity", "int", "数量")),
                "{\"item\": \"<物品>\", \"quantity\": <int>}"),
            (steve, task, ctx) -> new CraftItemAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("attack",
            new ActionSchema("attack", "Attack entities",
                List.of(required("target", "string", "hostile|生物名")),
                "{\"target\": \"hostile|生物名\"}"),
            (steve, task, ctx) -> new CombatAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("follow",
            new ActionSchema("follow", "Follow a player",
                List.of(required("player", "string", "玩家名")),
                "{\"player\": \"<玩家名>\"}"),
            (steve, task, ctx) -> new FollowPlayerAction(steve, task),
            priority, PLUGIN_ID);

        registry.register("build",
            new ActionSchema("build", "Build structures from NBT templates",
                List.of(
                    optional("structure", "string", "单模板名（便捷形式）"),
                    optional("structures", "array", "[{name,dx,dy,dz,facing}] 模块拼装协议")),
                "{\"structure\": \"<模板名>\"} 或 {\"structures\": [{\"name\":\"<n>\",\"dx\":<int>,\"dy\":<int>,\"dz\":<int>,\"facing\":\"N|E|S|W\"}]}",
                t -> t.hasParameters("structure") || t.hasParameters("structures")),
            (steve, task, ctx) -> {
                ActionExecutor executor = ctx.getService(ActionExecutor.class);
                return new PlanBuildAction(steve, task, executor);
            },
            priority, PLUGIN_ID);

        registry.register("mcp",
            new ActionSchema("mcp", "Call MCP tools",
                List.of(required("tool", "string", "serverName:toolName"), optional("args", "object", "工具参数")),
                "{\"tool\": \"<serverName:toolName>\", \"args\": {<args>}}"),
            (steve, task, ctx) -> new MCPAction(steve, task),
            priority, PLUGIN_ID);

        LOGGER.info("CoreActionsPlugin loaded {} actions", registry.getActionCount());
    }

    @Override
    public void onUnload() {
        LOGGER.info("CoreActionsPlugin unloading");
    }

    @Override
    public int getPriority() {
        return 1000; // Core plugin - highest priority
    }

    @Override
    public String[] getDependencies() {
        return new String[0]; // No dependencies - this is the base plugin
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public String getDescription() {
        return "Core Steve AI actions: mining, building, combat, pathfinding, and more";
    }
}
