package com.steve.ai.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wrapper around MCP SDK's McpSyncClient with HTTP transport.
 */
public class MCPClientWrapper {

    private static final Logger LOGGER = LogManager.getLogger(MCPClientWrapper.class);

    private final String serverName;
    private final String serverUrl;
    private final long timeoutMs;
    private io.modelcontextprotocol.client.McpSyncClient client;

    public MCPClientWrapper(String serverName, String serverUrl) {
        this(serverName, serverUrl, 30000);
    }

    public MCPClientWrapper(String serverName, String serverUrl, long timeoutMs) {
        this.serverName = serverName;
        this.serverUrl = serverUrl;
        this.timeoutMs = timeoutMs;
    }

    public void initialize() {
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl)
                .endpoint("/mcp")
                .build();

            client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(timeoutMs))
                .capabilities(McpSchema.ClientCapabilities.builder()
                    .roots(true)
                    .sampling()
                    .elicitation()
                    .build())
                .sampling(request -> new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("sampling response"), null, null, null))
                .elicitation(request -> new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, null))
                .build();

            client.initialize();
            LOGGER.info("MCP client '{}' initialized", serverName);

        } catch (Exception e) {
            LOGGER.error("Failed to create MCP client for server '{}'", serverName, e);
        }
    }

    /**
     * List all available tools from this server.
     */
    public List<MCPToolConverter.ToolInfo> listTools() {
        if (client == null) {
            return List.of();
        }

        try {
            McpSchema.ListToolsResult result = client.listTools();
            List<MCPToolConverter.ToolInfo> tools = new ArrayList<>();
            for (McpSchema.Tool tool : result.tools()) {
                String inputSchema = tool.inputSchema() != null
                    ? tool.inputSchema().toString()
                    : "";
                tools.add(new MCPToolConverter.ToolInfo(
                    tool.name(),
                    tool.description(),
                    inputSchema
                ));
            }
            return tools;
        } catch (Exception e) {
            LOGGER.error("Failed to list tools from MCP server '{}'", serverName, e);
            return List.of();
        }
    }

    /**
     * Call a tool with the given arguments.
     * Returns JSON string result.
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (client == null) {
            return "{\"error\": \"MCP client not initialized\"}";
        }

        try {
            McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(toolName, arguments)
            );

            if (result.isError() != null && result.isError()) {
                return "{\"error\": \"" + escapeJson(result.content().toString()) + "\"}";
            }
            return result.content().toString();
        } catch (Exception e) {
            LOGGER.error("Failed to call tool '{}' on MCP server '{}'", toolName, serverName, e);
            return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    /**
     * Close the MCP client connection.
     */
    public void close() {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
            LOGGER.info("MCP client '{}' closed", serverName);
        } catch (Exception e) {
            LOGGER.warn("Error closing MCP client '{}': {}", serverName, e.getMessage());
        } finally {
            client = null;
        }
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isInitialized() {
        return client != null;
    }
}
