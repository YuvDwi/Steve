package com.steve.ai.mcp;

import java.util.List;

/**
 * Converts MCP tools to prompt-friendly string format.
 */
public class MCPToolConverter {

    private MCPToolConverter() {}

    /**
     * Convert a list of tools to a prompt section describing available tools.
     */
    public static String toPromptSection(List<ToolInfo> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== MCP TOOLS ===\n");

        for (ToolInfo tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description());
            if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
                sb.append(" | args: ").append(tool.inputSchema());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Tool information extracted from MCP server.
     */
    public record ToolInfo(String name, String description, String inputSchema) {}
}
