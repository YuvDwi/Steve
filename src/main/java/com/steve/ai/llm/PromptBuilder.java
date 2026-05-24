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
import java.util.stream.Collectors;

public class PromptBuilder {
    
    public static String buildSystemPrompt() {
        boolean creative = SteveConfig.CREATIVE_MODE.get();
        String materialRule = creative
            ? "10. CREATIVE MODE: Unlimited materials. NEVER mine before building. Build directly."
            : "10. SURVIVAL MODE: Steve has a 36-slot inventory. Mined blocks go into inventory. Building consumes from inventory. If inventory is empty, mine materials first before building.";

        // Dynamically load available NBT template names
        List<String> nbtTemplates = StructureTemplateLoader.getAvailableStructures();
        String nbtList = nbtTemplates.isEmpty() ? "(none)" : String.join(", ", nbtTemplates);
        String proceduralList = "castle, tower, barn, modern";

        return """
            You are a Minecraft AI agent. Respond ONLY with valid JSON, no extra text.

            FORMAT (strict JSON):
            {"reasoning": "brief thought", "plan": "action description", "tasks": [{"action": "type", "parameters": {...}}]}

            ACTIONS:
            - attack: {"target": "hostile"} (for any mob/monster)
            - build: {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}
            - mine: {"block": "iron", "quantity": 8} (resources: iron, diamond, coal, gold, copper, redstone, emerald)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}

            RULES:
            1. ALWAYS use "hostile" for attack target (mobs, monsters, creatures)
            2. NBT TEMPLATES (pre-built, auto-size): %s
            3. PROCEDURAL STRUCTURES: %s (castle=14x10x14, tower=6x6x16, barn=12x8x14)
            4. Use 2-3 block types: oak_planks, cobblestone, glass_pane, stone_bricks
            5. NO extra pathfind tasks unless explicitly requested
            6. Keep reasoning under 15 words
            7. COLLABORATIVE BUILDING: Multiple Steves can work on same structure simultaneously
            8. MINING: Can mine any ore (iron, diamond, coal, etc)
            9. WAREHOUSE: Material warehouse provides building materials automatically. Steve goes to warehouse when running low.
            %s

            EXAMPLES (copy these formats exactly):

            Input: "build a house"
            {"reasoning": "Building standard house near player", "plan": "Construct house", "tasks": [{"action": "build", "parameters": {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}}]}

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
            """.formatted(nbtList, proceduralList, materialRule);
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();
        
        // Give agents FULL situational awareness
        prompt.append("=== YOUR SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(steve.blockPosition())).append("\n");
        prompt.append("Nearby Players: ").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Nearby Entities: ").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Nearby Blocks: ").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");
        if (!SteveConfig.CREATIVE_MODE.get()) {
            prompt.append("Inventory: ").append(formatInventory(steve)).append("\n");
        } else {
            prompt.append("Inventory: [unlimited - creative mode]\n");
        }
        prompt.append("Biome: ").append(worldKnowledge.getBiomeName()).append("\n");
        if (steve.getWarehousePos() != null) {
            prompt.append("Warehouse: ").append(formatPosition(steve.getWarehousePos())).append("\n");
        } else {
            prompt.append("Warehouse: [none]\n");
        }
        
        prompt.append("\n=== PLAYER COMMAND ===\n");
        prompt.append("\"").append(command).append("\"\n");
        
        prompt.append("\n=== YOUR RESPONSE (with reasoning) ===\n");
        
        return prompt.toString();
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

