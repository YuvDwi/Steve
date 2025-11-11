package com.steve.ai.ai;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class PromptBuilder {
    
    public static String buildSystemPrompt() {
        return """
            You are a Minecraft AI agent. Respond ONLY with valid JSON, no extra text.
            
            FORMAT (strict JSON):
            {"reasoning": "brief thought", "plan": "action description", "tasks": [{"action": "type", "parameters": {...}}]}
            
            ACTIONS:
            - attack: {"target": "hostile"} (for any mob/monster)
            - build: {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}
            - mine: {"block": "iron", "quantity": 8} (resources: iron, diamond, coal, gold, copper, redstone, emerald)
            - craft: {"item": "wooden_pickaxe", "quantity": 1} (crafts items, auto-finds/places crafting table)
            - store: {"item": "cobblestone"} or {} (stores items in chest, omit item to store all)
            - retrieve: {"item": "iron_ingot", "quantity": 8} (retrieves items from nearby chest)
            - place_chest: {} (places chest for storage)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}
            
            RULES:
            1. ALWAYS use "hostile" for attack target (mobs, monsters, creatures)
            2. STRUCTURE OPTIONS: house, oldhouse, powerplant, castle, tower, barn, modern
            3. house/oldhouse/powerplant = pre-built NBT templates (auto-size)
            4. castle/tower/barn/modern = procedural (castle=14x10x14, tower=6x6x16, barn=12x8x14)
            5. Use 2-3 block types: oak_planks, cobblestone, glass_pane, stone_bricks
            6. NO extra pathfind tasks unless explicitly requested
            7. Keep reasoning under 15 words
            8. COLLABORATIVE BUILDING: Multiple Steves can work on same structure simultaneously
            9. MINING: Can mine any ore (iron, diamond, coal, etc)
            10. CRAFTING: Checks inventory for ingredients, finds crafting table (or places one)
            11. STORAGE: Use 'store' when inventory full, 'retrieve' when need items from chest
            12. INVENTORY MANAGEMENT: Auto-stores items when inventory >90% full
            
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

            Input: "craft a wooden pickaxe"
            {"reasoning": "Crafting wooden pickaxe", "plan": "Craft tool", "tasks": [{"action": "craft", "parameters": {"item": "wooden_pickaxe", "quantity": 1}}]}

            Input: "store my items"
            {"reasoning": "Storing inventory items", "plan": "Store items", "tasks": [{"action": "store", "parameters": {}}]}

            Input: "get 10 iron ingots from chest"
            {"reasoning": "Retrieving iron from storage", "plan": "Retrieve iron", "tasks": [{"action": "retrieve", "parameters": {"item": "iron_ingot", "quantity": 10}}]}

            CRITICAL: Output ONLY valid JSON. No markdown, no explanations, no line breaks in JSON.
            """;
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();
        
        // Give agents FULL situational awareness
        prompt.append("=== YOUR SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(steve.blockPosition())).append("\n");
        prompt.append("Nearby Players: ").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Nearby Entities: ").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Nearby Blocks: ").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");
        prompt.append("Biome: ").append(worldKnowledge.getBiomeName()).append("\n");
        
        prompt.append("\n=== PLAYER COMMAND ===\n");
        prompt.append("\"").append(command).append("\"\n");
        
        prompt.append("\n=== YOUR RESPONSE (with reasoning) ===\n");
        
        return prompt.toString();
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(SteveEntity steve) {
        return "[empty]";
    }
}

