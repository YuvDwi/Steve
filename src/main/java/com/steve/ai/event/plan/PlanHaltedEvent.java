package com.steve.ai.event.plan;

import com.steve.ai.llm.react.BuildPhase;

import java.time.Instant;

/** Player (or timeout) halted the build. Design stays archived in mempalace. */
public final class PlanHaltedEvent implements PlanEvent {
    private final String projectId;
    private final BuildPhase phase;
    private final String reason;
    private final String mempalaceRef;
    private final int blocksPlaced;
    private final int totalBlocks;
    private final Instant timestamp;

    public PlanHaltedEvent(String projectId, BuildPhase phase, String reason,
                           String mempalaceRef, int blocksPlaced, int totalBlocks) {
        this.projectId = projectId;
        this.phase = phase;
        this.reason = reason;
        this.mempalaceRef = mempalaceRef;
        this.blocksPlaced = blocksPlaced;
        this.totalBlocks = totalBlocks;
        this.timestamp = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public BuildPhase getPhase() { return phase; }
    public String getReason() { return reason; }
    public String getMempalaceRef() { return mempalaceRef; }
    public int getBlocksPlaced() { return blocksPlaced; }
    public int getTotalBlocks() { return totalBlocks; }
    public Instant getTimestamp() { return timestamp; }
}
