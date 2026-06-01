package com.steve.ai.mcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Test suite for MCP client connection and tool calls.
 * Requires a running MCP server at http://localhost:6060
 */
public class MCPClientWrapperTest {

    private static final String SERVER_NAME = "mempalace";
    private static final String SERVER_URL = "http://localhost:6060";

    @Test
    void testMcpConnection() throws Exception {
        MCPClientWrapper client = new MCPClientWrapper(SERVER_NAME, SERVER_URL);
        client.initialize();

        try {
            assertTrue(client.isInitialized(), "MCP client should be initialized");
        } finally {
            client.close();
        }
    }

    @Test
    void testListTools() throws Exception {
        MCPClientWrapper client = new MCPClientWrapper(SERVER_NAME, SERVER_URL);
        client.initialize();

        try {
            assertTrue(client.isInitialized(), "MCP client should be initialized");

            List<MCPToolConverter.ToolInfo> tools = client.listTools();
            assertNotNull(tools, "Tools list should not be null");
            assertFalse(tools.isEmpty(), "Tools list should not be empty");

            System.out.println("Found " + tools.size() + " tools:");
            for (MCPToolConverter.ToolInfo tool : tools) {
                System.out.println("  - " + tool.name() + ": " + tool.description());
            }
        } finally {
            client.close();
        }
    }

    @Test
    void testStatus() throws Exception {
        MCPClientWrapper client = new MCPClientWrapper(SERVER_NAME, SERVER_URL);
        client.initialize();

        try {
            assertTrue(client.isInitialized(), "MCP client should be initialized");

            // Example tool call with arguments - adjust based on actual mempalace tools
            String result = client.callTool("mempalace_status", Map.of());
            assertNotNull(result, "Tool call result should not be null");
            System.out.println("Tool call result: " + result);
        } finally {
            client.close();
        }
    }

    @Test
    void testAddDrawer() throws Exception {
        MCPClientWrapper client = new MCPClientWrapper(SERVER_NAME, SERVER_URL);
        client.initialize();

        try {
            assertTrue(client.isInitialized(), "MCP client should be initialized");

            // Test mempalace_add_drawer tool
            String result = client.callTool("mempalace_add_drawer", Map.of(
                "wing", "test-wing",
                "room", "test-room",
                "content", "Hello from MCP test",
                "added_by", "steve-test"
            ));
            assertNotNull(result, "Tool call result should not be null");
            System.out.println("mempalace_add_drawer result: " + result);
        } finally {
            client.close();
        }
    }
}
