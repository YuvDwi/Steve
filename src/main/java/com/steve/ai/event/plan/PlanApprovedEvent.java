package com.steve.ai.event.plan;

import com.steve.ai.llm.react.BuildPhase;

import java.time.Instant;

/** Player (or auto-rule) approved a pending phase. */
public final class PlanApprovedEvent implements PlanEvent {
    private final String projectId;
    private final BuildPhase phase;
    private final String approvedBy;
    private final Instant timestamp;

    public PlanApprovedEvent(String projectId, BuildPhase phase, String approvedBy) {
        this.projectId = projectId;
        this.phase = phase;
        this.approvedBy = approvedBy;
        this.timestamp = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public BuildPhase getPhase() { return phase; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getTimestamp() { return timestamp; }
}
