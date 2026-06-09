package com.steve.ai.plugin;

import com.steve.ai.action.actions.BaseAction;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.execution.ActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for action factories using the Registry Pattern.
 *
 * <p>This class provides dynamic action lookup and creation, replacing the
 * hardcoded switch statement in ActionExecutor with a flexible, extensible
 * approach that follows the Open/Closed Principle.</p>
 *
 * <p><b>Thread Safety:</b> All operations are thread-safe using ConcurrentHashMap.</p>
 *
 * <p><b>Example Usage:</b></p>
 * <pre>
 * ActionRegistry registry = ActionRegistry.getInstance();
 *
 * // Register an action factory
 * registry.register("mine", (steve, task, ctx) -&gt; new MineBlockAction(steve, task));
 *
 * // Create an action
 * BaseAction action = registry.createAction("mine", steve, task, context);
 *
 * // Check if action exists
 * if (registry.hasAction("mine")) {
 *     // ...
 * }
 * </pre>
 *
 * <p><b>Design Patterns:</b></p>
 * <ul>
 *   <li><b>Registry Pattern</b>: Central lookup for factories</li>
 *   <li><b>Singleton Pattern</b>: Single registry instance</li>
 *   <li><b>Factory Pattern</b>: Factories create action instances</li>
 * </ul>
 *
 * @since 1.1.0
 * @see ActionPlugin
 * @see ActionFactory
 */
