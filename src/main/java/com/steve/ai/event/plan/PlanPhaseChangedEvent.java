package com.steve.ai.event.plan;

import com.steve.ai.llm.react.BuildPhase;

import java.time.Instant;

/** A BuildProject transitioned from {@code prev} to {@code next}. */
public final class PlanPhaseChangedEvent implements PlanEvent {
    private final String projectId;
    private final BuildPhase prev;
    private final BuildPhase next;
    private final Long deadlineMs;
    private final Instant timestamp;

    public PlanPhaseChangedEvent(String projectId, BuildPhase prev, BuildPhase next, Long deadlineMs) {
        this.projectId = projectId;
        this.prev = prev;
        this.next = next;
        this.deadlineMs = deadlineMs;
        this.timestamp = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public BuildPhase getPrev() { return prev; }
    public BuildPhase getNext() { return next; }
    public Long getDeadlineMs() { return deadlineMs; }
    public Instant getTimestamp() { return timestamp; }
}
