package com.steve.ai.mcp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MCP server connections and aggregates tools from all servers.
 */
public class MCPToolRegistry {

    private static final Gson GSON = new Gson();
    private final Map<String, MCPClientWrapper> clients = new ConcurrentHashMap<>();
    private final Map<String, List<MCPToolConverter.ToolInfo>> toolsByServer = new ConcurrentHashMap<>();

    public MCPToolRegistry() {
        initialize();
    }

    private void initialize() {
        if (!SteveConfig.MCP_ENABLED.get()) {
            SteveMod.LOGGER.info("MCP is disabled in config");
            return;
        }

        String serversJson = SteveConfig.MCP_SERVERS.get();
        if (serversJson == null || serversJson.isEmpty() || serversJson.equals("[]")) {
            SteveMod.LOGGER.info("No MCP servers configured");
            return;
        }

        try {
            Type listType = new TypeToken<List<ServerConfig>>() {}.getType();
            List<ServerConfig> servers = GSON.fromJson(serversJson, listType);

            for (ServerConfig server : servers) {
                SteveMod.LOGGER.info("Connecting to MCP server: {} at {}", server.name, server.url);
                MCPClientWrapper client = new MCPClientWrapper(server.name, server.url);
                client.initialize();
                clients.put(server.name, client);

                List<MCPToolConverter.ToolInfo> tools = client.listTools();
                toolsByServer.put(server.name, tools);
                SteveMod.LOGGER.info("MCP server '{}' has {} tools", server.name, tools.size());
            }
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to initialize MCP servers", e);
        }
    }

    /**
     * Get all tools from all servers, prefixed with server name.
     */
    public List<MCPToolConverter.ToolInfo> getAllTools() {
        List<MCPToolConverter.ToolInfo> allTools = new ArrayList<>();
        for (Map.Entry<String, List<MCPToolConverter.ToolInfo>> entry : toolsByServer.entrySet()) {
            String serverName = entry.getKey();
            for (MCPToolConverter.ToolInfo tool : entry.getValue()) {
                allTools.add(new MCPToolConverter.ToolInfo(
                    serverName + ":" + tool.name(),
                    tool.description(),
                    tool.inputSchema()
                ));
            }
        }
        return allTools;
    }

    /**
     * Call a tool. Tool name should be in format "serverName:toolName".
     */
    public String callTool(String fullToolName, Map<String, Object> arguments) {
        int colonIndex = fullToolName.indexOf(':');
        if (colonIndex < 0) {
            return "{\"error\": \"Invalid tool name format, expected 'serverName:toolName'\"}";
        }

        String serverName = fullToolName.substring(0, colonIndex);
        String toolName = fullToolName.substring(colonIndex + 1);

        MCPClientWrapper client = clients.get(serverName);
        if (client == null) {
            return "{\"error\": \"Unknown MCP server: " + serverName + "\"}";
        }

        try {
            return client.callTool(toolName, arguments);
        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to call MCP tool {} on server {}", toolName, serverName, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Shutdown all MCP connections.
     */
    public void shutdown() {
        for (MCPClientWrapper client : clients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                SteveMod.LOGGER.error("Error closing MCP client", e);
            }
        }
        clients.clear();
        toolsByServer.clear();
    }

    /**
     * Server configuration from JSON.
     */
    private static class ServerConfig {
        String name;
        String url;
    }
}
