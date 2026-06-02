package com.steve.ai.llm;

import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveMemory;
import com.steve.ai.memory.WorldKnowledge;
import com.steve.ai.mcp.MCPToolConverter;
import com.steve.ai.mcp.MCPToolRegistry;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PromptBuilder {
    
    public static String buildSystemPrompt() {
        return """
            You are a Minecraft AI agent. Respond ONLY with valid JSON, no extra text.

            FORMAT (strict JSON):
            {"reasoning": "brief thought", "plan": "action description", "tasks": [{"action": "type", "parameters": {...}}]}

            ACTIONS:
            - attack: {"target": "hostile"} (for any mob/monster)
            - build: {"structure": "house"} (NBT template, auto-sized)
            - mine: {"block": "iron", "quantity": 8} (resources: iron, diamond, coal, gold, copper, redstone, emerald)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}

            RULES:
            1. ALWAYS use "hostile" for attack target (mobs, monsters, creatures)
            2. NBT TEMPLATES: %s
            3. NO extra pathfind tasks unless explicitly requested
            4. Keep reasoning under 15 words
            5. COLLABORATIVE BUILDING: Multiple Steves can work on same structure simultaneously
            6. MINING: Can mine any ore (iron, diamond, coal, etc)
            7. WAREHOUSE: Material warehouse provides building materials automatically. Steve goes to warehouse when running low.
            8. MCP TOOLS: Use "mcp" action to call external tools: {"action": "mcp", "parameters": {"tool": "serverName:toolName", "args": {...}}}
            %s

            EXAMPLES (copy these formats exactly):

            Input: "build a house"
            {"reasoning": "Building house from NBT template", "plan": "Construct house", "tasks": [{"action": "build", "parameters": {"structure": "house"}}]}

            Input: "get me iron"
            {"reasoning": "Mining iron ore for player", "plan": "Mine iron", "tasks": [{"action": "mine", "parameters": {"block": "iron", "quantity": 16}}]}

            Input: "find diamonds"
            {"reasoning": "Searching for diamond ore", "plan": "Mine diamonds", "tasks": [{"action": "mine", "parameters": {"block": "diamond", "quantity": 8}}]}

            Input: "kill mobs"
            {"reasoning": "Hunting hostile creatures", "plan": "Attack hostiles", "tasks": [{"action": "attack", "parameters": {"target": "hostile"}}]}

            Input: "murder creeper"
            {"reasoning": "Targeting creeper", "plan": "Attack creeper", "tasks": [{"action": "attack", "parameters": {"target": "creeper"}}]}

            Input: "follow me"
            {"reasoning": "Player needs me", "plan": "Follow player", "tasks": [{"action": "follow", "parameters": {"player": "USE_NEARBY_PLAYER_NAME"}}]}

            CRITICAL: Output ONLY valid JSON. No markdown, no explanations, no line breaks in JSON.

            AVAILABLE MCP TOOLS:
            %s
            """.formatted(getAvailableTemplates(), getMaterialRule(), getMcpToolsPrompt());
    }

    private static String getAvailableTemplates() {
        List<String> templates = StructureTemplateLoader.getAvailableStructures();
        if (templates.isEmpty()) {
            return "(none)";
        }
        return String.join(", ", templates);
    }

    private static String getMaterialRule() {
        if (SteveConfig.CREATIVE_MODE.get()) {
            return "10. CREATIVE MODE: Unlimited materials. NEVER mine before building. Build directly.";
        }
        return "10. SURVIVAL MODE: Steve has a 36-slot inventory. Mined blocks go into inventory. Building consumes from inventory. If inventory is empty, mine materials first before building.";
    }

    private static String getMcpToolsPrompt() {
        if (!SteveConfig.MCP_ENABLED.get()) {
            return "(none - MCP is disabled in config)";
        }
        try {
            MCPToolRegistry registry = MCPToolRegistry.getInstance();
            if (registry == null) {
                return "(none - MCP registry not initialized)";
            }
            List<MCPToolConverter.ToolInfo> tools = registry.getAllTools();
            if (tools.isEmpty()) {
                return "(none - no MCP servers connected)";
            }
            return MCPToolConverter.toPromptSection(tools);
        } catch (Exception e) {
            return "(none - error loading MCP tools)";
        }
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        String inventory = getInventoryStatus(steve);
        String warehouse = getWarehouseStatus(steve);

        return """
            === YOUR SITUATION ===
            Position: %s
            Nearby Players: %s
            Nearby Entities: %s
            Nearby Blocks: %s
            Inventory: %s
            Biome: %s
            Warehouse: %s

            === PLAYER COMMAND ===
            "%s"

            === YOUR RESPONSE (with reasoning) ===
            """.formatted(
                formatPosition(steve.blockPosition()),
                worldKnowledge.getNearbyPlayerNames(),
                worldKnowledge.getNearbyEntitiesSummary(),
                worldKnowledge.getNearbyBlocksSummary(),
                inventory,
                worldKnowledge.getBiomeName(),
                warehouse,
                command
            );
    }

    private static String getInventoryStatus(SteveEntity steve) {
        if (SteveConfig.CREATIVE_MODE.get()) {
            return "[unlimited - creative mode]";
        }
        return formatInventory(steve);
    }

    private static String getWarehouseStatus(SteveEntity steve) {
        if (steve.getWarehousePos() != null) {
            return formatPosition(steve.getWarehousePos());
        }
        return "[none]";
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(SteveEntity steve) {
        SimpleContainer inventory = steve.getInventory();
        Map<String, Integer> itemCounts = new HashMap<>();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String name = stack.getHoverName().getString();
                itemCounts.merge(name, stack.getCount(), Integer::sum);
            }
        }

        if (itemCounts.isEmpty()) {
            return "[empty]";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return sb.toString();
    }

    public static String buildReActSystemPrompt(int maxSteps) {
        return """
            You are a Minecraft AI agent operating in ReAct (Reason + Act) mode.
            You decide ONE action per turn. After each action, you will receive an Observation
            describing the result. Use the observation to decide your next step.
            You may take up to %d steps to complete the user's command.

            OUTPUT FORMAT (strict JSON, one object only):
            {"thought": "what you are thinking and why you choose this action",
             "action": "<action_name>",
             "parameters": {<action parameters>},
             "is_final": false}

            When the command is fully accomplished (or you determine it cannot be done), output:
            {"thought": "summary of what was accomplished",
             "is_final": true,
             "final_answer": "a brief, friendly sentence to tell the user (use their language if obvious)"}

            ACTIONS (use these exact names):
            - attack: {"target": "hostile|mob_name"} (for any mob/monster/creature)
            - build: {"structure": "<template_name>"} (NBT template, auto-sized)
            - mine: {"block": "<resource>", "quantity": <int>} (resources: iron, diamond, coal, gold, copper, redstone, emerald, etc)
            - follow: {"player": "<player_name>"}
            - pathfind: {"x": <int>, "y": <int>, "z": <int>}
            - gather: {"resource": "<resource>", "quantity": <int>}
            - craft: {"item": "<item>", "quantity": <int>}
            - mcp: {"tool": "<serverName:toolName>", "args": {<args>}} (call an MCP tool)

            RULES:
            1. ALWAYS use "hostile" for attack target unless the player named a specific mob
            2. NBT TEMPLATES available: %s
            3. NO pathfind task unless explicitly needed (build/mine auto-navigate)
            4. Keep "thought" under 30 words
            5. COLLABORATIVE BUILDING: multiple Steves can work on the same structure
            6. %s
            7. MCP TOOLS: use action="mcp" with parameters.tool = "serverName:toolName"
            8. If a tool call fails or the action is wrong, the Observation will tell you — adjust and try again, or use is_final:true with an explanation
            9. To stop, set is_final:true. Do NOT repeat the same failing action twice.
            10. Output ONLY valid JSON. No markdown, no prose, no line breaks inside JSON.

            EXAMPLES:

            Step 1 (need information):
            {"thought": "I should check what build templates are available before choosing one",
             "action": "mcp",
             "parameters": {"tool": "mempalace:mempalace_list_drawers", "args": {"wing": "structure_template"}},
             "is_final": false}

            Step 2 (after receiving template list, build):
            {"thought": "house is available, will build it",
             "action": "build",
             "parameters": {"structure": "house"},
             "is_final": false}

            Final step:
            {"thought": "House built successfully at the target position",
             "is_final": true,
             "final_answer": "Built a house at [100, 64, -200]"}

            AVAILABLE MCP TOOLS:
            %s
            """.formatted(maxSteps, getAvailableTemplates(), getMaterialRule(), getMcpToolsPrompt());
    }

    public static String buildReActUserPrompt(SteveEntity steve, String command, String scratchpad) {
        return """
            === YOUR SITUATION ===
            Position: %s
            Nearby Players: %s
            Nearby Entities: %s
            Nearby Blocks: %s
            Inventory: %s
            Biome: %s
            Warehouse: %s

            === USER COMMAND ===
            "%s"

            === SCRATCHPAD (your previous thoughts, actions, and observations) ===
            %s

            === YOUR NEXT STEP (JSON only) ===
            """.formatted(
                formatPosition(steve.blockPosition()),
                new WorldKnowledge(steve).getNearbyPlayerNames(),
                new WorldKnowledge(steve).getNearbyEntitiesSummary(),
                new WorldKnowledge(steve).getNearbyBlocksSummary(),
                getInventoryStatus(steve),
                new WorldKnowledge(steve).getBiomeName(),
                getWarehouseStatus(steve),
                command,
                scratchpad.isEmpty() ? "(no steps taken yet)" : scratchpad
            );
    }
}

