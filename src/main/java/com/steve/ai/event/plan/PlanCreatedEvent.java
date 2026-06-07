package com.steve.ai.event.plan;

import com.steve.ai.llm.react.BuildPhase;

import java.time.Instant;
import java.util.List;

/** A new BuildProject has been created (the LLM just selected templates). */
public final class PlanCreatedEvent implements PlanEvent {
    private final String projectId;
    private final String steveName;
    private final String command;
    private final List<String> templates;
    private final BuildPhase phase;
    private final Instant timestamp;

    public PlanCreatedEvent(String projectId, String steveName, String command,
                            List<String> templates, BuildPhase phase) {
        this.projectId = projectId;
        this.steveName = steveName;
        this.command = command;
        this.templates = templates == null ? List.of() : List.copyOf(templates);
        this.phase = phase;
        this.timestamp = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public String getSteveName() { return steveName; }
    public String getCommand() { return command; }
    public List<String> getTemplates() { return templates; }
    public BuildPhase getPhase() { return phase; }
    public Instant getTimestamp() { return timestamp; }
}
