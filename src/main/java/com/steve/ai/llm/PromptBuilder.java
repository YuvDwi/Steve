package com.steve.ai.llm;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
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
            你是 Minecraft AI 智能体,正在以 ReAct (推理 + 行动) 模式工作。
            你每回合只决定一个 action。执行后你会收到一段 Observation 描述结果,请根据 Observation 决定下一步。
            你最多可用 %d 步完成玩家指令。

            输出格式(严格 JSON,只输出一个对象):
            {"thought": "你在想什么、为什么选这个 action",
             "action": "<action_name>",
             "parameters": {<action 参数>},
             "is_final": false}

            当任务完全完成(或你确定无法完成)时,输出:
            {"thought": "总结已完成的成果",
             "is_final": true,
             "final_answer": "一句简短友好的中文回复给玩家"}

            动作列表(用以下英文 key,大小写敏感):
            - attack: {"target": "hostile|生物名"} (攻击任何敌对生物/怪物)
            - build: {"structure": "<模板名>"} (单个 NBT,自动算尺寸) —— 或 {"structures": [{"name":"<n>","dx":<int>,"dy":<int>,"dz":<int>,"facing":"N|E|S|W"}]} 模块拼装协议 (dx/dy/dz 是相对上一块出口的偏移,在上一块的局部坐标系下表达;facing 默认 S)。长线状结构(铁轨、高速、长城、运河)优先用模块拼装形式,这样每段能旋转拼出折线。
              组合规则:非平凡结构 ≥3 entry,简单结构 ≥2 entry;玩家明确要单个 piece 时 (如 "放 房子_1") 才允许单 entry。
            - mine: {"block": "<资源名>", "quantity": <int>} (资源: iron, diamond, coal, gold, copper, redstone, emerald 等)
            - follow: {"player": "<玩家名>"}
            - pathfind: {"x": <int>, "y": <int>, "z": <int>}
            - gather: {"resource": "<资源名>", "quantity": <int>}
            - craft: {"item": "<物品>", "quantity": <int>}
            - mcp: {"tool": "<serverName:toolName>", "args": {<args>}} (调用 MCP 工具)

            规则:
            1. 攻击目标统一用 "hostile",除非玩家明确指定了具体生物名
            2. 可用 NBT 模板: %s
            3. 除非明确需要否则不要发 pathfind 任务(build/mine 会自动寻路)
            4. "thought" 字段保持在 30 字以内
            5. 协同建造:多个 Steve 可以同时做同一个结构
            6. %s
            7. MCP 工具调用:用 action="mcp",parameters.tool = "serverName:toolName"
            8. 计划模式 (action=build 时): 任何非平凡结构都必须用模块拼装形式,parameters.structures 数组至少 2 个 entry (典型 3+);玩家明确说 "放 X" 的单 piece 例外 —— 此规则与 plan-mode 用户消息中的规则是同一约束。
            9. 工具调用失败或 action 选错时,Observation 会告诉你 —— 调整重试,或者用 is_final:true 给出说明
            10. 终止时设 is_final:true,同一个失败的 action 不要重复发两次
            11. 只输出合法 JSON,不要 markdown、不要散文、JSON 里不要换行

            示例:

            步骤 1 (需要先查信息):
            {"thought": "我先查一下有哪些 build 模板可用",
             "action": "mcp",
             "parameters": {"tool": "mempalace:mempalace_list_drawers", "args": {"wing": "structure_template"}},
             "is_final": false}

            步骤 2 (拿到模板后建):
            {"thought": "house 可用,直接建",
             "action": "build",
             "parameters": {"structure": "house"},
             "is_final": false}

            步骤 2b (组合建造 —— 村庄 3 个 module):
            {"thought": "村庄需要 房子_1 + 井 + 围栏,都有,直接拼",
             "action": "build",
             "parameters": {"structures": [
               {"name": "房子_1"},
               {"name": "井", "dx": 0, "dy": 0, "dz": 0, "facing": "S"},
               {"name": "围栏", "dx": 0, "dy": 0, "dz": 0, "facing": "S"}
             ]},
             "is_final": false}

            收尾步骤:
            {"thought": "房子在目标位置建好了",
             "is_final": true,
             "final_answer": "已在 [100, 64, -200] 位置建好房子"}

            可用 MCP 工具:
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

    /**
     * Build the plan-mode user-prompt prefix that the LLM sees in
     * {@code === USER COMMAND ===}. The constraint text must travel with the
     * command (ReActAgent.runStep embeds the original command raw on every
     * turn), so this string is prepended to the player's free-form description.
     *
     * <p>Used by {@code ActionExecutor.startPlannedBuild} and surfaced via
     * {@code /steve plan &lt;description&gt;}.</p>
     *
     * @param description player's free-form request, e.g. {@code "build a castle"}
     * @param maxEntries cap on the number of {@code structures[]} entries
     *                   (from {@code SteveConfig.MAX_TEMPLATES_PER_PLAN})
     * @return the full prompt string ready to be queued for the ReAct agent
     */
    public static String buildPlanPrompt(String description, int maxEntries) {
        return "[PLAN MODE] Player wants a plan, NOT immediate execution. "
            + "Do NOT gather/mine/craft/pathfind first — the player will /steve approve "
            + "before any blocks are placed.\n\n"
            + "可用 NBT 模板 (直接复用,不要重新查询): " + String.join(", ", StructureTemplateLoader.getAvailableStructures()) + "\n\n"
            + "You MUST respond by emitting action=build with the module-composition form:\n"
            + "  parameters: {\"structures\": [{\"name\": \"<template>\", \"facing\": \"N|E|S|W\"}, ...]}\n\n"
            + "Rules:\n"
            + "- 至少 2 entries (系统规则第 8 条同样要求);单元素数组会被拒绝 (除玩家显式 \"放 X\")。\n"
            + "- Each entry's \"facing\" rotates the piece 90° about Y, so chained pieces turn corners — that is why a build needs multiple entries.\n"
            + "- \"dx\"/\"dy\"/\"dz\" are optional offsets from the previous piece's exit point; default 0/0/0.\n"
            + "- At most " + maxEntries + " entries (cap).\n"
            + "- Do NOT use the legacy {\"structures\": [\"<name>\", ...]} string form — it is no longer supported.\n\n"
            + "Concrete example for a house:\n"
            + "  {\"structures\": [{\"name\": \"房子_主体\", \"facing\": \"S\"}, "
            + "{\"name\": \"房子_屋顶\", \"dx\": 0, \"dy\": 6, \"facing\": \"S\"}]}\n\n"
            + "Player's request: " + description;
    }
}
