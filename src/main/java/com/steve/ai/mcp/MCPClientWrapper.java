package com.steve.ai.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wrapper around MCP SDK's McpAsyncClient with HTTP transport.
 */
public class MCPClientWrapper {

    private static final Logger LOGGER = LogManager.getLogger(MCPClientWrapper.class);

    private final String serverName;
    private final String serverUrl;
    private final long timeoutMs;
    private io.modelcontextprotocol.client.McpAsyncClient client;
    private volatile boolean initialized = false;

    public MCPClientWrapper(String serverName, String serverUrl) {
        this(serverName, serverUrl, 30000); // default 30s
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

            client = McpClient.async(transport)
                .requestTimeout(Duration.ofMillis(timeoutMs))
                .capabilities(McpSchema.ClientCapabilities.builder()
                    .roots(true)       // Enable filesystem roots support with list changes notifications
                    .sampling()        // Enable LLM sampling support
                    .elicitation()     // Enable elicitation support (form and URL modes)
                    .build())
                .sampling(request -> Mono.just(new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT, new McpSchema.TextContent("sampling response"), null, null, null)))
                .elicitation(request -> Mono.just(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, null)))
                .toolsChangeConsumer(tools -> Mono.fromRunnable(() -> {
                    LOGGER.info("MCP server '{}' tools updated: {}", serverName, tools);
                }))
                .resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> {
                    LOGGER.info("MCP server '{}' resources updated: {}", serverName, resources);
                }))
                .promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> {
                    LOGGER.info("MCP server '{}' prompts updated: {}", serverName, prompts);
                }))
                .progressConsumer(progress -> Mono.fromRunnable(() -> {
                    LOGGER.debug("MCP server '{}' progress: {}", serverName, progress);
                }))
                .build();

            client.initialize()
                .flatMap(initResult -> {
                    LOGGER.info("MCP client '{}' initialized: {}", serverName, initResult);
                    initialized = true;
                    return Mono.empty();
                })
                .doOnError(e -> {
                    LOGGER.error("Failed to initialize MCP client '{}'", serverName, e);
                })
                .onErrorResume(e -> {
                    LOGGER.warn("MCP client '{}' initialization failed, continuing without it", serverName);
                    return Mono.empty();
                })
                .subscribe();

        } catch (Exception e) {
            LOGGER.error("Failed to create MCP client for server '{}'", serverName, e);
        }
    }

    /**
     * List all available tools from this server.
     */
    public List<MCPToolConverter.ToolInfo> listTools() {
        if (client == null || !initialized) {
            return List.of();
        }

        List<MCPToolConverter.ToolInfo>[] result = new List[1];

        client.listTools()
            .flatMap(listResult -> {
                List<MCPToolConverter.ToolInfo> tools = new ArrayList<>();
                for (McpSchema.Tool tool : listResult.tools()) {
                    String inputSchema = tool.inputSchema() != null
                        ? tool.inputSchema().toString()
                        : "";
                    tools.add(new MCPToolConverter.ToolInfo(
                        tool.name(),
                        tool.description(),
                        inputSchema
                    ));
                }
                result[0] = tools;
                return Mono.just(tools);
            })
            .doOnError(e -> {
                LOGGER.error("Failed to list tools from MCP server '{}'", serverName, e);
            })
            .onErrorReturn(List.of())
            .subscribe();

        return result[0] != null ? result[0] : List.of();
    }

    /**
     * Call a tool with the given arguments.
     * Returns JSON string result.
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        if (client == null || !initialized) {
            return "{\"error\": \"MCP client not initialized\"}";
        }

        String[] result = new String[1];

        client.callTool(new McpSchema.CallToolRequest(toolName, arguments))
            .flatMap(callResult -> {
                if (callResult.isError() != null && callResult.isError()) {
                    return Mono.just("{\"error\": \"" + escapeJson(callResult.content().toString()) + "\"}");
                }
                return Mono.just(callResult.content().toString());
            })
            .doOnError(e -> {
                LOGGER.error("Failed to call tool '{}' on MCP server '{}'", toolName, serverName, e);
                result[0] = "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
            })
            .onErrorReturn("{\"error\": \"unknown error\"}")
            .subscribe(r -> result[0] = r);

        // Wait for result with timeout
        int waitCount = 0;
        while (result[0] == null && waitCount < 100) {
            try {
                Thread.sleep(100);
                waitCount++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (result[0] == null) {
            return "{\"error\": \"timeout\"}";
        }

        return result[0];
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
        initialized = false;
        try {
            client.close();
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
        return initialized;
    }
}
