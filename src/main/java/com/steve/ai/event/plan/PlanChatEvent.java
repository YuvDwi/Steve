package com.steve.ai.event.plan;

import java.time.Instant;

/** Free-form chat line from a Steve (or a player/system message) for the
 *  external dashboard's chat panel. Distinct from {@link PlanLogEvent}:
 *  PlanLogEvent is a log line with severity; PlanChatEvent is a chat bubble
 *  with a sender and a target Steve.
 *
 *  <p>{@code projectId} is empty when no plan is active (general chat).
 *  {@code sender} names who wrote the line; the browser renders it as a
 *  bubble on the corresponding side.</p> */
public final class PlanChatEvent implements PlanEvent {
    public enum Sender { USER, STEVE, SYSTEM }

    private final String projectId;
    private final String steveName;
    private final Sender sender;
    private final String message;
    private final Instant timestamp;

    public PlanChatEvent(String projectId, String steveName, Sender sender, String message) {
        this.projectId = projectId == null ? "" : projectId;
        this.steveName = steveName == null ? "" : steveName;
        this.sender = sender == null ? Sender.SYSTEM : sender;
        this.message = message == null ? "" : message;
        this.timestamp = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public String getSteveName() { return steveName; }
    public Sender getSender() { return sender; }
    public String getMessage() { return message; }
    public Instant getTimestamp() { return timestamp; }
}
