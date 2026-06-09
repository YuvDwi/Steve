package com.steve.ai.memory;

import com.steve.ai.SteveMod;
import com.steve.ai.mcp.MCPToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Construction domain knowledge base backed by the mempalace {@code build_knowledge} wing.
 *
 * <p>Stores/queries the construction-domain knowledge articles (e.g. 公路工程 4 阶段
 * 流程) that Steve's LLM should consult during plan design. Articles live as
 * drawers in mempalace, not in the local filesystem, so multiple Steve agents
 * and even other tools can share one source of truth.</p>
 *
 * <p>Modeled after {@link WorldKnowledge}: constructor synchronously queries
 * the topic index and stores it in instance fields; getters return the
 * snapshot. A short TTL cache covers the single-drawer body fetches so a busy
 * LLM step does not hammer the MCP endpoint.</p>
 */
public class ConstructionKnowledgeService {

    private static final String WING = "build_knowledge";
    private static final long CACHE_TTL_MS = 30_000L;

    private static final Gson GSON = new Gson();

    private final MCPToolRegistry mcp;

    /** Cached topic index, refreshed on construct and on TTL expiry. */
    private List<String> topics = List.of();
    private long topicsLoadedAtMs = 0L;

    /** Per-room body cache. Each entry has its own timestamp. */
    private final Map<String, String> topicBodyCache = new ConcurrentHashMap<>();
    private final Map<String, Long> topicBodyLoadedAtMs = new ConcurrentHashMap<>();

    public ConstructionKnowledgeService() {
        this.mcp = MCPToolRegistry.getInstance();
        try {
            refreshTopics();
        } catch (Exception e) {
            SteveMod.LOGGER.warn("ConstructionKnowledgeService: initial topic load failed: {}", e.getMessage());
        }
    }

    /** Topic index (room names in the {@code build_knowledge} wing). Cached. */
    public List<String> getTopics() {
        if (topics == null || System.currentTimeMillis() - topicsLoadedAtMs > CACHE_TTL_MS) {
            try { refreshTopics(); } catch (Exception e) {
                SteveMod.LOGGER.warn("ConstructionKnowledgeService: topic refresh failed: {}", e.getMessage());
            }
        }
        return topics == null ? List.of() : topics;
    }

    /** Body of a single topic. Cached per room with TTL. */
    public String getTopic(String room) {
        Long loaded = topicBodyLoadedAtMs.get(room);
        if (loaded != null && System.currentTimeMillis() - loaded < CACHE_TTL_MS) {
            return topicBodyCache.get(room);
        }
        String body;
        try {
            String json = mcp.callTool("mempalace:mempalace_get_drawer",
                Map.of("wing", WING, "room", room));
            body = parseDrawerBody(json, room);
        } catch (Exception e) {
            SteveMod.LOGGER.warn("ConstructionKnowledgeService: getTopic({}) failed: {}", room, e.getMessage());
            body = null;
        }
        topicBodyCache.put(room, body);
        topicBodyLoadedAtMs.put(room, System.currentTimeMillis());
        return body;
    }

    private void refreshTopics() {
        String json = mcp.callTool("mempalace:mempalace_list_drawers", Map.of("wing", WING));
        this.topics = parseTopicList(json);
        this.topicsLoadedAtMs = System.currentTimeMillis();
    }

    /** Parse {@code mempalace_list_drawers} response. Returns room names. */
    static List<String> parseTopicList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el == null || el.isJsonNull()) return Collections.emptyList();
            // mcp error wrapper: {"error": "..."}
            if (el.isJsonObject() && el.getAsJsonObject().has("error")) return Collections.emptyList();
            JsonArray arr = el.getAsJsonArray();
            List<String> names = new ArrayList<>(arr.size());
            for (JsonElement item : arr) names.add(item.getAsString());
            return names;
        } catch (Exception e) {
            SteveMod.LOGGER.warn("ConstructionKnowledgeService: failed to parse topic list: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Parse {@code mempalace_get_drawer} response. Pulls {@code content} field. */
    static String parseDrawerBody(String json, String room) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (el == null || el.isJsonNull()) return null;
            if (el.isJsonObject() && el.getAsJsonObject().has("error")) return null;
            JsonObject obj = el.getAsJsonObject();
            JsonElement content = obj.get("content");
            return content == null || content.isJsonNull() ? null : content.getAsString();
        } catch (Exception e) {
            SteveMod.LOGGER.warn("ConstructionKnowledgeService: failed to parse drawer '{}': {}", room, e.getMessage());
            return null;
        }
    }
}
