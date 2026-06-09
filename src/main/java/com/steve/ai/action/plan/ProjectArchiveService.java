package com.steve.ai.action.plan;

import com.steve.ai.SteveMod;
import com.steve.ai.llm.react.BuildPhase;
import com.steve.ai.mcp.MCPToolRegistry;
import com.steve.ai.structure.ModuleTransform;
import com.steve.ai.structure.PlacedModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Map;

public final class ProjectArchiveService {

    /** disableHtmlEscaping 保持 {@code minecraft:stone} 这样的方块 ID 可读
     *  （不会对冒号进行 HTML 转义），与 {@code PlanEventJson.GSON} 一致。 */
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ProjectArchiveService() {}

    public static JsonObject serialize(BuildProject p, String steveName, BuildPhase phase, String haltReasonOrNull) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("projectId", p.id);
        root.addProperty("command", p.command);
        root.addProperty("phase", phase.name());
        root.addProperty("steveName", steveName);
        root.addProperty("createdAtMs", p.createdAtMs);
        if (p.originPos != null) {
            root.add("origin", blockPosToJson(p.originPos));
        }

        JsonArray modules = new JsonArray();
        boolean includeBlocks = haltReasonOrNull == null;
        for (PlacedModule pm : p.placedModules) {
            JsonObject m = new JsonObject();
            m.addProperty("name", pm.template.name);
            m.addProperty("facing", pm.facing.name());
            m.add("worldOrigin", blockPosToJson(pm.worldOrigin));
            m.add("worldExit", blockPosToJson(pm.worldExit()));
            m.addProperty("width", pm.template.width);
            m.addProperty("height", pm.template.height);
            m.addProperty("depth", pm.template.depth);
            if (includeBlocks) {
                JsonArray blocks = new JsonArray();
                for (var tb : pm.template.blocks) {
                    BlockPos worldPos = ModuleTransform.apply(tb.relativePos, pm.worldOrigin, pm.facing);
                    JsonObject b = new JsonObject();
                    b.addProperty("x", worldPos.getX());
                    b.addProperty("y", worldPos.getY());
                    b.addProperty("z", worldPos.getZ());
                    b.addProperty("blockId",
                        tb.blockState.getBlock().builtInRegistryHolder().key().location().toString());
                    blocks.add(b);
                }
                m.add("blocks", blocks);
            }
            modules.add(m);
        }
        root.add("modules", modules);

        JsonObject materials = new JsonObject();
        for (var e : p.materials.entrySet()) {
            String id = BuiltInRegistries.BLOCK.getKey(e.getKey()).toString();
            materials.addProperty(id, e.getValue());
        }
        root.add("materials", materials);

        root.addProperty("totalBlocks", p.totalBlocks);

        if (haltReasonOrNull != null) {
            JsonObject halted = new JsonObject();
            halted.addProperty("reason", haltReasonOrNull);
            halted.addProperty("blocksPlaced", p.blocksPlaced);
            halted.addProperty("totalBlocks", p.totalBlocks);
            halted.addProperty("fromPhase", p.phase.name());
            root.add("halted", halted);
        }

        return root;
    }

    /**
     * 归档到 mempalace。返回 ref 字符串（{@code wing=.../room=...}），
     * 调用方应存入 {@code project.mempalaceRefs}。
     */
    public static String archive(BuildProject project, BuildPhase phase, String roomSuffix, JsonObject payload) {
        try {
            String room = project.id + "_" + roomSuffix;
            String wing = switch (phase) {
                case DESIGN -> "build_designs";
                case FAILED -> "build_halted";
                case AWAITING_ACCEPTANCE -> "build_acceptance";
                case COMPLETED -> "built_structures";
                default -> "build_misc";
            };
            Map<String, Object> args = Map.of(
                "wing", wing,
                "room", room,
                "content", GSON.toJson(payload),
                "added_by", "steve-ai"
            );
            String res = MCPToolRegistry.getInstance().callTool("mempalace:mempalace_add_drawer", args);
            String ref = "wing=" + wing + "/room=" + room;
            SteveMod.LOGGER.info("Archived {} to mempalace {} (response: {})", phase, ref, truncate(res, 200));
            return ref;
        } catch (Exception e) {
            SteveMod.LOGGER.warn("Failed to archive {} to mempalace: {}", phase, e.getMessage());
            return null;
        }
    }

    private static JsonObject blockPosToJson(BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
