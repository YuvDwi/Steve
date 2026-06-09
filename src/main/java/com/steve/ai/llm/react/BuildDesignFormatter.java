package com.steve.ai.llm.react;

import com.steve.ai.action.plan.BuildProject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure static helpers that turn a {@link BuildProject} into chat-friendly text.
 *
 * <p>No side effects, no Minecraft types in the output — easy to unit-test.</p>
 */
public final class BuildDesignFormatter {

    private BuildDesignFormatter() {}

    /** Top of the design doc: name, id, command. */
    public static String header(BuildProject project) {
        return String.format(Locale.ROOT,
            "========== %s 设计图 #%s ==========\n项目: 玩家指令\"%s\"",
            project.steve.getSteveName(), project.id, project.command);
    }

    /** Middle section: template list, dimensions, footprint, total blocks, origin, ETA, materials.
     *  Single-template output keeps the original format byte-for-byte; multi-template output
     *  shows a per-building breakdown with a global totals row. */
    public static String body(BuildProject project) {
        if (project.placedModules.isEmpty()) {
            return "(no templates loaded)";
        }
        if (project.placedModules.size() == 1) {
            return bodySingle(project);
        }
        return bodyMulti(project);
    }

    private static String bodySingle(BuildProject project) {
        var pm = project.placedModules.get(0);
        var t = pm.template;
        int footprint = t.width * t.depth;
        int total = t.blocks.size();
        int etaTicks = project.totalBlocks * 5; // BUILD_TICK_DELAY default

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "模板: %s\n", t.name));
        sb.append(String.format(Locale.ROOT, "尺寸: %d × %d × %d (长 × 高 × 深)\n", t.width, t.height, t.depth));
        sb.append(String.format(Locale.ROOT, "占地: %d 平方米\n", footprint));
        sb.append(String.format(Locale.ROOT, "方块总数: %d\n", total));
        sb.append("材料清单:").append(System.lineSeparator());
        appendMaterials(sb, project.materials, total);
        sb.append(String.format(Locale.ROOT, "原点坐标: %s\n", formatPos(project.originPos)));
        sb.append("协同分区: 4 个象限, 单 Steve 承担全部").append(System.lineSeparator());
        sb.append(String.format(Locale.ROOT, "预计耗时: 约 %d tick (≈ %d 秒)\n", etaTicks, etaTicks / 20));
        return sb.toString();
    }

    private static String bodyMulti(BuildProject project) {
        int footprintTotal = 0;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "子建筑清单 (%d 个):\n", project.placedModules.size()));
        for (int i = 0; i < project.placedModules.size(); i++) {
            var pm = project.placedModules.get(i);
            var t = pm.template;
            net.minecraft.core.BlockPos o = pm.worldOrigin != null ? pm.worldOrigin : project.originPos;
            footprintTotal += t.width * t.depth;
            sb.append(String.format(Locale.ROOT, "\n[%d/%d] %s\n", i + 1, project.placedModules.size(), t.name));
            sb.append(String.format(Locale.ROOT, "  尺寸: %d × %d × %d (长 × 高 × 深)\n", t.width, t.height, t.depth));
            sb.append(String.format(Locale.ROOT, "  占地: %d 平方米\n", t.width * t.depth));
            sb.append(String.format(Locale.ROOT, "  块数: %d\n", t.blocks.size()));
            sb.append(String.format(Locale.ROOT, "  原点: %s\n", formatPos(o)));
        }
        sb.append("\n--------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "总计: %d 子建筑, %d 块, 占地 %d 平方米\n",
            project.placedModules.size(), project.totalBlocks, footprintTotal));
        sb.append("材料清单:").append(System.lineSeparator());
        appendMaterials(sb, project.materials, project.totalBlocks);
        sb.append("协同分区: 4 个象限, 单 Steve 承担全部").append(System.lineSeparator());
        int etaTicks = project.totalBlocks * 5;
        sb.append(String.format(Locale.ROOT, "预计耗时: 约 %d tick (≈ %d 秒)\n", etaTicks, etaTicks / 20));
        return sb.toString();
    }

    /** Footer with player instructions and mempalace archive ref. */
    public static String footer(BuildProject project) {
        StringBuilder sb = new StringBuilder();
        sb.append("--------------------------------------------").append(System.lineSeparator());
        sb.append("输入 /steve approve 开始施工, /steve halt 放弃").append(System.lineSeparator());
        String ref = project.mempalaceRefs.get(BuildPhase.DESIGN);
        if (ref != null) {
            sb.append("已归档到 mempalace: ").append(ref).append(System.lineSeparator());
        }
        sb.append("============================================");
        return sb.toString();
    }

    /** Full design doc, joined with newlines. */
    public static String fullDesign(BuildProject project) {
        return header(project) + System.lineSeparator()
             + body(project)
             + footer(project);
    }

    /** Acceptance report: ✓/✗ per check. */
    public static String acceptanceReport(BuildProject project, List<AcceptanceCheck> checks) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "========== 验收报告 #%s ==========\n", project.id));
        for (var c : checks) {
            sb.append(c.passed ? "[✓] " : "[✗] ").append(c.label);
            if (c.detail != null && !c.detail.isEmpty()) {
                sb.append(": ").append(c.detail);
            }
            sb.append(System.lineSeparator());
        }
        sb.append("----------------------------------------").append(System.lineSeparator());
        sb.append("输入 /steve accept 正式交付, /steve halt 视为失败").append(System.lineSeparator());
        String ref = project.mempalaceRefs.get(BuildPhase.AWAITING_ACCEPTANCE);
        if (ref != null) {
            sb.append("已归档: ").append(ref).append(System.lineSeparator());
        }
        sb.append("======================================");
        return sb.toString();
    }

    /** Construction progress line, every 5 seconds during phase 3. */
    public static String progress(BuildProject project) {
        if (project.totalBlocks <= 0) {
            return String.format(Locale.ROOT, "[施工进度] %s 阶段 3/4 准备中", project.id);
        }
        int pct = (project.blocksPlaced * 100) / project.totalBlocks;
        return String.format(Locale.ROOT, "[施工进度] %s 阶段 3/4 主体建造 %d/%d blocks (%d%%)",
            project.id, project.blocksPlaced, project.totalBlocks, pct);
    }

    /** Halt/timeout message sent to nearest player. */
    public static String halted(BuildProject project, String reason) {
        return String.format(Locale.ROOT,
            "[%s] 工程中止: %s (阶段=%s, 已放置 %d/%d 块, 设计书保留在 mempalace %s)",
            project.steve.getSteveName(), reason, project.phase,
            project.blocksPlaced, project.totalBlocks,
            project.mempalaceRefs.getOrDefault(BuildPhase.DESIGN, "(none)"));
    }

    public static class AcceptanceCheck {
        public final boolean passed;
        public final String label;
        public final String detail;

        public AcceptanceCheck(boolean passed, String label, String detail) {
            this.passed = passed;
            this.label = label;
            this.detail = detail;
        }

        public static AcceptanceCheck ok(String label) {
            return new AcceptanceCheck(true, label, null);
        }

        public static AcceptanceCheck ok(String label, String detail) {
            return new AcceptanceCheck(true, label, detail);
        }

        public static AcceptanceCheck fail(String label, String detail) {
            return new AcceptanceCheck(false, label, detail);
        }
    }

    private static void appendMaterials(StringBuilder sb, Map<net.minecraft.world.level.block.Block, Integer> materials, int total) {
        // Sort by count desc for readability
        List<Map.Entry<net.minecraft.world.level.block.Block, Integer>> entries = new ArrayList<>(materials.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (var e : entries) {
            String name = e.getKey().getName().getString();
            int n = e.getValue();
            int pct = total > 0 ? (n * 100 / total) : 0;
            sb.append(String.format(Locale.ROOT, "  %-16s × %4d (%d%%)\n", name, n, pct));
        }
    }

    private static String formatPos(net.minecraft.core.BlockPos pos) {
        if (pos == null) return "(uncomputed)";
        return String.format(Locale.ROOT, "(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
    }
}
