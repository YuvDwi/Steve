package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.*;
import com.steve.ai.di.ServiceContainer;
import com.steve.ai.di.SimpleServiceContainer;
import com.steve.ai.event.EventBus;
import com.steve.ai.event.SimpleEventBus;
import com.steve.ai.event.plan.PlanChatEvent;
import com.steve.ai.execution.*;
import com.steve.ai.llm.PromptBuilder;
import com.steve.ai.llm.ResponseParser;
import com.steve.ai.llm.TaskPlanner;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.llm.react.ReActAgent;
import com.steve.ai.plugin.ActionRegistry;
import com.steve.ai.plugin.PluginManager;

import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * Executes actions for a Steve entity using the plugin-based action system.
 *
 * <p><b>Architecture:</b></p>
 * <ul>
 *   <li>Uses ActionRegistry for dynamic action creation (Factory + Registry patterns)</li>
 *   <li>Uses InterceptorChain for cross-cutting concerns (logging, metrics, events)</li>
 *   <li>Uses AgentStateMachine for explicit state management</li>
 *   <li>Falls back to legacy switch statement if registry lookup fails</li>
 * </ul>
 *
 * @since 1.1.0
 */
public class ActionExecutor {
    private final SteveEntity steve;
    private TaskPlanner taskPlanner;  // Lazy-initialized to avoid loading dependencies on entity creation

    private BaseAction currentAction;
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;  // Follow player when idle

    // NEW: Plugin architecture components
    private final ActionContext actionContext;
    private final InterceptorChain interceptorChain;
    private final AgentStateMachine stateMachine;
    private final EventBus eventBus;

    // ReAct mode state
    private ReActAgent reactAgent;
    private final Queue<String> pendingCommands = new LinkedList<>();
    private Map<String, Object> reactBaseParams;

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.taskPlanner = null;  // Will be initialized when first needed
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;

        // Initialize plugin architecture components
        this.eventBus = new SimpleEventBus();
        this.stateMachine = new AgentStateMachine(eventBus, steve.getSteveName());
        this.interceptorChain = new InterceptorChain();

        // Setup interceptors
        interceptorChain.addInterceptor(new LoggingInterceptor());
        interceptorChain.addInterceptor(new MetricsInterceptor());
        interceptorChain.addInterceptor(new EventPublishingInterceptor(eventBus, steve.getSteveName()));

        // Build action context
        ServiceContainer container = new SimpleServiceContainer();
        this.actionContext = ActionContext.builder()
            .serviceContainer(container)
            .eventBus(eventBus)
            .stateMachine(stateMachine)
            .interceptorChain(interceptorChain)
            .build();

