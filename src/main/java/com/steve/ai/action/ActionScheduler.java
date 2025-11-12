package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.BaseAction;
import com.steve.ai.entity.SteveEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Action scheduler for concurrent action execution
 * Manages action priorities, resource locks, and interruption
 *
 * Features:
 * - Priority-based scheduling (CRITICAL > HIGH > NORMAL > LOW > BACKGROUND)
 * - Concurrent action execution when compatible
 * - Resource locking to prevent conflicts
 * - Action interruption for higher priority tasks
 */
public class ActionScheduler {
    private final SteveEntity steve;
    private final ResourceLock resourceLock;

    // Priority queues for scheduled actions
    private final Map<ActionPriority, Queue<BaseAction>> actionQueues;

    // Currently running actions
    private final Set<BaseAction> runningActions;

    // Action history for debugging
    private final List<ActionHistoryEntry> history;
    private static final int MAX_HISTORY = 50;

    public ActionScheduler(SteveEntity steve) {
        this.steve = steve;
        this.resourceLock = new ResourceLock();
        this.actionQueues = new EnumMap<>(ActionPriority.class);
        this.runningActions = ConcurrentHashMap.newKeySet();
        this.history = new ArrayList<>();

        // Initialize priority queues
        for (ActionPriority priority : ActionPriority.values()) {
            actionQueues.put(priority, new LinkedList<>());
        }
    }

    /**
     * Schedule an action with a priority
     * @param action Action to schedule
     * @param priority Priority level
     */
    public void scheduleAction(BaseAction action, ActionPriority priority) {
        if (action == null) {
            return;
        }

        // Check if higher priority action should interrupt running actions
        if (priority == ActionPriority.CRITICAL || priority == ActionPriority.HIGH) {
            checkAndInterruptLowerPriority(priority);
        }

        actionQueues.get(priority).offer(action);

        SteveMod.LOGGER.debug("Scheduled action: {} with priority {}",
            action.getDescription(), priority);
    }

    /**
     * Main tick method - called every game tick
     * Processes action queues and runs compatible actions
     */
    public void tick() {
        // Remove completed actions
        runningActions.removeIf(action -> {
            if (action.isCompleted()) {
                resourceLock.release(action);
                recordHistory(action, "COMPLETED");
                return true;
            }
            return false;
        });

        // Try to start new actions from queues (highest priority first)
        for (ActionPriority priority : ActionPriority.values()) {
            Queue<BaseAction> queue = actionQueues.get(priority);

            while (!queue.isEmpty()) {
                BaseAction action = queue.peek();

                // Check if action can be executed
                if (canExecute(action)) {
                    queue.poll(); // Remove from queue

                    // Acquire resource locks
                    Set<ResourceLock.Resource> requiredResources = getRequiredResources(action);
                    if (resourceLock.tryAcquire(action, requiredResources)) {
                        // Start action
                        action.start();
                        runningActions.add(action);
                        recordHistory(action, "STARTED");

                        SteveMod.LOGGER.debug("Started action: {} (priority: {})",
                            action.getDescription(), priority);
                    } else {
                        // Resources unavailable, put back in queue
                        queue.offer(action);
                        break; // Try next priority level
                    }
                } else {
                    // Action cannot execute, skip for now
                    break;
                }
            }
        }

        // Tick all running actions
        for (BaseAction action : runningActions) {
            try {
                action.tick();
            } catch (Exception e) {
                SteveMod.LOGGER.error("Error ticking action: {}",
                    action.getDescription(), e);
                action.cancel();
            }
        }
    }

    /**
     * Interrupt an action immediately
     * @param action Action to interrupt
     */
    public void interruptAction(BaseAction action) {
        if (runningActions.contains(action)) {
            action.cancel();
            runningActions.remove(action);
            resourceLock.forceRelease(action);
            recordHistory(action, "INTERRUPTED");

            SteveMod.LOGGER.debug("Interrupted action: {}", action.getDescription());
        }

        // Also remove from queues
        for (Queue<BaseAction> queue : actionQueues.values()) {
            queue.remove(action);
        }
    }

    /**
     * Check if an action can be executed
     * @param action Action to check
     * @return True if action can execute
     */
    public boolean canExecute(BaseAction action) {
        // Check resource availability
        Set<ResourceLock.Resource> required = getRequiredResources(action);
        for (ResourceLock.Resource resource : required) {
            if (resourceLock.isLocked(resource)) {
                BaseAction holder = resourceLock.getLockHolder(resource);
                if (holder != action) {
                    return false; // Resource locked by another action
                }
            }
        }

        // Check action compatibility with running actions
        return isCompatibleWithRunning(action);
    }

