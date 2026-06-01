package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.mcp.MCPToolRegistry;

import java.util.Map;

/**
 * Action that executes an MCP tool call.
 * The task parameters should contain:
 * - tool: full tool name in format "serverName:toolName"
 * - args: map of arguments to pass to the tool (optional)
 */
public class MCPAction extends BaseAction {

    public MCPAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        Map<String, Object> params = task.getParameters();
        Object toolObj = params.get("tool");

        if (toolObj == null) {
            result = ActionResult.failure("MCP tool name not specified");
            return;
        }

        String toolName = toolObj.toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("args", Map.of());

        SteveMod.LOGGER.info("Executing MCP tool: {} with args: {}", toolName, args);

        try {
            SteveMod mcp = null;
            String response = MCPToolRegistry.getInstance().callTool(toolName, args);
            SteveMod.LOGGER.info("MCP tool '{}' result: {}", toolName, response);
            result = ActionResult.success("MCP tool executed: " + response);
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to execute MCP tool '{}'", toolName, e);
            result = ActionResult.failure("MCP tool failed: " + e.getMessage());
        }
    }

    @Override
    protected void onTick() {
        // MCP calls are synchronous, should be complete after onStart
    }

    @Override
    protected void onCancel() {
        // Cannot cancel a completed MCP call
    }

    @Override
    public String getDescription() {
        Object toolObj = task.getParameters().get("tool");
        return "MCP tool: " + (toolObj != null ? toolObj.toString() : "unknown");
    }
}
