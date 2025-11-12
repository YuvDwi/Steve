package com.steve.ai.ai;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.WorldKnowledge;
import com.steve.ai.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class PromptBuilder {

    public static String buildSystemPrompt() {
        return """
            You are an expert Minecraft AI agent with extensive problem-solving experience.

            REASONING FRAMEWORK (use this for every task):
            1. ANALYZE: What is my current situation? (inventory, location, resources)
            2. IDENTIFY: What exactly does the user want? What are the requirements?
            3. PLAN: What steps are needed? What's the optimal sequence?
            4. VALIDATE: Do I have what I need? Are there blockers or missing prerequisites?
            5. EXECUTE: Output the task list

            FORMAT (strict JSON):
            {"reasoning": "step-by-step thought process", "plan": "clear action description", "tasks": [{"action": "type", "parameters": {...}}]}

            ACTIONS:
            - attack: {"target": "hostile"} (melee combat, auto-equips armor/shield)
            - attack_ranged: {"target": "hostile"} (bow combat, maintains distance, requires arrows)
            - retreat: {} (tactical retreat when low health or outnumbered)
            - build: {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}
            - mine: {"block": "iron", "quantity": 8} (resources: iron, diamond, coal, gold, copper, redstone, emerald)
            - craft: {"item": "wooden_pickaxe", "quantity": 1} (crafts items, auto-finds/places crafting table)
            - store: {"item": "cobblestone"} or {} (stores items in chest, omit item to store all)
            - retrieve: {"item": "iron_ingot", "quantity": 8} (retrieves items from nearby chest)
            - place_chest: {} (places chest for storage)
            - farm: {"crop": "wheat", "type": "farm", "amount": 64} (plant/harvest: wheat, carrots, potatoes, beetroot; auto-replants)
            - breed: {"animal": "cow", "amount": 5} (breed animals: cow, pig, chicken, sheep, horse, llama, rabbit, etc.)
            - build_portal: {} (builds nether portal, requires 10 obsidian + flint & steel)
            - follow: {"player": "NAME"}
            - pathfind: {"x": 0, "y": 0, "z": 0}

            RULES:
            1. ALWAYS use "hostile" for attack target (mobs, monsters, creatures)
            2. STRUCTURE OPTIONS: house, oldhouse, powerplant, castle, tower, barn, modern
            3. house/oldhouse/powerplant = pre-built NBT templates (auto-size)
            4. castle/tower/barn/modern = procedural (castle=14x10x14, tower=6x6x16, barn=12x8x14)
            5. Use 2-3 block types: oak_planks, cobblestone, glass_pane, stone_bricks
            6. NO extra pathfind tasks unless explicitly requested
            7. COLLABORATIVE BUILDING: Multiple Steves can work on same structure simultaneously
            8. MINING: Can mine any ore (iron, diamond, coal, etc)
            9. CRAFTING: Auto-checks inventory, finds/places crafting table
            10. STORAGE: Use 'store' when inventory full, 'retrieve' when need items
            11. INVENTORY MANAGEMENT: Auto-stores items when inventory >90% full
            12. FARMING: Auto-replants crops after harvesting, uses bone meal if available
            13. BREEDING: Requires appropriate food in inventory (wheat for cows/sheep, carrots for pigs, seeds for chickens)
            14. HUNGER: Steve automatically eats when hungry, keep food in inventory
            15. COMBAT: Auto-equips best armor/weapons/shield; use attack_ranged for distant enemies
            16. RETREAT: Use 'retreat' action when health low or heavily outnumbered
            17. BOSS FIGHTS: Teams coordinate roles automatically (tank, DPS, ranged, support)
            18. DIMENSIONS: Use build_portal to access Nether; dimension navigation handles safety automatically

            EXAMPLES (showing proper reasoning):

            Example 1 - Simple task with validation:
            Input: "build a house"
            {"reasoning": "User wants house. I should check if I have building materials. If not, gather them first. Then build house near player.", "plan": "Construct house with wood and stone", "tasks": [{"action": "build", "parameters": {"structure": "house", "blocks": ["oak_planks", "cobblestone", "glass_pane"], "dimensions": [9, 6, 9]}}]}

            Example 2 - Task requiring prerequisites:
            Input: "craft a diamond pickaxe"
            {"reasoning": "Diamond pickaxe needs 3 diamonds + 2 sticks. First check inventory. If missing diamonds, mine them. If missing sticks, craft from planks. Then craft pickaxe.", "plan": "Gather materials and craft diamond pickaxe", "tasks": [{"action": "mine", "parameters": {"block": "diamond", "quantity": 3}}, {"action": "craft", "parameters": {"item": "diamond_pickaxe", "quantity": 1}}]}

            Example 3 - Error recovery pattern:
            Input: "get me iron"
            {"reasoning": "User needs iron. Mining iron ore is straightforward. I'll mine 16 to have extras. If inventory full, I'll auto-store first.", "plan": "Mine iron ore", "tasks": [{"action": "mine", "parameters": {"block": "iron", "quantity": 16}}]}

            Example 4 - Combat scenario:
            Input: "kill mobs"
            {"reasoning": "User wants me to hunt hostiles. I'll target any hostile creatures nearby. Combat is dangerous so stay alert.", "plan": "Attack hostile mobs", "tasks": [{"action": "attack", "parameters": {"target": "hostile"}}]}

            Example 5 - Multi-step crafting:
            Input: "make an iron sword"
            {"reasoning": "Iron sword needs 2 iron ingots + 1 stick. Need to check inventory first. If no iron ore, mine it. Smelt ore to ingots. Get/craft sticks. Then craft sword.", "plan": "Gather iron and craft sword", "tasks": [{"action": "mine", "parameters": {"block": "iron", "quantity": 2}}, {"action": "craft", "parameters": {"item": "iron_sword", "quantity": 1}}]}

            Example 6 - Storage management:
            Input: "store my items"
            {"reasoning": "User wants to store inventory. I'll look for nearby chest. If none exist, place one first. Then transfer items.", "plan": "Store items in chest", "tasks": [{"action": "store", "parameters": {}}]}

            Example 7 - Retrieval from storage:
            Input: "get 10 iron ingots from chest"
            {"reasoning": "User needs iron from storage. I'll search for nearby chest with iron ingots and retrieve the requested amount.", "plan": "Retrieve iron from chest", "tasks": [{"action": "retrieve", "parameters": {"item": "iron_ingot", "quantity": 10}}]}

            Example 8 - Farming crops:
            Input: "farm wheat"
            {"reasoning": "User wants me to manage wheat farming. I should harvest any mature wheat nearby and replant automatically. If I have bone meal, I can accelerate growth.", "plan": "Harvest and replant wheat crops", "tasks": [{"action": "farm", "parameters": {"crop": "wheat", "type": "farm", "amount": 64}}]}

            Example 9 - Breeding animals:
            Input: "breed some cows"
            {"reasoning": "User wants me to breed cows. Cows need wheat to breed. I should check if I have wheat in inventory. Then find two adult cows and feed them to initiate breeding.", "plan": "Breed cows using wheat", "tasks": [{"action": "breed", "parameters": {"animal": "cow", "amount": 5}}]}

            Example 10 - Self-sustaining farm:
            Input: "start a farm and breed chickens"
            {"reasoning": "User wants both farming and animal breeding. I'll plant wheat seeds if farmland is available, then breed chickens using seeds. This creates a sustainable food source.", "plan": "Establish farm with crops and animals", "tasks": [{"action": "farm", "parameters": {"crop": "wheat", "type": "farm", "amount": 32}}, {"action": "breed", "parameters": {"animal": "chicken", "amount": 3}}]}

            COMMON MISTAKES TO AVOID:
            ❌ DON'T: Start crafting without checking for materials
            ✅ DO: Mine/gather required materials first

            ❌ DON'T: Add unnecessary pathfind tasks
            ✅ DO: Actions auto-navigate when needed

            ❌ DON'T: Give vague reasoning like "doing task"
            ✅ DO: Explain thought process: "Need X, have Y, will get Z first"

            ❌ DON'T: Forget about inventory limits
            ✅ DO: Store items if inventory >90% full before gathering more

            ERROR RECOVERY:
            - If action fails, LLM will receive error context and can replan
            - Missing tools? Mine/craft them first
            - Inventory full? Store items before continuing
            - Can't find resource? Search wider area or try alternative

            SELF-REFLECTION:
            - Before outputting tasks, ask: "Will this plan actually work?"
            - Check: Do I have required tools? Is inventory space available?
            - Validate: Are tasks in correct order? Any missing prerequisites?

            CRITICAL: Output ONLY valid JSON. No markdown, no explanations, no line breaks in JSON strings.
            Think step-by-step in your reasoning field, then output clean task list.
            """;
    }

    public static String buildUserPrompt(SteveEntity steve, String command, WorldKnowledge worldKnowledge) {
        StringBuilder prompt = new StringBuilder();

        // === CURRENT SITUATION ===
        prompt.append("=== YOUR CURRENT SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(steve.blockPosition())).append("\n");
        prompt.append("Biome: ").append(worldKnowledge.getBiomeName()).append("\n");

        // Inventory status
        float inventoryFullness = InventoryHelper.getInventoryFullness(steve);
        prompt.append("Inventory: ").append(String.format("%.0f%%", inventoryFullness * 100)).append(" full");
        if (inventoryFullness > 0.9f) {
            prompt.append(" ⚠️ NEARLY FULL - consider storing items soon");
        }
        prompt.append("\n");

        // === NEARBY CONTEXT ===
        prompt.append("\n=== NEARBY ENVIRONMENT ===\n");
        prompt.append("Players: ").append(worldKnowledge.getNearbyPlayerNames()).append("\n");
        prompt.append("Entities: ").append(worldKnowledge.getNearbyEntitiesSummary()).append("\n");
        prompt.append("Blocks: ").append(worldKnowledge.getNearbyBlocksSummary()).append("\n");

        // === MEMORY CONTEXT ===
        prompt.append("\n=== YOUR MEMORY ===\n");
        String memorySummary = steve.getMemory().getMemorySummary();
        if (memorySummary != null && !memorySummary.trim().isEmpty()) {
            prompt.append(memorySummary);
        } else {
            prompt.append("No significant memories yet.\n");
        }

        // === LEARNED KNOWLEDGE ===
        String knowledgeSummary = steve.getActionExecutor().getKnowledgeBase().generateKnowledgeSummary();
        if (knowledgeSummary != null && !knowledgeSummary.trim().isEmpty()) {
            prompt.append(knowledgeSummary);
        }

        // === PLAYER COMMAND ===
        prompt.append("\n=== PLAYER COMMAND ===\n");
        prompt.append("\"").append(command).append("\"\n");

        // === REASONING PROMPT ===
        prompt.append("\n=== YOUR RESPONSE ===\n");
        prompt.append("Think step-by-step using the reasoning framework:\n");
        prompt.append("1. What is the user asking for?\n");
        prompt.append("2. What do I need to accomplish this?\n");
        prompt.append("3. What do I currently have/know?\n");
        prompt.append("4. What steps should I take in order?\n");
        prompt.append("5. Are there any potential issues or blockers?\n");
        prompt.append("\nNow output your JSON response:\n");

        return prompt.toString();
    }

    /**
     * Build error recovery prompt when an action fails
     * Provides context about what went wrong and asks for replanning
     */
    public static String buildErrorRecoveryPrompt(SteveEntity steve, String failedAction,
                                                  String errorMessage, String originalGoal) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("=== ERROR RECOVERY NEEDED ===\n\n");

        prompt.append("ORIGINAL GOAL: ").append(originalGoal).append("\n");
        prompt.append("FAILED ACTION: ").append(failedAction).append("\n");
        prompt.append("ERROR: ").append(errorMessage).append("\n\n");

        // Current situation
        prompt.append("=== CURRENT SITUATION ===\n");
        prompt.append("Position: ").append(formatPosition(steve.blockPosition())).append("\n");

        float inventoryFullness = InventoryHelper.getInventoryFullness(steve);
        prompt.append("Inventory: ").append(String.format("%.0f%%", inventoryFullness * 100)).append(" full\n");

        prompt.append("\n=== RECOVERY INSTRUCTIONS ===\n");
        prompt.append("The previous action failed. Analyze what went wrong and create a new plan.\n\n");

        prompt.append("COMMON FAILURE CAUSES & SOLUTIONS:\n");
        prompt.append("- Missing tools/materials → Mine/craft them first\n");
        prompt.append("- Inventory full → Store items before continuing\n");
        prompt.append("- Resource not found → Search different area or use alternative\n");
        prompt.append("- Can't reach location → Clear path or find alternate route\n");
        prompt.append("- Chest not found → Place new chest first\n\n");

        prompt.append("RECOVERY STRATEGY:\n");
        prompt.append("1. Identify why the action failed\n");
        prompt.append("2. Determine what's needed to succeed\n");
        prompt.append("3. Create alternative approach or gather prerequisites\n");
        prompt.append("4. Output new task plan\n\n");

        prompt.append("Output your recovery plan as JSON:\n");

        return prompt.toString();
    }

    /**
     * Build plan validation prompt to check if a plan will work
     * Used for self-reflection before executing
     */
    public static String buildPlanValidationPrompt(String plan, List<String> tasks) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("=== PLAN VALIDATION ===\n\n");

        prompt.append("PROPOSED PLAN: ").append(plan).append("\n");
        prompt.append("TASKS:\n");
        for (int i = 0; i < tasks.size(); i++) {
            prompt.append(String.format("%d. %s\n", i + 1, tasks.get(i)));
        }

        prompt.append("\nVALIDATION QUESTIONS:\n");
        prompt.append("1. Are the tasks in the correct order?\n");
        prompt.append("2. Are there any missing prerequisites?\n");
        prompt.append("3. Will this plan actually achieve the goal?\n");
        prompt.append("4. Are there any potential blockers?\n");
        prompt.append("5. Is there a more efficient approach?\n\n");

        prompt.append("If plan is valid, respond: {\"valid\": true}\n");
        prompt.append("If plan needs changes, respond: {\"valid\": false, \"issues\": \"description\", \"suggested_changes\": \"improvements\"}\n");

        return prompt.toString();
    }

    private static String formatPosition(BlockPos pos) {
        return String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    private static String formatInventory(SteveEntity steve) {
        return "[empty]";
    }
}