    /**
     * Check if action is compatible with currently running actions
     */
    private boolean isCompatibleWithRunning(BaseAction newAction) {
        for (BaseAction running : runningActions) {
            if (!areActionsCompatible(newAction, running)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if two actions are compatible (can run simultaneously)
     */
    private boolean areActionsCompatible(BaseAction action1, BaseAction action2) {
        Set<ResourceLock.Resource> resources1 = getRequiredResources(action1);
        Set<ResourceLock.Resource> resources2 = getRequiredResources(action2);

        // Actions are incompatible if they share any resources
        for (ResourceLock.Resource resource : resources1) {
            if (resources2.contains(resource)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get required resources for an action based on its type
     */
    private Set<ResourceLock.Resource> getRequiredResources(BaseAction action) {
        Set<ResourceLock.Resource> resources = new HashSet<>();

        String actionClass = action.getClass().getSimpleName();

        // Determine required resources based on action type
        switch (actionClass) {
            case "MineBlockAction":
                resources.add(ResourceLock.Resource.NAVIGATION);
                resources.add(ResourceLock.Resource.INTERACTION);
                resources.add(ResourceLock.Resource.INVENTORY);
                break;

            case "PlaceBlockAction":
            case "PlaceChestAction":
                resources.add(ResourceLock.Resource.NAVIGATION);
                resources.add(ResourceLock.Resource.INTERACTION);
                resources.add(ResourceLock.Resource.INVENTORY);
                break;

            case "CraftItemAction":
                resources.add(ResourceLock.Resource.NAVIGATION);
                resources.add(ResourceLock.Resource.INTERACTION);
                resources.add(ResourceLock.Resource.INVENTORY);
                resources.add(ResourceLock.Resource.CRAFTING);
                break;

            case "StoreItemsAction":
            case "RetrieveItemsAction":
                resources.add(ResourceLock.Resource.NAVIGATION);
                resources.add(ResourceLock.Resource.INTERACTION);
                resources.add(ResourceLock.Resource.INVENTORY);
                break;

            case "NavigateToAction":
                resources.add(ResourceLock.Resource.NAVIGATION);
                break;

            case "FollowPlayerAction":
                resources.add(ResourceLock.Resource.NAVIGATION);
                break;

            case "IdleAction":
                // Idle requires no resources
                break;

            default:
                // Unknown action type - lock all resources to be safe
                resources.add(ResourceLock.Resource.NAVIGATION);
                resources.add(ResourceLock.Resource.INTERACTION);
                break;
        }

        return resources;
    }

    /**
     * Interrupt lower priority actions if higher priority action is scheduled
     */
    private void checkAndInterruptLowerPriority(ActionPriority newPriority) {
        List<BaseAction> toInterrupt = runningActions.stream()
            .filter(action -> {
                ActionPriority actionPriority = getActionPriority(action);
                return newPriority.isHigherThan(actionPriority);
            })
            .collect(Collectors.toList());

        for (BaseAction action : toInterrupt) {
            interruptAction(action);
        }
    }

    /**
     * Get priority of an action (default to NORMAL if not set)
     */
    private ActionPriority getActionPriority(BaseAction action) {
        // For now, return NORMAL as default
        // In the future, actions can specify their own priority
        return ActionPriority.NORMAL;
    }

    /**
     * Get number of running actions
     */
    public int getRunningActionCount() {
        return runningActions.size();
    }

    /**
     * Get number of queued actions
     */
    public int getQueuedActionCount() {
        return actionQueues.values().stream()
            .mapToInt(Queue::size)
            .sum();
    }

    /**
     * Get all running actions
     */
    public Set<BaseAction> getRunningActions() {
        return new HashSet<>(runningActions);
    }

    /**
     * Clear all actions and queues
     */
    public void clear() {
        for (BaseAction action : runningActions) {
            action.cancel();
        }
        runningActions.clear();

        for (Queue<BaseAction> queue : actionQueues.values()) {
            queue.clear();
        }

        resourceLock.clear();
    }

    /**
     * Record action history for debugging
     */
    private void recordHistory(BaseAction action, String event) {
        history.add(new ActionHistoryEntry(
            System.currentTimeMillis(),
            action.getDescription(),
            event
        ));

        // Limit history size
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    /**
     * Get action history summary
     */
    public String getHistorySummary() {
        StringBuilder sb = new StringBuilder("Action History:\n");
        for (ActionHistoryEntry entry : history) {
            sb.append(String.format("[%d] %s - %s\n",
                entry.timestamp, entry.event, entry.actionDescription));
        }
        return sb.toString();
    }

    /**
     * Get scheduler status for debugging
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Action Scheduler Status ===\n");
        sb.append("Running actions: ").append(runningActions.size()).append("\n");
        for (BaseAction action : runningActions) {
            sb.append("  - ").append(action.getDescription()).append("\n");
        }

        sb.append("Queued actions: ").append(getQueuedActionCount()).append("\n");
        for (ActionPriority priority : ActionPriority.values()) {
            int count = actionQueues.get(priority).size();
            if (count > 0) {
                sb.append("  ").append(priority).append(": ").append(count).append("\n");
            }
        }

        sb.append(resourceLock.getStatusSummary());

        return sb.toString();
    }

    /**
     * Action history entry
     */
    private static class ActionHistoryEntry {
        final long timestamp;
        final String actionDescription;
        final String event;

        ActionHistoryEntry(long timestamp, String actionDescription, String event) {
            this.timestamp = timestamp;
            this.actionDescription = actionDescription;
            this.event = event;
        }
    }
}
