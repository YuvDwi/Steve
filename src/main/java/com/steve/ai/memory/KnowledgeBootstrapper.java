package com.steve.ai.memory;

import com.steve.ai.SteveMod;
import com.steve.ai.mcp.MCPToolRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-shot uploader of local {@code docs/knowledges/*.md} into the
 * mempalace {@code build_knowledge} wing. Each markdown file becomes one
 * room; the file's basename (without {@code .md}) is the room id.
 *
 * <p>Called from {@code SteveMod} startup hooks. Failures are logged and
 * swallowed — the rest of Steve should still boot even if mempalace is down.</p>
 */
public class KnowledgeBootstrapper {

    private static final String KNOWLEDGE_DIR = "docs/knowledges";
    private static final String WING = "build_knowledge";

    /**
     * Scan the run-directory {@code docs/knowledges/} folder (alongside the
     * mod JAR) and push every {@code *.md} to mempalace. Run from
     * {@code SteveMod} after MCP registry init.
     */
    public static void syncLocalKnowledgeToMempalace() {
        Path dir = resolveKnowledgeDir();
        if (dir == null || !Files.isDirectory(dir)) {
            SteveMod.LOGGER.info("KnowledgeBootstrapper: no {} directory (skipping sync)", KNOWLEDGE_DIR);
            return;
        }

        MCPToolRegistry mcp = MCPToolRegistry.getInstance();
        if (mcp == null) {
            SteveMod.LOGGER.warn("KnowledgeBootstrapper: MCP registry not initialized, skipping");
            return;
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .forEach(p -> uploadOne(mcp, p));
        } catch (IOException e) {
            SteveMod.LOGGER.warn("KnowledgeBootstrapper: failed to list {}: {}", KNOWLEDGE_DIR, e.getMessage());
        }
    }

    private static void uploadOne(MCPToolRegistry mcp, Path file) {
        String room = stripExt(file.getFileName().toString());
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String res = mcp.callTool("mempalace:mempalace_add_drawer", Map.of(
                "wing", WING,
                "room", room,
                "content", content,
                "added_by", "steve-ai-bootstrap"
            ));
            SteveMod.LOGGER.info("KnowledgeBootstrapper: uploaded '{}' to {} ({} bytes) -> {}",
                room, WING, content.length(), truncate(res, 100));
        } catch (Exception e) {
            SteveMod.LOGGER.warn("KnowledgeBootstrapper: failed to upload '{}': {}", room, e.getMessage());
        }
    }

    private static Path resolveKnowledgeDir() {
        // 1. try run-dir / docs/knowledges (production: alongside the world folder).
        //    SteveMod holds the active MinecraftServer after ServerStartingEvent fires.
        net.minecraft.server.MinecraftServer srv = com.steve.ai.SteveMod.getServer();
        if (srv != null) {
            Path runDir = srv.getServerDirectory().toPath().resolve(KNOWLEDGE_DIR);
            if (Files.isDirectory(runDir)) return runDir;
        }

        // 2. try the working directory (dev convenience: runClient CWD)
        Path cwd = Path.of("").toAbsolutePath().resolve(KNOWLEDGE_DIR);
        if (Files.isDirectory(cwd)) return cwd;

        return null;
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
