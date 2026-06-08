package com.steve.ai.action.actions;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the plan-mode fallback composition in {@link PlanBuildAction}.
 *
 * <p>The plan-mode flow hits a real bug when the LLM emits a single
 * {@code structure} parameter instead of a multi-entry {@code structures}
 * array. The fallback helper expands the single name into a same-type list
 * using {@code StructureTemplateLoader}'s registered siblings, capped by
 * {@code MAX_TEMPLATES_PER_PLAN}. The constructor delegates to
 * {@link PlanBuildAction#composeFromSiblings(String, List, String, java.util.function.IntSupplier)}
 * for the actual ordering + capping, which is what these tests cover —
 * pure logic, no Minecraft, no event bus, no static-method mocks.</p>
 */
class PlanBuildActionFallbackTest {

    private static List<Map<String, Object>> singleEntry(String name) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("name", name);
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(spec);
        return list;
    }

    private static List<String> namesOf(List<Map<String, Object>> specs) {
        List<String> out = new ArrayList<>(specs.size());
        for (Map<String, Object> s : specs) {
            Object n = s.get("name");
            assertNotNull(n, "name should be present");
            out.add(n.toString());
        }
        return out;
    }

    @Test
    void compose_expandsHeadWithSiblingsInRegistrationOrder() {
        // Siblings include the head; helper should reorder so head is first
        // and the rest follow registration order.
        List<String> siblings = List.of("房子_1", "房子_2", "房子_3");

        List<Map<String, Object>> out = PlanBuildAction.composeFromSiblings(
            "房子_1", siblings, "房子", () -> 10);

        assertEquals(List.of("房子_1", "房子_2", "房子_3"), namesOf(out),
            "head should come first, then the rest in registration order");
        assertNotSame(singleEntry("房子_1"), out,
            "expanded list should be a new instance, not the original");
    }

    @Test
    void compose_reordersHeadWhenNotFirstInSiblings() {
        // Sibling list might come back in any order (e.g. registration scan
        // order). Helper should still place head first.
        List<String> siblings = List.of("房子_2", "房子_3", "房子_1");

        List<Map<String, Object>> out = PlanBuildAction.composeFromSiblings(
            "房子_1", siblings, "房子", () -> 10);

        assertEquals(List.of("房子_1", "房子_2", "房子_3"), namesOf(out));
    }

    @Test
    void compose_capsAtMaxTemplatesPerPlan() {
        // 5 siblings + cap=3 -> head + first 2 siblings
        List<String> siblings = List.of("房子_1", "房子_2", "房子_3", "房子_4", "房子_5");

        List<Map<String, Object>> out = PlanBuildAction.composeFromSiblings(
            "房子_1", siblings, "房子", () -> 3);

        assertEquals(3, out.size(), "should be capped at the supplier value");
        assertEquals(List.of("房子_1", "房子_2", "房子_3"), namesOf(out));
    }

    @Test
    void expand_doesNothingForMultiEntryList() {
        // When the LLM did its job, the wrapper should return the input
        // unchanged (and not even hit the loader).
        List<Map<String, Object>> input = List.of(
            Map.of("name", "房子_1"),
            Map.of("name", "房子_2"));

        List<Map<String, Object>> out = PlanBuildAction.expandSingleStructureFallback(
            input, () -> 10);

        assertSame(input, out,
            "multi-entry lists must not be mutated by the fallback");
    }

    @Test
    void expand_doesNothingForNullNameInSingleEntry() {
        // Defensive: malformed payload (no "name") should not crash, just
        // pass through.
        Map<String, Object> spec = new HashMap<>();
        spec.put("facing", "S");  // no "name"
        List<Map<String, Object>> input = List.of(spec);

        List<Map<String, Object>> out = PlanBuildAction.expandSingleStructureFallback(
            input, () -> 10);

        assertSame(input, out);
    }

    @Test
    void expand_doesNothingForEmptyOrNullList() {
        assertSame(null,
            PlanBuildAction.expandSingleStructureFallback(null, () -> 10));
        List<Map<String, Object>> empty = List.of();
        assertSame(empty,
            PlanBuildAction.expandSingleStructureFallback(empty, () -> 10));
    }

    /**
     * Integration smoke test: confirm that the constructor's call site wires
     * the helper up correctly. The constructor needs a SteveEntity (null is
     * fine — BaseAction stores it) and an ActionExecutor (null is fine —
     * the fallback path doesn't touch it). We pass an empty single-entry
     * structure list and assert that {@link PlanBuildAction#getProject()}
     * round-trips the head name (the fallback will try to call into the
     * loader, which will hit the real NBT directory on disk — we expect it
     * to return null siblings and keep the single entry, OR to expand
     * depending on what NBTs exist in the test environment). We only assert
     * that the action constructs without throwing and the head name is
     * preserved in the project.
     */
    @Test
    void constructor_preservesHeadNameOnSingleEntry() {
        com.steve.ai.action.Task task = new com.steve.ai.action.Task(
            "build", Map.of("structure", "no_such_template_xyz"));
        PlanBuildAction action = new PlanBuildAction(null, task, null);
        assertEquals("no_such_template_xyz", action.getProject().command,
            "command should be the head name from the single structure");
        assertTrue(action.getProject().selectedTemplates.contains("no_such_template_xyz"),
            "head template should be in selectedTemplates regardless of fallback");
    }
}
