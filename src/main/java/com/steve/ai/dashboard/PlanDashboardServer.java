package com.steve.ai.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.event.EventBus;
import com.steve.ai.event.plan.PlanEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Embedded HTTP server that exposes the plan dashboard to a browser.
 *
 * <p>Routes:</p>
 * <ul>
 *   <li>{@code GET /}             — serves {@code /assets/steve/dashboard/index.html}</li>
 *   <li>{@code GET /<file>}       — serves static files from the dashboard classpath folder</li>
 *   <li>{@code GET /events}       — Server-Sent Events stream of {@link PlanEvent}s</li>
 *   <li>{@code POST /command}     — accepts {@code {action:"approve"|"halt", projectId:"..."}}</li>
 * </ul>
 *
 * <p>Bind address: {@code 127.0.0.1:<port>} only. Listens for {@code /steve dashboard}
 * to start it; {@code /steve dashboard stop} stops it. The server is fully
 * optional — players who never open the dashboard pay no cost beyond
 * {@code EventBus.publish} being a no-op when no subscribers exist.</p>
 */
public class PlanDashboardServer {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String CORS_ORIGIN = "http://localhost:5173";

    private final int port;
    private HttpServer http;
    private final List<EventBus.Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicReference<List<SseClient>> clients = new AtomicReference<>(new CopyOnWriteArrayList<>());

    public PlanDashboardServer(int port) {
        this.port = port;
    }

    public int getPort() { return port; }

    public synchronized void start() throws IOException {
        if (http != null) return;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        http.createContext("/events", new SseHandler());
        http.createContext("/command", new CommandHandler());
        http.createContext("/chat", new ChatHandler());
        http.createContext("/plan", new PlanStartHandler());
        http.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "plan-dashboard-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        LOG.info("Plan dashboard started at http://127.0.0.1:{}/ (CORS: {})", port, CORS_ORIGIN);

        // Subscribe to all plan events; forward each to every connected SSE client.
        subscriptions.addAll(SteveMod.subscribeToAllPlanEvents(this::broadcast));
    }

    public synchronized void stop() {
        if (http != null) {
            // Close all SSE clients so they notice.
            for (SseClient c : clients.get()) {
                try { c.close(); } catch (Exception ignored) {}
            }
            clients.get().clear();
            http.stop(0);
            http = null;
        }
        subscriptions.forEach(EventBus.Subscription::unsubscribe);
        subscriptions.clear();
        LOG.info("Plan dashboard stopped");
    }

    public boolean isRunning() { return http != null; }

    /** Push one event to every connected SSE client. */
    private void broadcast(PlanEvent event) {
        String sse = PlanEventJson.toSseData(event);
        for (SseClient c : clients.get()) {
            try {
                c.write(sse);
            } catch (Exception e) {
                // Client gone — drop it lazily.
                clients.get().remove(c);
            }
        }
    }

    // ===== Handlers =====

