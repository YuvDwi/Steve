package com.steve.ai.action;

public class ActionResult {
    public enum Status { SUCCESS, FAILURE, PHASE_TRANSITION, AWAITING_APPROVAL }

    private final boolean success;
    private final String message;
    private final boolean requiresReplanning;
    private final Status status;

    public ActionResult(boolean success, String message) {
        this(success, message, !success, success ? Status.SUCCESS : Status.FAILURE);
    }

    public ActionResult(boolean success, String message, boolean requiresReplanning) {
        this(success, message, requiresReplanning, success ? Status.SUCCESS : Status.FAILURE);
    }

    public ActionResult(boolean success, String message, boolean requiresReplanning, Status status) {
        this.success = success;
        this.message = message;
        this.requiresReplanning = requiresReplanning;
        this.status = status;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public boolean requiresReplanning() {
        return requiresReplanning;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isAwaitingApproval() {
        return status == Status.AWAITING_APPROVAL;
    }

    public static ActionResult success(String message) {
        return new ActionResult(true, message, false, Status.SUCCESS);
    }

    public static ActionResult failure(String message) {
        return new ActionResult(false, message, true, Status.FAILURE);
    }

    public static ActionResult failure(String message, boolean requiresReplanning) {
        return new ActionResult(false, message, requiresReplanning, Status.FAILURE);
    }

    public static ActionResult phaseTransition(String message) {
        return new ActionResult(false, message, false, Status.PHASE_TRANSITION);
    }

    public static ActionResult awaitingApproval(String message) {
        return new ActionResult(false, message, false, Status.AWAITING_APPROVAL);
    }

    @Override
    public String toString() {
        return "ActionResult{status=" + status + ", success=" + success + ", message='" + message + "', requiresReplanning=" + requiresReplanning + "}";
    }
}

