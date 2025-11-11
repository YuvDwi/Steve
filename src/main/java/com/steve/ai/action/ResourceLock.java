package com.steve.ai.action;

import com.steve.ai.action.actions.BaseAction;

import java.util.*;

/**
 * Resource locking system to prevent conflicting actions
 * Ensures that only one action can access a resource at a time
 *
 * Resources include:
 * - INVENTORY: Steve's inventory
 * - NAVIGATION: Pathfinding and movement
 * - INTERACTION: Block breaking, placement, chest access
 * - COMBAT: Attack and defense actions
 */
public class ResourceLock {
    /**
     * Resource types that can be locked
     */
    public enum Resource {
        INVENTORY,      // Inventory operations (add/remove items)
        NAVIGATION,     // Movement and pathfinding
        INTERACTION,    // Block interaction (break, place, open chest)
        COMBAT,         // Combat actions (attack, defend)
        CRAFTING        // Crafting operations
    }

    private final Map<Resource, BaseAction> locks;
    private final Map<BaseAction, Set<Resource>> actionResources;

    public ResourceLock() {
        this.locks = new HashMap<>();
        this.actionResources = new HashMap<>();
    }

    /**
     * Try to acquire locks for an action
     * @param action The action requesting locks
     * @param resources Resources to lock
     * @return True if all locks acquired successfully
     */
    public boolean tryAcquire(BaseAction action, Set<Resource> resources) {
        // Check if all resources are available
        for (Resource resource : resources) {
            if (locks.containsKey(resource) && locks.get(resource) != action) {
                return false; // Resource already locked by another action
            }
        }

        // Acquire all locks
        for (Resource resource : resources) {
            locks.put(resource, action);
        }

        // Track which resources this action holds
        actionResources.put(action, new HashSet<>(resources));

        return true;
    }

    /**
     * Release all locks held by an action
     * @param action The action releasing locks
     */
    public void release(BaseAction action) {
        Set<Resource> resources = actionResources.remove(action);
        if (resources != null) {
            for (Resource resource : resources) {
                if (locks.get(resource) == action) {
                    locks.remove(resource);
                }
            }
        }
    }

    /**
     * Force release locks for an action (used during interruption)
     * @param action The action to force release
     */
    public void forceRelease(BaseAction action) {
        release(action);
    }

    /**
     * Check if a resource is locked
     * @param resource The resource to check
     * @return True if locked
     */
    public boolean isLocked(Resource resource) {
        return locks.containsKey(resource);
    }

    /**
     * Check if an action holds any locks
     * @param action The action to check
     * @return True if action holds locks
     */
    public boolean holdsLocks(BaseAction action) {
        return actionResources.containsKey(action);
    }

    /**
     * Get the action that holds a resource lock
     * @param resource The resource to query
     * @return The action holding the lock, or null if unlocked
     */
    public BaseAction getLockHolder(Resource resource) {
        return locks.get(resource);
    }

    /**
     * Get all resources locked by an action
     * @param action The action to query
     * @return Set of resources, or empty set if none
     */
    public Set<Resource> getLockedResources(BaseAction action) {
        return actionResources.getOrDefault(action, Collections.emptySet());
    }

    /**
     * Get all currently locked resources
     * @return Set of locked resources
     */
    public Set<Resource> getAllLockedResources() {
        return new HashSet<>(locks.keySet());
    }

    /**
     * Clear all locks (for debugging/reset)
     */
    public void clear() {
        locks.clear();
        actionResources.clear();
    }

    /**
     * Get lock status summary for debugging
     */
    public String getStatusSummary() {
        if (locks.isEmpty()) {
            return "No resources locked";
        }

        StringBuilder sb = new StringBuilder("Locked resources:\n");
        for (Map.Entry<Resource, BaseAction> entry : locks.entrySet()) {
            sb.append("  ").append(entry.getKey())
              .append(" -> ").append(entry.getValue().getDescription())
              .append("\n");
        }
        return sb.toString();
    }
}
