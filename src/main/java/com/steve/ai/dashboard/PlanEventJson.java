package com.steve.ai.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.steve.ai.event.plan.PlanApprovedEvent;
import com.steve.ai.event.plan.PlanChatEvent;
import com.steve.ai.event.plan.PlanCreatedEvent;
import com.steve.ai.event.plan.PlanDesignReadyEvent;
import com.steve.ai.event.plan.PlanEvent;
import com.steve.ai.event.plan.PlanHaltedEvent;
import com.steve.ai.event.plan.PlanLogEvent;
import com.steve.ai.event.plan.PlanPhaseChangedEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serializes {@link PlanEvent}s to JSON for the SSE channel.
 *  Hand-rolled to avoid Gson reflection on every event class — keeps the
 *  wire format explicit and stable across changes. */
public final class PlanEventJson {

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();

    private PlanEventJson() {}

    /** Render the event to a JSON object with {@code type} field set to a stable
     *  string like {@code "plan.created"}. Returned as a {@link JsonObject} for
     *  callers that want to merge with snapshot data. */
    public static JsonObject toJson(PlanEvent event) {
        JsonObject o = new JsonObject();
        if (event instanceof PlanCreatedEvent e) {
            o.addProperty("type", "plan.created");
            o.addProperty("projectId", e.getProjectId());
            o.addProperty("steveName", e.getSteveName());
            o.addProperty("command", e.getCommand());
            o.add("templates", GSON.toJsonTree(e.getTemplates()));
            o.addProperty("phase", e.getPhase().name());
            o.addProperty("timestamp", e.getTimestamp().toString());
        } else if (event instanceof PlanDesignReadyEvent e) {
            o.addProperty("type", "plan.design_ready");
            o.addProperty("projectId", e.getProjectId());
            o.addProperty("design", e.getDesign());
            o.add("materials", GSON.toJsonTree(e.getMaterials()));
            o.addProperty("totalBlocks", e.getTotalBlocks());
            o.add("blocks", GSON.toJsonTree(e.getBlocks()));
            o.addProperty("timestamp", e.getTimestamp().toString());
        } else if (event instanceof PlanPhaseChangedEvent e) {
            o.addProperty("type", "plan.phase_changed");
            o.addProperty("projectId", e.getProjectId());
            o.addProperty("prev", e.getPrev().name());
            o.addProperty("next", e.getNext().name());
            if (e.getDeadlineMs() != null) o.addProperty("deadlineMs", e.getDeadlineMs());
            o.addProperty("timestamp", e.getTimestamp().toString());
        } else if (event instanceof PlanApprovedEvent e) {
            o.addProperty("type", "plan.approved");
            o.addProperty("projectId", e.getProjectId());
            o.addProperty("phase", e.getPhase().name());
            o.addProperty("approvedBy", e.getApprovedBy());
            o.addProperty("timestamp", e.getTimestamp().toString());
        } else if (event instanceof PlanHaltedEvent e) {
            o.addProperty("type", "plan.halted");
            o.addProperty("projectId", e.getProjectId());
            o.addProperty("phase", e.getPhase().name());
            o.addProperty("reason", e.getReason());
            if (e.getMempalaceRef() != null) o.addProperty("mempalaceRef", e.getMempalaceRef());
            o.addProperty("blocksPlaced", e.getBlocksPlaced());
            o.addProperty("totalBlocks", e.getTotalBlocks());
            o.addProperty("timestamp", e.getTimestamp().toString());
        } else if (event instanceof PlanLogEvent e) {
            o.addProperty("type", "plan.log");
            o.addProperty("projectId", e.getProjectId());
            o.addProperty("severity", e.getSeverity().name());
            o.addProperty("message", e.getMessage());
            o.addProperty("timestamp", e.getTimestamp().toString());
        } else if (event instanceof PlanChatEvent e) {
            o.addProperty("type", "plan.chat");
            o.addProperty("projectId", e.getProjectId());
            o.addProperty("steveName", e.getSteveName());
            o.addProperty("sender", e.getSender().name());
            o.addProperty("message", e.getMessage());
            o.addProperty("timestamp", e.getTimestamp().toString());
        } else {
            o.addProperty("type", "plan.unknown");
            o.addProperty("timestamp", Instant.now().toString());
        }
        return o;
    }

    /** Encode as a single SSE data line: {@code data: <json>\n\n}. */
    public static String toSseData(PlanEvent event) {
        return "data: " + GSON.toJson(toJson(event)) + "\n\n";
    }

    /** Build a snapshot object representing "no active project". Sent on SSE
     *  connect when {@code SteveManager} has no Steve with an active build.
     *  The caller may pass an explicit list of active Steve names so the
     *  browser can target a chat / plan even when no plan is in flight. */
    public static JsonObject idleSnapshot(java.util.List<String> steves) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "snapshot");
        o.addProperty("projectId", "");
        o.addProperty("idle", true);
        o.addProperty("timestamp", Instant.now().toString());
        o.add("steves", GSON.toJsonTree(steves == null ? java.util.List.of() : steves));
        return o;
    }

    /** Backwards-compatible overload used by tests. */
    public static JsonObject idleSnapshot() {
        return idleSnapshot(java.util.List.of());
    }

    /** Helper for test code: pretty-print a JSON object as a Map for assertions. */
    public static Map<String, Object> toMap(JsonObject o) {
        Map<String, Object> out = new LinkedHashMap<>();
        o.entrySet().forEach(e -> out.put(e.getKey(), GSON.fromJson(e.getValue(), Object.class)));
        return out;
    }
}