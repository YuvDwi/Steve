package com.steve.ai.llm.react;

/**
 * Phases of the plan-then-build pipeline.
 *
 * <p>Mirrors the four stages of a real construction project:
 * feasibility -> design -> construction -> acceptance.</p>
 */
public enum BuildPhase {
    FEASIBILITY,
    DESIGN,
    AWAITING_DESIGN_APPROVAL,
    CONSTRUCTION,
    AWAITING_ACCEPTANCE,
    COMPLETED,
    FAILED
}