        SteveMod.LOGGER.debug("ActionExecutor initialized with plugin architecture for Steve '{}'",
            steve.getSteveName());
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            SteveMod.LOGGER.info("Initializing TaskPlanner for Steve '{}'", steve.getSteveName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

    /**
     * Processes a natural language command using ASYNC non-blocking LLM calls.
     *
     * <p>This method returns immediately and does NOT block the game thread.
     * The LLM response is processed in tick() when the CompletableFuture completes.</p>
     *
     * <p><b>Non-blocking flow:</b></p>
     * <ol>
     *   <li>User sends command</li>
     *   <li>This method starts async LLM call, returns immediately</li>
     *   <li>Game continues running normally (no freeze!)</li>
     *   <li>tick() checks if planning is done</li>
     *   <li>When done, tasks are queued and execution begins</li>
     * </ol>
     *
     * @param command The natural language command from the user
     */
    public void processNaturalLanguageCommand(String command) {
        SteveMod.LOGGER.info("Steve '{}' received command: {}", steve.getSteveName(), command);

        pendingCommands.add(command);
        SteveMod.LOGGER.info("Steve '{}' queued command (queue size: {}): {}",
            steve.getSteveName(), pendingCommands.size(), command);

        if (reactAgent == null && currentAction == null) {
            sendToGUI(steve.getSteveName(), "Thinking...");
            drainNextCommand();
        } else {
            sendToGUI(steve.getSteveName(),
                "Got it, will do after current task (queue: " + pendingCommands.size() + ")");
        }
    }

    private void drainNextCommand() {
        String next = pendingCommands.poll();
        if (next == null) {
            return;
        }
        currentGoal = next;
        steve.getMemory().setCurrentGoal(currentGoal);

        reactBaseParams = getTaskPlanner().buildReActParams();
        String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
        reactAgent = new ReActAgent(steve, next,
            SteveConfig.REACT_MAX_STEPS.get(),
            SteveConfig.REACT_OBS_TRUNCATE.get(),
            SteveConfig.REACT_FAIL_TOLERANCE.get());

        SteveMod.LOGGER.info("Steve '{}' starting ReAct agent for: {}", steve.getSteveName(), next);
        reactAgent.startAsync(getTaskPlanner().getAsyncClient(provider), reactBaseParams);
    }

    /**
     * Send a message to the GUI pane (client-side only, no chat spam)
     */
    private void sendToGUI(String steveName, String message) {
        // The chat surface lives in the browser dashboard now. Forward the line
        // through the plan event bus so the SSE channel picks it up.
        String projectId = "";
        try {
            BuildProject p = getActiveBuildProject();
            if (p != null) projectId = p.id;
        } catch (Exception ignored) {}
        SteveMod.getPlanEventBus().publish(new PlanChatEvent(projectId, steveName,
            PlanChatEvent.Sender.STEVE, message));
    }

    public void tick() {
        ticksSinceLastAction++;

        // (Legacy Plan-and-Execute removed; ReAct handles LLM-driven step dispatch below.)

        if (currentAction != null) {
            if (currentAction.isComplete()) {
                ActionResult result = currentAction.getResult();
                SteveMod.LOGGER.info("Steve '{}' - Action completed: {} (Success: {})",
                    steve.getSteveName(), result.getMessage(), result.isSuccess());

                steve.getMemory().addAction(currentAction.getDescription());

                if (!result.isSuccess() && result.requiresReplanning()) {
                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(), "Problem: " + result.getMessage());
                    }
                }

                currentAction = null;

                // Feed the observation back to the ReAct agent (if any)
                if (reactAgent != null) {
                    String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
                    reactAgent.feedObservation(result,
                        getTaskPlanner().getAsyncClient(provider), reactBaseParams);
                }
            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' - Ticking action: {}",
                        steve.getSteveName(), currentAction.getDescription());
                }
                currentAction.tick();
                return;
            }
        }

        // ReAct mode state machine
        if (reactAgent != null) {
            if (reactAgent.failed()) {
                String msg = reactAgent.getFailureMessage();
                SteveMod.LOGGER.error("Steve '{}' ReAct agent failed: {}",
                    steve.getSteveName(), msg);
                sendToGUI(steve.getSteveName(), "AI error: " + msg);
                reactAgent = null;
                if (!pendingCommands.isEmpty()) {
                    drainNextCommand();
                    return;
                }
                currentGoal = null;
                return;
            }

            if (reactAgent.isFinished()) {
                String answer = reactAgent.getFinalAnswer();
                if (answer != null && !answer.isEmpty()) {
                    SteveMod.LOGGER.info("Steve '{}' ReAct finished: {}", steve.getSteveName(), answer);
                    sendToGUI(steve.getSteveName(), answer);
                }
                reactAgent = null;
                if (!pendingCommands.isEmpty()) {
                    drainNextCommand();
                    return;
                }
                currentGoal = null; // allow idle follow when queue is empty
            } else if (reactAgent.isReadyNextStep()) {
                ResponseParser.ParsedResponse step = reactAgent.consumeNextStep();
                if (step != null && !step.getTasks().isEmpty()) {
                    Task task = step.getTasks().get(0);
                    if (!getTaskPlanner().validateTask(task)) {
                        SteveMod.LOGGER.warn("Steve '{}' invalid action from ReAct: {}",
                            steve.getSteveName(), task.getAction());
                        String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
                        reactAgent.feedObservation(
                            "Invalid action: '" + task.getAction() + "'. Allowed: pathfind, mine, place, craft, attack, follow, gather, build, mcp",
                            getTaskPlanner().getAsyncClient(provider), reactBaseParams);
                    } else {
                        executeTask(task);
                        ticksSinceLastAction = 0;
                        return;
                    }
                }
            }
            return; // ReAct is in control
        }

        // ReAct path returns above; below runs only when no ReAct is active.
        // (No legacy Plan-and-Execute task queue — ReAct drives every step.)
        if (currentGoal == null && currentAction == null) {
            // When completely idle (no ReAct, no goal), follow nearest player
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else if (idleFollowAction.isComplete()) {
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else {
                idleFollowAction.tick();
            }
        } else if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
    }

    private void executeTask(Task task) {
        SteveMod.LOGGER.info("Steve '{}' executing task: {} (action type: {})", 
            steve.getSteveName(), task, task.getAction());
        
        currentAction = createAction(task);
        
        if (currentAction == null) {
            SteveMod.LOGGER.error("FAILED to create action for task: {}", task);
            return;
        }

        SteveMod.LOGGER.info("Created action: {} - starting now...", currentAction.getClass().getSimpleName());
        currentAction.start();
        SteveMod.LOGGER.info("Action started! Is complete: {}", currentAction.isComplete());
    }

    /**
     * Creates an action using the plugin registry with legacy fallback.
     *
     * <p>First attempts to create the action via ActionRegistry (plugin system).
     * If the registry doesn't have the action or creation fails, falls back
     * to the legacy switch statement for backward compatibility.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown action type
     */
    private BaseAction createAction(Task task) {
        String actionType = task.getAction();

        // Try registry-based creation first (plugin architecture)
        ActionRegistry registry = ActionRegistry.getInstance();
        if (registry.hasAction(actionType)) {
            BaseAction action = registry.createAction(actionType, steve, task, actionContext);
            if (action != null) {
                SteveMod.LOGGER.debug("Created action '{}' via registry (plugin: {})",
                    actionType, registry.getPluginForAction(actionType));
                return action;
            }
        }

        // Fallback to legacy switch statement for backward compatibility
        SteveMod.LOGGER.debug("Using legacy fallback for action: {}", actionType);
        return createActionLegacy(task);
    }

    /**
     * Legacy action creation using switch statement.
     *
     * <p>Kept for backward compatibility during migration to plugin system.
     * Will be removed in a future version once all actions are registered
     * via plugins.</p>
     *
     * @param task Task containing action type and parameters
     * @return Created action, or null if unknown
     * @deprecated Use ActionRegistry instead
     */
    @Deprecated
    private BaseAction createActionLegacy(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(steve, task);
            case "mine" -> new MineBlockAction(steve, task);
            case "place" -> new PlaceBlockAction(steve, task);
            case "craft" -> new CraftItemAction(steve, task);
            case "attack" -> new CombatAction(steve, task);
            case "follow" -> new FollowPlayerAction(steve, task);
            case "gather" -> new GatherResourceAction(steve, task);
            // Intercept "build" -> PlanBuildAction (four-phase plan-then-build workflow).
            // The plan action loads NBT, archives design to mempalace, and waits for
            // player /steve approve before any blocks are placed.
            case "build" -> new PlanBuildAction(steve, task, this);
            case "mcp" -> new MCPAction(steve, task);
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    public void stopCurrentAction() {
        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        currentGoal = null;
        reactAgent = null;
        reactBaseParams = null;
        pendingCommands.clear();

        // Reset state machine
        stateMachine.reset();
    }

    /**
     * Approve the current build's pending phase. No-op if not in a PlanBuildAction
     * awaiting approval.
     */
    public void approveCurrentBuild() {
        if (currentAction instanceof PlanBuildAction plan) {
            plan.approve();
        } else {
            SteveMod.LOGGER.warn("approveCurrentBuild: no PlanBuildAction in progress for Steve '{}'",
                steve.getSteveName());
        }
    }

    /**
     * Halt the current build (if any). No-op if not a PlanBuildAction.
     */
    public void haltCurrentBuild(String reason) {
        if (currentAction instanceof PlanBuildAction plan) {
            plan.halt(reason);
        } else {
            SteveMod.LOGGER.warn("haltCurrentBuild: no PlanBuildAction in progress for Steve '{}'",
                steve.getSteveName());
        }
    }

    /**
     * Get the active build project, or null if no PlanBuildAction is in flight.
     */
    public com.steve.ai.action.BuildProject getActiveBuildProject() {
        if (currentAction instanceof PlanBuildAction plan) {
            return plan.getProject();
        }
        return null;
    }

    /**
     * Plan a build via LLM. The LLM picks the template and the design phase
     * produces a doc the player must /steve approve before any blocks are
     * placed. Mirrors Claude Code's plan mode semantics.
     *
     * <p>The plan-mode constraint is embedded into the user-facing command
     * string itself — ReActAgent.runStep embeds originalCommand raw into the
     * === USER COMMAND === block of every step, so the LLM sees the rule on
     * every turn without any system-prompt changes.</p>
     *
     * <p>Used by the /steve plan subcommand.</p>
     */
    public void startPlannedBuild(String description) {
        SteveMod.LOGGER.info("Steve '{}' planning: {}", steve.getSteveName(), description);
        int cap = SteveConfig.MAX_TEMPLATES_PER_PLAN.get();
        String augmented = PromptBuilder.buildPlanPrompt(description, cap);
        pendingCommands.add(augmented);
        if (reactAgent == null && currentAction == null) {
            sendToGUI(steve.getSteveName(), "Planning: " + description);
            drainNextCommand();
        } else {
            sendToGUI(steve.getSteveName(),
                "Will plan after current task (queue: " + pendingCommands.size() + ")");
        }
    }

    public boolean isExecuting() {
        return currentAction != null || reactAgent != null;
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Returns the event bus for subscribing to action events.
     *
     * @return EventBus instance
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * Returns the agent state machine.
     *
     * @return AgentStateMachine instance
     */
    public AgentStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Returns the interceptor chain for adding custom interceptors.
     *
     * @return InterceptorChain instance
     */
    public InterceptorChain getInterceptorChain() {
        return interceptorChain;
    }

    /**
     * Returns the action context.
     *
     * @return ActionContext instance
     */
    public ActionContext getActionContext() {
        return actionContext;
    }

    /**
     * Checks if the agent is currently busy with a ReAct agent or an action.
     */
    public boolean isBusy() {
        return reactAgent != null || currentAction != null;
    }
}

