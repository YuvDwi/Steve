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

    private static MCPToolRegistry INSTANCE;

    private static final Gson GSON = new Gson();
    private final Map<String, MCPClientWrapper> clients = new ConcurrentHashMap<>();
    private final Map<String, List<MCPToolConverter.ToolInfo>> toolsByServer = new ConcurrentHashMap<>();

    public static void init() {
        if (INSTANCE == null) {
            INSTANCE = new MCPToolRegistry();
        }
    }

    public static MCPToolRegistry getInstance() {
        return INSTANCE;
    }

    private MCPToolRegistry() {
        doInitialize();
    }

    private void doInitialize() {
        try {
            if (!SteveConfig.MCP_ENABLED.get()) {
                SteveMod.LOGGER.info("MCP is disabled in config");
                return;
            }

            String serversJson = SteveConfig.MCP_SERVERS.get();
            if (serversJson == null || serversJson.isEmpty() || serversJson.equals("[]")) {
                SteveMod.LOGGER.info("No MCP servers configured");
                return;
            }

            Type listType = new TypeToken<List<ServerConfig>>() {}.getType();
            List<ServerConfig> servers = GSON.fromJson(serversJson, listType);

            for (ServerConfig server : servers) {
                SteveMod.LOGGER.info("Connecting to MCP server: {} at {}", server.name, server.url);
                MCPClientWrapper client = new MCPClientWrapper(server.name, server.url);
                client.initialize();
                clients.put(server.name, client);

                List<MCPToolConverter.ToolInfo> tools = client.listTools();
                toolsByServer.put(server.name, tools);
                SteveMod.LOGGER.info("MCP server '{}' has {} tools: {}", server.name, tools.size(), tools);

                // Log detailed tool info
                for (MCPToolConverter.ToolInfo tool : tools) {
                    SteveMod.LOGGER.info("  - {}: {} (inputSchema: {})", tool.name(), tool.description(), tool.inputSchema());
                }
            }

            // Log summary of all MCP capabilities
            List<MCPToolConverter.ToolInfo> allTools = getAllTools();
            SteveMod.LOGGER.info("=== MCP Capabilities Summary: {} total tools ===", allTools.size());
            for (MCPToolConverter.ToolInfo tool : allTools) {
                SteveMod.LOGGER.info("  [{}] {}", tool.name(), tool.description());
            }
            SteveMod.LOGGER.info("===========================================");
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
     * Look up the configured URL for an MCP server by name.
     *
     * <p>Reads {@code SteveConfig.MCP_SERVERS} (a JSON array of
     * {@code {"name","url"} objects}) and returns the first matching URL.
     * Used by code outside the registry (e.g.
     * {@code StructureTemplateLoader.registerStructureToMempalace}) that
     * needs a configured mempalace URL without re-parsing the config.</p>
     *
     * @return the configured URL, or {@code null} if MCP is disabled, the
     *         config is empty/malformed, or no server with that name is
     *         configured.
     */
    public static String getServerUrl(String name) {
        try {
            if (!SteveConfig.MCP_ENABLED.get()) return null;
            String json = SteveConfig.MCP_SERVERS.get();
            if (json == null || json.isEmpty() || json.equals("[]")) return null;
            Type listType = new TypeToken<List<ServerConfig>>() {}.getType();
            List<ServerConfig> servers = GSON.fromJson(json, listType);
            if (servers == null) return null;
            for (ServerConfig s : servers) {
                if (name.equals(s.name)) return s.url;
            }
        } catch (Exception e) {
            SteveMod.LOGGER.warn("Failed to look up MCP server URL for '{}': {}", name, e.getMessage());
        }
        return null;
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
