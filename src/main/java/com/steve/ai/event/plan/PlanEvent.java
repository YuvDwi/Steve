package com.steve.ai.event.plan;

/**
 * Marker interface for events that flow from {@code PlanBuildAction} to the
 * external HTML dashboard.
 *
 * <p>Implementations are immutable POJOs published on
 * {@code SteveMod.getPlanEventBus()}. {@code SimpleEventBus} dispatches by
 * exact runtime class, so {@code PlanDashboardServer} subscribes to each
 * concrete subtype individually — see
 * {@code SteveMod.subscribeToAllPlanEvents}.</p>
 */
public interface PlanEvent {
}
