package com.steve.ai.llm;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
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
            """.formatted(getAvailableTemplates(), getMaterialRule());
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
}

