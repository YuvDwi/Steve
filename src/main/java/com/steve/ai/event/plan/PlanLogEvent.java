package com.steve.ai.event.plan;

import java.time.Instant;

/** Free-form log line mirrored from SteveMod.LOGGER to the dashboard timeline. */
public final class PlanLogEvent implements PlanEvent {
    public enum Severity { INFO, WARN, ERROR }

    private final String projectId;
    private final Severity severity;
    private final String message;
    private final Instant timestamp;

    public PlanLogEvent(String projectId, Severity severity, String message) {
        this.projectId = projectId;
        this.severity = severity;
        this.message = message;
        this.timestamp = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public Severity getSeverity() { return severity; }
    public String getMessage() { return message; }
    public Instant getTimestamp() { return timestamp; }
}
