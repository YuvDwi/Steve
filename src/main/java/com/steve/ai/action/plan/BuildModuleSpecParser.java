package com.steve.ai.action.plan;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.structure.PlacedModule;
import com.steve.ai.structure.StructureTemplateLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;

public final class BuildModuleSpecParser {

    private BuildModuleSpecParser() {}

    public static List<Map<String, Object>> parse(Task task) {
        List<Map<String, Object>> moduleList = task.getModuleListParameter("structures");
        if (moduleList == null || moduleList.isEmpty()) {
            String single = task.getStringParameter("structure");
            if (single == null || single.isEmpty()) {
                single = task.getStringParameter("structure", "unknown");
            }
            moduleList = new ArrayList<>(1);
            Map<String, Object> spec = new HashMap<>();
            spec.put("name", single);
            moduleList.add(spec);
        }

        moduleList = expandSingleStructureFallback(moduleList,
            () -> SteveConfig.MAX_TEMPLATES_PER_PLAN.get());

        int cap = SteveConfig.MAX_TEMPLATES_PER_PLAN.get();
        if (moduleList.size() > cap) {
            SteveMod.LOGGER.warn("PlanBuildAction: LLM requested {} modules, capping to {}",
                moduleList.size(), cap);
            moduleList = new ArrayList<>(moduleList.subList(0, cap));
        }
        return moduleList;
    }

    public static List<String> extractNames(List<Map<String, Object>> moduleList) {
        List<String> names = new ArrayList<>(moduleList.size());
        for (Map<String, Object> m : moduleList) {
            Object n = m.get("name");
            if (n != null) names.add(n.toString());
        }
        return names;
    }

    static List<Map<String, Object>> expandSingleStructureFallback(
            List<Map<String, Object>> moduleList,
            IntSupplier capSupplier) {
        if (moduleList == null || moduleList.size() != 1) {
            return moduleList;
        }
        Object nameObj = moduleList.get(0).get("name");
        if (nameObj == null) {
            return moduleList;
        }
        String headName = nameObj.toString();
        List<String> siblings =
            StructureTemplateLoader.getSiblingStructuresOfSameType(headName);
        if (siblings == null) {
            SteveMod.LOGGER.warn(
                "PlanBuildAction: single structure '{}' not found in StructureTemplateLoader, keeping 1-element list.",
                headName);
            return moduleList;
        }
        if (siblings.size() <= 1) {
            return moduleList;
        }
        return composeFromSiblings(headName, siblings,
            StructureTemplateLoader.getTypeFor(headName), capSupplier);
    }

    static List<Map<String, Object>> composeFromSiblings(
            String headName,
            List<String> siblings,
            String typeName,
            IntSupplier capSupplier) {
        List<String> ordered = new ArrayList<>(siblings.size());
        ordered.add(headName);
        for (String s : siblings) {
            if (!s.equals(headName)) ordered.add(s);
        }
        int fallbackCap = capSupplier.getAsInt();
        int keep = Math.min(ordered.size(), fallbackCap);
        if (ordered.size() > fallbackCap) {
            SteveMod.LOGGER.warn(
                "PlanBuildAction: same-type expansion produced {} templates, capping to {}",
                ordered.size(), fallbackCap);
        }
        List<Map<String, Object>> expanded = new ArrayList<>(keep);
        for (int i = 0; i < keep; i++) {
            Map<String, Object> spec = new HashMap<>();
            spec.put("name", ordered.get(i));
            expanded.add(spec);
        }
        SteveMod.LOGGER.warn(
            "PlanBuildAction: LLM returned single structure '{}' for plan-mode, "
          + "auto-expanding to {} templates of type '{}' (LLM ignored ≥2 rule).",
            headName, expanded.size(), typeName);
        return expanded;
    }

    public static int readInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return def; }
        }
        return def;
    }

    public static PlacedModule.Facing readFacing(Map<String, Object> m, String key, PlacedModule.Facing def) {
        Object v = m.get(key);
        if (v == null) return def;
        String s = v.toString().trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "N", "NORTH" -> PlacedModule.Facing.N;
            case "E", "EAST"  -> PlacedModule.Facing.E;
            case "S", "SOUTH" -> PlacedModule.Facing.S;
            case "W", "WEST"  -> PlacedModule.Facing.W;
            default -> def;
        };
    }
}