public class ActionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionRegistry.class);

    private static final ActionRegistry INSTANCE = new ActionRegistry();

    /**
     * Map of action name to factory, with metadata for conflict resolution.
     * Thread-safe via ConcurrentHashMap.
     */
    private final ConcurrentHashMap<String, FactoryEntry> factories;

    /**
     * Tracks which plugin registered which action for debugging.
     */
    private final ConcurrentHashMap<String, String> actionToPlugin;

    private ActionRegistry() {
        this.factories = new ConcurrentHashMap<>();
        this.actionToPlugin = new ConcurrentHashMap<>();
    }

    /**
     * Returns the singleton registry instance.
     *
     * @return ActionRegistry singleton
     */
    public static ActionRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers an action factory with default priority.
     *
     * @param actionName Action name (e.g., "mine", "build")
     * @param factory    Factory to create action instances
     * @throws IllegalArgumentException if actionName or factory is null
     */
    public void register(String actionName, ActionFactory factory) {
        register(actionName, null, factory, 0, "unknown");
    }

    /**
     * Registers an action factory with priority for conflict resolution.
     *
     * <p>If an action with the same name is already registered, the factory
     * with higher priority wins. If priorities are equal, the newer registration
     * wins (last-write-wins).</p>
     *
     * @param actionName Action name (e.g., "mine", "build")
     * @param factory    Factory to create action instances
     * @param priority   Priority for conflict resolution (higher wins)
     * @param pluginId   ID of the plugin registering this action
     * @throws IllegalArgumentException if actionName or factory is null
     */
    public void register(String actionName, ActionFactory factory, int priority, String pluginId) {
        register(actionName, null, factory, priority, pluginId);
    }

    public void register(String actionName, ActionSchema schema, ActionFactory factory, int priority, String pluginId) {
        if (actionName == null || actionName.isBlank()) {
            throw new IllegalArgumentException("Action name cannot be null or blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("Factory cannot be null");
        }

        String normalizedName = actionName.toLowerCase().trim();

        factories.compute(normalizedName, (key, existing) -> {
            if (existing == null) {
                LOGGER.info("Registered action '{}' from plugin '{}' (priority: {})",
                    normalizedName, pluginId, priority);
                actionToPlugin.put(normalizedName, pluginId);
                return new FactoryEntry(factory, priority, pluginId, schema);
            }

            if (priority > existing.priority) {
                LOGGER.info("Action '{}' overridden by plugin '{}' (priority {} > {})",
                    normalizedName, pluginId, priority, existing.priority);
                actionToPlugin.put(normalizedName, pluginId);
                return new FactoryEntry(factory, priority, pluginId, schema);
            } else if (priority == existing.priority) {
                LOGGER.warn("Action '{}' already registered by '{}' with same priority, keeping existing",
                    normalizedName, existing.pluginId);
                return existing;
            } else {
                LOGGER.debug("Action '{}' registration from '{}' ignored (priority {} < {})",
                    normalizedName, pluginId, priority, existing.priority);
                return existing;
            }
        });
    }

    /**
     * Unregisters an action.
     *
     * @param actionName Action name to unregister
     * @return true if action was registered and removed, false otherwise
     */
    public boolean unregister(String actionName) {
        if (actionName == null) return false;

        String normalizedName = actionName.toLowerCase().trim();
        FactoryEntry removed = factories.remove(normalizedName);
        actionToPlugin.remove(normalizedName);

        if (removed != null) {
            LOGGER.info("Unregistered action '{}'", normalizedName);
            return true;
        }
        return false;
    }

    /**
     * Creates an action instance using the registered factory.
     *
     * @param actionName Action name
     * @param steve      Steve entity
     * @param task       Task with parameters
     * @param context    Action context with dependencies
     * @return Created action, or null if action not found
     */
    public BaseAction createAction(String actionName, SteveEntity steve, Task task, ActionContext context) {
        if (actionName == null) {
            LOGGER.warn("Cannot create action: actionName is null");
            return null;
        }

        String normalizedName = actionName.toLowerCase().trim();
        FactoryEntry entry = factories.get(normalizedName);

        if (entry == null) {
            LOGGER.warn("No factory registered for action '{}'", normalizedName);
            return null;
        }

        try {
            BaseAction action = entry.factory.create(steve, task, context);
            LOGGER.debug("Created action '{}' from plugin '{}'", normalizedName, entry.pluginId);
            return action;
        } catch (Exception e) {
            LOGGER.error("Failed to create action '{}': {}", normalizedName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Checks if an action is registered.
     *
     * @param actionName Action name
     * @return true if registered
     */
    public boolean hasAction(String actionName) {
        if (actionName == null) return false;
        return factories.containsKey(actionName.toLowerCase().trim());
    }

    /**
     * Returns all registered action names.
     *
     * @return Unmodifiable set of action names
     */
    public Set<String> getRegisteredActions() {
        return Collections.unmodifiableSet(factories.keySet());
    }

    /**
     * Returns the number of registered actions.
     *
     * @return Action count
     */
    public int getActionCount() {
        return factories.size();
    }

    /**
     * Returns which plugin registered a specific action.
     *
     * @param actionName Action name
     * @return Plugin ID, or null if action not found
     */
    public String getPluginForAction(String actionName) {
        if (actionName == null) return null;
        return actionToPlugin.get(actionName.toLowerCase().trim());
    }

    /**
     * Clears all registered actions.
     *
     * <p>Used during shutdown or testing.</p>
     */
    public void clear() {
        factories.clear();
        actionToPlugin.clear();
        LOGGER.info("ActionRegistry cleared");
    }

    /**
     * Returns a formatted string listing all registered actions.
     *
     * <p>Useful for dynamic prompt building to tell LLM what actions are available.</p>
     *
     * @return Comma-separated list of action names
     */
    public String getActionsAsList() {
        return String.join(", ", getRegisteredActions());
    }

    public ActionSchema getSchema(String actionName) {
        if (actionName == null) return null;
        FactoryEntry entry = factories.get(actionName.toLowerCase().trim());
        return entry != null ? entry.schema : null;
    }

    public java.util.List<ActionSchema> getAllSchemas() {
        java.util.List<ActionSchema> result = new java.util.ArrayList<>();
        for (FactoryEntry e : factories.values()) {
            if (e.schema != null) result.add(e.schema);
        }
        return result;
    }

    public String generatePromptSection() {
        return ActionSchema.generatePromptSection(getAllSchemas());
    }

    /**
     * Internal entry storing factory with metadata.
     */
    private static class FactoryEntry {
        final ActionFactory factory;
        final int priority;
        final String pluginId;
        final ActionSchema schema;

        FactoryEntry(ActionFactory factory, int priority, String pluginId, ActionSchema schema) {
            this.factory = factory;
            this.priority = priority;
            this.pluginId = pluginId;
            this.schema = schema;
        }
    }
}