    /** Sets CORS headers on every response and short-circuits preflight OPTIONS. */
    private void applyCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Vary", "Origin");
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
        }
    }

    private final class SseHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.sendResponseHeaders(200, 0);

            SseClient client = new SseClient(exchange);
            clients.get().add(client);

            // Send an initial snapshot so the UI doesn't show "empty" for events
            // that happened before the browser connected. buildSnapshot() reads
            // SteveEntity state, which is main-thread-only, so we hop back to
            // the server thread for the read and return here to write the SSE.
            final MinecraftServer mc = SteveMod.getServer();
            if (mc == null) {
                // No server yet — server thread can't help. Send an idle
                // snapshot synchronously and let the client wait for events.
                try {
                    client.write("data: " + new Gson().toJson(PlanEventJson.idleSnapshot()) + "\n\n");
                } catch (Exception e) {
                    LOG.warn("Failed to write idle snapshot: {}", e.getMessage());
                    clients.get().remove(client);
                }
                return;
            }
            try {
                mc.execute(() -> {
                    JsonObject snap;
                    try {
                        snap = buildSnapshot();
                    } catch (Exception e) {
                        LOG.error("buildSnapshot() failed; sending idle snapshot. Cause:", e);
                        snap = PlanEventJson.idleSnapshot();
                    }
                    try {
                        client.write("data: " + new Gson().toJson(snap) + "\n\n");
                    } catch (Exception e) {
                        LOG.warn("Failed to write initial snapshot to SSE client: {}", e.getMessage());
                        clients.get().remove(client);
                    }
                });
            } catch (Exception e) {
                // mc.execute() should not throw, but be defensive: the
                // HttpServer would otherwise turn this into a 500.
                LOG.error("Failed to schedule snapshot write on main thread: ", e);
            }
        }
    }

    private final class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject req;
            try {
                req = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                respondJson(exchange, 400, error("invalid_json", e.getMessage()));
                return;
            }
            String action = req.has("action") ? req.get("action").getAsString() : "";
            String projectId = req.has("projectId") ? req.get("projectId").getAsString() : "";

            // Both the project lookup and the mutation need to run on the
            // main server thread (SteveEntity / BuildProject are main-thread-
            // only). Hop there, then write the HTTP response from the main
            // thread too — the HttpServer lets any thread send the response.
            MinecraftServer mc = SteveMod.getServer();
            if (mc == null) {
                respondJson(exchange, 503, error("server_not_ready", "Minecraft server not running"));
                return;
            }
            try {
                mc.execute(() -> {
                    try {
                        SteveEntity target = findSteveByProjectId(projectId);
                        if (target == null) {
                            respondJson(exchange, 404, error("project_not_found",
                                "No active BuildProject with id=" + projectId));
                            return;
                        }
                        try {
                            switch (action) {
                                case "approve" -> {
                                    target.getActionExecutor().approveCurrentBuild();
                                    broadcastLocal(Map.of("type", "plan.command_ack",
                                        "action", "approve", "projectId", projectId, "ok", true));
                                }
                                case "halt" -> {
                                    target.getActionExecutor().haltCurrentBuild("player halted via dashboard");
                                    broadcastLocal(Map.of("type", "plan.command_ack",
                                        "action", "halt", "projectId", projectId, "ok", true));
                                }
                                default -> {
                                    respondJson(exchange, 400, error("unknown_action", action));
                                    return;
                                }
                            }
                            respondJson(exchange, 202, Map.of("ok", true, "queued", action, "projectId", projectId));
                        } catch (Exception e) {
                            LOG.warn("Command '{}' for project {} failed: {}", action, projectId, e.getMessage());
                            broadcastLocal(Map.of("type", "plan.command_ack",
                                "action", action, "projectId", projectId, "ok", false,
                                "error", e.getMessage()));
                            respondJson(exchange, 500, error("command_failed", e.getMessage()));
                        }
                    } catch (Exception e) {
                        LOG.error("Command handler failed on main thread: ", e);
                        try { respondJson(exchange, 500, error("server_error", e.getMessage())); }
                        catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                LOG.error("Failed to schedule command on main thread: ", e);
                respondJson(exchange, 503, error("server_not_ready", e.getMessage()));
            }
        }
    }

    private final class ChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject req;
            try {
                req = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                respondJson(exchange, 400, error("invalid_json", e.getMessage()));
                return;
            }
            String steveName = req.has("steveName") ? req.get("steveName").getAsString() : "";
            String message = req.has("message") ? req.get("message").getAsString() : "";
            if (message.isEmpty()) {
                respondJson(exchange, 400, error("empty_message", "message must be non-empty"));
                return;
            }

            MinecraftServer mc = SteveMod.getServer();
            if (mc == null) {
                respondJson(exchange, 503, error("server_not_ready", "Minecraft server not running"));
                return;
            }
            try {
                mc.execute(() -> {
                    try {
                        SteveEntity target = findSteveByName(steveName);
                        if (target == null) {
                            respondJson(exchange, 404, error("steve_not_found",
                                "No Steve with name=" + steveName));
                            return;
                        }
                        try {
                            // Echo the user's message back as a USER chat bubble so
                            // every connected dashboard sees the same conversation.
                            String projectId = target.getActionExecutor().getActiveBuildProject() != null
                                ? target.getActionExecutor().getActiveBuildProject().id : "";
                            broadcastLocal(java.util.Map.of(
                                "type", "plan.chat",
                                "projectId", projectId,
                                "steveName", target.getSteveName(),
                                "sender", "USER",
                                "message", message,
                                "timestamp", java.time.Instant.now().toString()));

                            // Kick off the natural-language pipeline on the main thread.
                            target.getActionExecutor().processNaturalLanguageCommand(message);
                            respondJson(exchange, 202, java.util.Map.of(
                                "ok", true, "queued", "chat", "steveName", target.getSteveName()));
                        } catch (Exception e) {
                            LOG.warn("Chat dispatch for {} failed: {}", target.getSteveName(), e.getMessage());
                            respondJson(exchange, 500, error("chat_failed", e.getMessage()));
                        }
                    } catch (Exception e) {
                        LOG.error("Chat handler failed on main thread: ", e);
                        try { respondJson(exchange, 500, error("server_error", e.getMessage())); }
                        catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                LOG.error("Failed to schedule chat on main thread: ", e);
                respondJson(exchange, 503, error("server_not_ready", e.getMessage()));
            }
        }
    }

    /** Find a Steve by exact name (case-insensitive). Returns null if not found. */
    private SteveEntity findSteveByName(String name) {
        if (name == null || name.isEmpty()) return null;
        for (SteveEntity s : SteveMod.getSteveManager().getAllSteves()) {
            if (s.getSteveName().equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    /** Find the Steve nearest to any online player. Used by /plan to pick a
     *  target when the browser didn't name one. Returns null if no Steves
     *  exist. We scan all players, not the "local" one, because in
     *  multiplayer the dashboard player may not be in any level. */
    private SteveEntity findNearestSteveToLocalPlayer() {
        SteveEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (SteveEntity s : SteveMod.getSteveManager().getAllSteves()) {
            for (var player : s.level().players()) {
                if (player.isSpectator()) continue;
                double d = player.distanceTo(s);
                if (d < nearestDist) { nearestDist = d; nearest = s; }
            }
            // If a Steve has no nearby player, fall back to "any" ordering
            if (nearest == null) nearest = s;
        }
        return nearest;
    }

    private final class PlanStartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) return;
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject req;
            try {
                req = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                respondJson(exchange, 400, error("invalid_json", e.getMessage()));
                return;
            }
            String description = req.has("description") ? req.get("description").getAsString().trim() : "";
            if (description.isEmpty()) {
                respondJson(exchange, 400, error("empty_description", "description must be non-empty"));
                return;
            }

            MinecraftServer mc = SteveMod.getServer();
            if (mc == null) {
                respondJson(exchange, 503, error("server_not_ready", "Minecraft server not running"));
                return;
            }
            try {
                mc.execute(() -> {
                    try {
                        SteveEntity target = findNearestSteveToLocalPlayer();
                        if (target == null) {
                            respondJson(exchange, 404, error("no_steves",
                                "No Steve available — spawn one in Minecraft first"));
                            return;
                        }
                        // Reject if a plan is already in flight for this Steve
                        if (target.getActionExecutor().getActiveBuildProject() != null) {
                            respondJson(exchange, 409, error("plan_in_progress",
                                target.getSteveName() + " is already building. Halt it first."));
                            return;
                        }
                        try {
                            target.getActionExecutor().startPlannedBuild(description);
                            respondJson(exchange, 202, java.util.Map.of(
                                "ok", true, "queued", "plan",
                                "steveName", target.getSteveName(),
                                "description", description));
                        } catch (Exception e) {
                            LOG.warn("Plan start for {} failed: {}", target.getSteveName(), e.getMessage());
                            respondJson(exchange, 500, error("plan_failed", e.getMessage()));
                        }
                    } catch (Exception e) {
                        LOG.error("PlanStartHandler failed on main thread: ", e);
                        try { respondJson(exchange, 500, error("server_error", e.getMessage())); }
                        catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                LOG.error("Failed to schedule plan start on main thread: ", e);
                respondJson(exchange, 503, error("server_not_ready", e.getMessage()));
            }
        }
    }

    // ===== Helpers =====

    private void respondJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] data = new Gson().toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static Map<String, Object> error(String code, String detail) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", false);
        m.put("error", code);
        if (detail != null) m.put("detail", detail);
        return m;
    }

    /** Send a synthetic event (not from {@code PlanEventBus}) to all clients. */
    private void broadcastLocal(Map<String, Object> payload) {
        String sse = "data: " + new Gson().toJson(payload) + "\n\n";
        for (SseClient c : clients.get()) {
            try { c.write(sse); } catch (Exception ignored) {}
        }
    }

    /** Look up the Steve whose active BuildProject matches {@code projectId}. */
    private SteveEntity findSteveByProjectId(String projectId) {
        for (SteveEntity s : SteveMod.getSteveManager().getAllSteves()) {
            var p = s.getActionExecutor().getActiveBuildProject();
            if (p != null && p.id.equals(projectId)) {
                return s;
            }
        }
        return null;
    }

    private JsonObject buildSnapshot() {
        // Always ship the list of all active Steves so the browser can target
        // a chat / plan start even when no plan is currently running.
        java.util.List<String> allNames = new java.util.ArrayList<>();
        for (SteveEntity s : SteveMod.getSteveManager().getAllSteves()) {
            allNames.add(s.getSteveName());
        }
        for (SteveEntity s : SteveMod.getSteveManager().getAllSteves()) {
            var p = s.getActionExecutor().getActiveBuildProject();
            if (p == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("type", "snapshot");
            o.addProperty("projectId", p.id);
            o.addProperty("steveName", s.getSteveName());
            o.add("steves", new Gson().toJsonTree(allNames));
            o.addProperty("command", p.command);
            o.addProperty("phase", p.phase.name());
            o.addProperty("blocksPlaced", p.blocksPlaced);
            o.addProperty("totalBlocks", p.totalBlocks);
            o.add("materials", new Gson().toJsonTree(p.materials.entrySet().stream()
                .map(e -> {
                    JsonObject m = new JsonObject();
                    m.addProperty("name", e.getKey().getName().getString());
                    m.addProperty("count", e.getValue());
                    return m;
                }).toList()));
            // Flatten all placed modules into one world-space block list so
            // the dashboard can render the structure in 3D immediately on
            // connect. World coordinates go through ModuleTransform.apply
            // — the same helper PlanBuildAction.placeNextBlock uses — so
            // the preview and the placed world cannot diverge.
            java.util.List<JsonObject> flat = new java.util.ArrayList<>();
            for (var pm : p.placedModules) {
                for (var tb : pm.template.blocks) {
                    net.minecraft.core.BlockPos worldPos = com.steve.ai.structure.ModuleTransform.apply(
                        tb.relativePos, pm.worldOrigin, pm.facing);
                    JsonObject b = new JsonObject();
                    b.addProperty("x", worldPos.getX());
                    b.addProperty("y", worldPos.getY());
                    b.addProperty("z", worldPos.getZ());
                    b.addProperty("blockId", tb.blockState.getBlock().builtInRegistryHolder()
                        .key().location().toString());
                    flat.add(b);
                }
            }
            o.add("blocks", new Gson().toJsonTree(flat));
            String ref = p.mempalaceRefs.get(com.steve.ai.llm.react.BuildPhase.DESIGN);
            if (ref != null) o.addProperty("mempalaceRef", ref);
            return o;
        }
        return PlanEventJson.idleSnapshot(allNames);
    }

    // StaticHandler removed: the Vite project (../web) now serves the page itself.
    // PlanDashboardServer only handles /events (SSE) and /command (POST).

    /** Wrapper around an open HTTP exchange that lets us write SSE chunks and
     *  detect client disconnect. */
    private static final class SseClient {
        private final HttpExchange exchange;
        private final OutputStream out;
        private volatile boolean closed = false;

        SseClient(HttpExchange exchange) throws IOException {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        synchronized void write(String chunk) throws IOException {
            if (closed) throw new IOException("client closed");
            out.write(chunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        void close() {
            closed = true;
            try { exchange.close(); } catch (Exception ignored) {}
        }
    }
}
