package com.steve.ai.action.actions;

import com.steve.ai.action.ActionPriority;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;

public abstract class BaseAction {
    protected final SteveEntity steve;
    protected final Task task;
    protected ActionResult result;
    protected boolean started = false;
    protected boolean cancelled = false;
    protected ActionPriority priority = ActionPriority.NORMAL; // Default priority

    public BaseAction(SteveEntity steve, Task task) {
        this.steve = steve;
        this.task = task;
    }

    public void start() {
        if (started) return;
        started = true;
        onStart();
    }

    public void tick() {
        if (!started || isComplete()) return;
        onTick();
    }

    public void cancel() {
        cancelled = true;
        result = ActionResult.failure("Action cancelled");
        onCancel();
    }

    public boolean isComplete() {
        return result != null || cancelled;
    }

    public boolean isCompleted() {
        return isComplete();
    }

    public ActionResult getResult() {
        return result;
    }

    /**
     * Get action priority
     */
    public ActionPriority getPriority() {
        return priority;
    }

    /**
     * Set action priority
     */
    public void setPriority(ActionPriority priority) {
        this.priority = priority;
    }

    /**
     * Check if this action has been started
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Check if this action has been cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }

    protected abstract void onStart();
    protected abstract void onTick();
    protected abstract void onCancel();

    public abstract String getDescription();
}

