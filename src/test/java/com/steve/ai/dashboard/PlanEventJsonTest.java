package com.steve.ai.dashboard;

import com.google.gson.JsonObject;
import com.steve.ai.event.plan.PlanApprovedEvent;
import com.steve.ai.event.plan.PlanCreatedEvent;
import com.steve.ai.event.plan.PlanDesignReadyEvent;
import com.steve.ai.event.plan.PlanHaltedEvent;
import com.steve.ai.event.plan.PlanLogEvent;
import com.steve.ai.event.plan.PlanPhaseChangedEvent;
import com.steve.ai.llm.react.BuildPhase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies that every concrete {@code PlanEvent} serializes to a stable
 *  JSON shape that the front-end {@code plan_dashboard.js} expects. */
public class PlanEventJsonTest {

    @Test
    void createdEventRoundTripsKeyFields() {
        PlanCreatedEvent ev = new PlanCreatedEvent(
            "p1", "Steve1", "build a hut", List.of("hut_a", "shed_b"), BuildPhase.FEASIBILITY);
        JsonObject o = PlanEventJson.toJson(ev);
        assertEquals("plan.created", o.get("type").getAsString());
        assertEquals("p1", o.get("projectId").getAsString());
        assertEquals("Steve1", o.get("steveName").getAsString());
        assertEquals("build a hut", o.get("command").getAsString());
        assertEquals("FEASIBILITY", o.get("phase").getAsString());
        assertEquals(2, o.getAsJsonArray("templates").size());
        assertTrue(o.has("timestamp"));
    }

    @Test
    void designReadyEventCarriesTextAndMaterials() {
        PlanDesignReadyEvent ev = new PlanDesignReadyEvent(
            "p1", "DESIGN BODY",
            List.of(
                new PlanDesignReadyEvent.MaterialEntry("oak_planks", 10, 50),
                new PlanDesignReadyEvent.MaterialEntry("glass", 5, 25)
            ),
            20,
            List.of(
                new PlanDesignReadyEvent.BlockEntry(0, 0, 0, "minecraft:oak_planks"),
                new PlanDesignReadyEvent.BlockEntry(1, 0, 0, "minecraft:glass")
            ));
        JsonObject o = PlanEventJson.toJson(ev);
        assertEquals("plan.design_ready", o.get("type").getAsString());
        assertEquals("p1", o.get("projectId").getAsString());
        assertEquals("DESIGN BODY", o.get("design").getAsString());
        assertEquals(20, o.get("totalBlocks").getAsInt());
        assertEquals(2, o.getAsJsonArray("materials").size());
        assertEquals("oak_planks", o.getAsJsonArray("materials").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(2, o.getAsJsonArray("blocks").size());
        assertEquals("minecraft:oak_planks",
            o.getAsJsonArray("blocks").get(0).getAsJsonObject().get("blockId").getAsString());
    }

    @Test
    void phaseChangedWithDeadlineKeepsMillis() {
        PlanPhaseChangedEvent ev = new PlanPhaseChangedEvent(
            "p1", BuildPhase.DESIGN, BuildPhase.AWAITING_DESIGN_APPROVAL, 1717800000000L);
        JsonObject o = PlanEventJson.toJson(ev);
        assertEquals("plan.phase_changed", o.get("type").getAsString());
        assertEquals("DESIGN", o.get("prev").getAsString());
        assertEquals("AWAITING_DESIGN_APPROVAL", o.get("next").getAsString());
        assertEquals(1717800000000L, o.get("deadlineMs").getAsLong());
    }

    @Test
    void phaseChangedWithoutDeadlineOmitsField() {
        PlanPhaseChangedEvent ev = new PlanPhaseChangedEvent(
            "p1", BuildPhase.DESIGN, BuildPhase.AWAITING_DESIGN_APPROVAL, null);
        JsonObject o = PlanEventJson.toJson(ev);
        assertFalse(o.has("deadlineMs"));
    }

    @Test
    void approvedAndHaltedCarryContext() {
        PlanApprovedEvent app = new PlanApprovedEvent("p1", BuildPhase.AWAITING_DESIGN_APPROVAL, "player");
        JsonObject appO = PlanEventJson.toJson(app);
        assertEquals("plan.approved", appO.get("type").getAsString());
        assertEquals("player", appO.get("approvedBy").getAsString());

        PlanHaltedEvent halt = new PlanHaltedEvent(
            "p1", BuildPhase.AWAITING_DESIGN_APPROVAL, "timeout",
            "wing=build_designs/room=p1_design", 0, 200);
        JsonObject haltO = PlanEventJson.toJson(halt);
        assertEquals("plan.halted", haltO.get("type").getAsString());
        assertEquals("timeout", haltO.get("reason").getAsString());
        assertEquals(200, haltO.get("totalBlocks").getAsInt());
        assertEquals("wing=build_designs/room=p1_design", haltO.get("mempalaceRef").getAsString());
    }

    @Test
    void logEventKeepsSeverity() {
        PlanLogEvent ev = new PlanLogEvent("p1", PlanLogEvent.Severity.WARN, "watch out");
        JsonObject o = PlanEventJson.toJson(ev);
        assertEquals("plan.log", o.get("type").getAsString());
        assertEquals("WARN", o.get("severity").getAsString());
        assertEquals("watch out", o.get("message").getAsString());
    }

    @Test
    void idleSnapshotMarksIdle() {
        JsonObject o = PlanEventJson.idleSnapshot();
        assertEquals("snapshot", o.get("type").getAsString());
        assertTrue(o.get("idle").getAsBoolean());
        assertEquals("", o.get("projectId").getAsString());
    }

    @Test
    void toSseDataEndsWithNewlines() {
        String sse = PlanEventJson.toSseData(
            new PlanLogEvent("p1", PlanLogEvent.Severity.INFO, "hi"));
        assertTrue(sse.startsWith("data: {"));
        assertTrue(sse.endsWith("\n\n"), "SSE chunks must end with two newlines");
    }
}
