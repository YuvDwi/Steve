package com.steve.ai.llm;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.ConstructionKnowledgeService;
import com.steve.ai.memory.WorldKnowledge;
import com.steve.ai.mcp.MCPToolConverter;
import com.steve.ai.mcp.MCPToolRegistry;
import com.steve.ai.plugin.ActionRegistry;
import com.steve.ai.structure.StructureTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PromptBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptBuilder.class);

    // ---- 公共入口 ----

    public static String buildReActSystemPrompt(int maxSteps) {
        String actionList = ActionRegistry.getInstance().generatePromptSection();
        return String.join("",
            REACT_HEADER.formatted(maxSteps),
            REACT_OUTPUT_FORMAT,
            REACT_ACTION_LIST_HEADER + "\n" + actionList + "\n",
            REACT_RULES.formatted(
                formatTemplateList(),
                formatMaterialRule(),
                formatMcpTools()),
            REACT_EXAMPLES,
            REACT_MCP_HEADER + "\n" + formatMcpTools() + "\n",
            formatConstructionKnowledgeIndex()
        );
    }

    public static String buildReActUserPrompt(SteveEntity steve, String command, String scratchpad) {
        return """
            %s

            === USER COMMAND ===
            "%s"

            === SCRATCHPAD (your previous thoughts, actions, and observations) ===
            %s

            === YOUR NEXT STEP (JSON only) ===
            """.formatted(
                buildSituationBlock(steve),
                command,
                scratchpad.isEmpty() ? "(no steps taken yet)" : scratchpad
            );
    }

    /**
     * Build the plan-mode user-prompt prefix that the LLM sees in
     * {@code === USER COMMAND ===}. The constraint text must travel with the
     * command (ReActAgent.runStep embeds the original command raw on every
     * turn), so this string is prepended to the player's free-form description.
     */
    public static String buildPlanPrompt(String description, int maxEntries) {
        return PLAN_HEADER + "\n\n"
            + formatConstructionKnowledgeIndex() + "\n\n"
            + PLAN_TEMPLATE_LIST.formatted(formatTemplateList()) + "\n\n"
            + PLAN_RULES.formatted(maxEntries) + "\n"
            + PLAN_EXAMPLE + "\n"
            + "Player's request: " + description;
    }

    // ---- 共享 situation block ----

    private static String buildSituationBlock(SteveEntity steve) {
        WorldKnowledge wk = new WorldKnowledge(steve);
        return """
            === YOUR SITUATION ===
            Position: %s
            Nearby Players: %s
            Nearby Entities: %s
            Nearby Blocks: %s
            Inventory: %s
            Biome: %s""".formatted(
                formatPosition(steve.blockPosition()),
                wk.getNearbyPlayerNames(),
                wk.getNearbyEntitiesSummary(),
                wk.getNearbyBlocksSummary(),
                formatInventoryStatus(steve),
                wk.getBiomeName());
    }

    // ---- 数据获取 ----

    private static String formatTemplateList() {
        List<String> templates = StructureTemplateLoader.getAvailableStructures();
        if (templates.isEmpty()) return "(none)";
        return String.join(", ", templates);
    }

    private static String formatMaterialRule() {
        if (SteveConfig.CREATIVE_MODE.get()) {
            return "10. CREATIVE MODE: Unlimited materials. NEVER mine before building. Build directly.";
        }
        return "10. SURVIVAL MODE: Steve has a 36-slot inventory. Mined blocks go into inventory. Building consumes from inventory. If inventory is empty, mine materials first before building.";
    }

    private static String formatMcpTools() {
        if (!SteveConfig.MCP_ENABLED.get()) return "(none - MCP is disabled in config)";
        try {
            MCPToolRegistry registry = MCPToolRegistry.getInstance();
            if (registry == null) return "(none - MCP registry not initialized)";
            List<MCPToolConverter.ToolInfo> tools = registry.getAllTools();
            if (tools.isEmpty()) return "(none - no MCP servers connected)";
            return MCPToolConverter.toPromptSection(tools);
        } catch (Exception e) {
            LOGGER.warn("Failed to load MCP tools for prompt: {}", e.getMessage());
            return "(none - error loading MCP tools)";
        }
    }

    private static String formatConstructionKnowledgeIndex() {
        return """
            === CONSTRUCTION DOMAIN KNOWLEDGE ===
            Steve 是专业的施工工程师。在 DESIGN 阶段你可以查询 mempalace 的领域知识库：

            Tool: mempalace:mempalace_list_drawers  args={"wing":"build_knowledge"}
            Tool: mempalace:mempalace_get_drawer   args={"wing":"build_knowledge","room":"<topic>"}

            当前知识库主题: %s

            查到的内容应指导你的设计：
            - 项目阶段拆解 (前期 → 设计 → 施工 → 验收)
            - 各阶段前置依赖 (先可行性、再方案、再施工)
            - 行业规范约束
            """.formatted(formatKnowledgeIndex());
    }

    private static String formatKnowledgeIndex() {
        try {
            List<String> topics = new ConstructionKnowledgeService().getTopics();
            if (topics.isEmpty()) return "(empty - proceed with general construction knowledge)";
            return String.join(", ", topics);
        } catch (Exception e) {
            LOGGER.warn("Failed to query build_knowledge: {}", e.getMessage());
            return "(unavailable - proceed with general construction knowledge)";
        }
    }

    // ---- 格式化 ----

    private static String formatInventoryStatus(SteveEntity steve) {
        if (SteveConfig.CREATIVE_MODE.get()) return "[unlimited - creative mode]";
        return formatInventory(steve);
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
                itemCounts.merge(stack.getHoverName().getString(), stack.getCount(), Integer::sum);
            }
        }
        if (itemCounts.isEmpty()) return "[empty]";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return sb.toString();
    }

    // ---- ReAct system prompt 段 ----

    private static final String REACT_HEADER = """
        你是 Minecraft AI 智能体,正在以 ReAct (推理 + 行动) 模式工作。
        你每回合只决定一个 action。执行后你会收到一段 Observation 描述结果,请根据 Observation 决定下一步。
        你最多可用 %d 步完成玩家指令。
        """;

    private static final String REACT_OUTPUT_FORMAT = """
        输出格式(严格 JSON,只输出一个对象):
        {"thought": "你在想什么、为什么选这个 action",
         "action": "<action_name>",
         "parameters": {<action 参数>},
         "is_final": false}

        当任务完全完成(或你确定无法完成)时,输出:
        {"thought": "总结已完成的成果",
         "is_final": true,
         "final_answer": "一句简短友好的中文回复给玩家"}
        """;

    private static final String REACT_ACTION_LIST_HEADER =
        "动作列表(用以下英文 key,大小写敏感):\n%s\n"
        + "\nbuild 补充说明: 长线状结构(铁轨、高速、长城、运河)优先用模块拼装形式(structures 数组),"
        + "这样每段能旋转拼出折线。\n"
        + "  组合规则:非平凡结构 ≥3 entry,简单结构 ≥2 entry;"
        + "玩家明确要单个 piece 时 (如 \"放 房子_1\") 才允许单 entry。";

    private static final String REACT_RULES = """
        规则:
        1. 攻击目标统一用 "hostile",除非玩家明确指定了具体生物名
        2. 可用 NBT 模板: %s
        3. 除非明确需要否则不要发 pathfind 任务(build/mine 会自动寻路)
        4. "thought" 字段保持在 30 字以内
        5. 协同建造:多个 Steve 可以同时做同一个结构
        6. %s
        7. MCP 工具调用:用 action="mcp",parameters.tool = "serverName:toolName"
        8. 计划模式 (action=build 时): 任何非平凡结构都必须用模块拼装形式,parameters.structures 数组至少 2 个 entry (典型 3+);玩家明确说 "放 X" 的单 piece 例外
        9. 工具调用失败或 action 选错时,Observation 会告诉你 —— 调整重试,或者用 is_final:true 给出说明
        10. 终止时设 is_final:true,同一个失败的 action 不要重复发两次
        11. 只输出合法 JSON,不要 markdown、不要散文、JSON 里不要换行
        """;

    private static final String REACT_EXAMPLES = """
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
        """;

    private static final String REACT_MCP_HEADER = "可用 MCP 工具:";

    // ---- Plan prompt 段 ----

    private static final String PLAN_HEADER =
        "[PLAN MODE] Player wants a plan, NOT immediate execution. "
        + "Do NOT gather/mine/craft/pathfind first — the player will /steve approve "
        + "before any blocks are placed.";

    private static final String PLAN_RULES = """
        You MUST respond by emitting action=build with the module-composition form:
          parameters: {"structures": [{"name": "<template>", "facing": "N|E|S|W"}, ...]}

        Rules:
        - 至少 2 entries (系统规则第 8 条同样要求);单元素数组会被拒绝 (除玩家显式 "放 X")。
        - Each entry's "facing" rotates the piece 90° about Y, so chained pieces turn corners — that is why a build needs multiple entries.
        - "dx"/"dy"/"dz" are optional offsets from the previous piece's exit point; default 0/0/0.
        - At most %d entries (cap).
        - Do NOT use the legacy {"structures": ["<name>", ...]} string form — it is no longer supported.
        """;

    private static final String PLAN_EXAMPLE = """
        Concrete example for a house:
        {"action": "build", "parameters": {"structures": [
            {"name": "房子_1"},
            {"name": "井", "facing": "S"}
        ]}}
        """;

    private static final String PLAN_TEMPLATE_LIST = "可用 NBT 模板 (直接复用,不要重新查询): %s";
}
