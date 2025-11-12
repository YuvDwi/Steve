package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.*;
import com.steve.ai.ai.*;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.learning.*;

import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.Queue;

public class ActionExecutor {
    private final SteveEntity steve;
    private TaskPlanner taskPlanner;  // Lazy-initialized to avoid loading dependencies on entity creation
    private final Queue<Task> taskQueue;
    private final ActionScheduler scheduler;  // New async action scheduler

    // Learning system components (Phase 2.4)
    private final FailureTracker failureTracker;
    private final ActionKnowledgeBase knowledgeBase;
    private final LearningSystem learningSystem;
    private final AdaptiveRetryStrategy retryStrategy;

    private BaseAction currentAction;  // Legacy: kept for compatibility
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;  // Follow player when idle
    private boolean useScheduler = true;  // Enable new scheduler by default
    private int learningTickCounter = 0; // Periodic learning analysis

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.taskPlanner = null;  // Will be initialized when first needed
        this.taskQueue = new LinkedList<>();
        this.scheduler = new ActionScheduler(steve);
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;

        // Initialize learning system
        this.failureTracker = new FailureTracker(steve.getSteveName());
        this.knowledgeBase = new ActionKnowledgeBase(steve.getSteveName());
        this.learningSystem = new LearningSystem(steve, failureTracker, knowledgeBase);
        this.retryStrategy = new AdaptiveRetryStrategy(failureTracker, learningSystem);
    }
    
    private TaskPlanner getTaskPlanner() {
        if (taskPlanner == null) {
            SteveMod.LOGGER.info("Initializing TaskPlanner for Steve '{}'", steve.getSteveName());
            taskPlanner = new TaskPlanner();
        }
        return taskPlanner;
    }

    public void processNaturalLanguageCommand(String command) {
        SteveMod.LOGGER.info("Steve '{}' processing command: {}", steve.getSteveName(), command);
        
        if (currentAction != null) {            currentAction.cancel();
            currentAction = null;
        }
        
        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
        
        try {
            ResponseParser.ParsedResponse response = getTaskPlanner().planTasks(steve, command);
            
            if (response == null) {
                sendToGUI(steve.getSteveName(), "I couldn't understand that command.");
                return;
            }

            currentGoal = response.getPlan();
            steve.getMemory().setCurrentGoal(currentGoal);
            
            taskQueue.clear();
            taskQueue.addAll(response.getTasks());
            
            // Send response to GUI pane only
            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "Okay! " + currentGoal);
            }
        } catch (NoClassDefFoundError e) {
            SteveMod.LOGGER.error("Failed to initialize AI components", e);
            sendToGUI(steve.getSteveName(), "Sorry, I'm having trouble with my AI systems!");
        }
        
        SteveMod.LOGGER.info("Steve '{}' queued {} tasks", steve.getSteveName(), taskQueue.size());
    }
    
    /**
     * Send a message to the GUI pane (client-side only, no chat spam)
     */
    private void sendToGUI(String steveName, String message) {
        if (steve.level().isClientSide) {
            com.steve.ai.client.SteveGUI.addSteveMessage(steveName, message);
        }
    }

    public void tick() {
        // Periodic learning analysis (every 10 seconds = 200 ticks)
        learningTickCounter++;
        if (learningTickCounter >= 200) {
            performLearningAnalysis();
            learningTickCounter = 0;
        }

        if (useScheduler) {
            tickWithScheduler();
        } else {
            tickLegacy();
        }
    }

    /**
     * Perform periodic learning analysis to identify patterns
     */
    private void performLearningAnalysis() {
        try {
            learningSystem.generateInsights();
            SteveMod.LOGGER.debug("Steve '{}' performed learning analysis - {} insights in knowledge base",
                steve.getSteveName(), knowledgeBase.getInsightCount());
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error during learning analysis for Steve '{}'", steve.getSteveName(), e);
        }
    }

    /**
     * New tick method using ActionScheduler for async execution
     */
    private void tickWithScheduler() {
        ticksSinceLastAction++;

        // Tick the scheduler (handles running actions)
        scheduler.tick();

        // Schedule new tasks from queue when ready
        if (ticksSinceLastAction >= SteveConfig.ACTION_TICK_DELAY.get()) {
            if (!taskQueue.isEmpty()) {
                Task nextTask = taskQueue.poll();
                scheduleTask(nextTask, ActionPriority.NORMAL);
                ticksSinceLastAction = 0;
            }
        }

        // Handle idle behavior
        boolean isIdle = taskQueue.isEmpty() &&
                        scheduler.getRunningActionCount() == 0 &&
                        currentGoal == null;

        if (isIdle) {
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(steve);
                scheduler.scheduleAction(idleFollowAction, ActionPriority.BACKGROUND);
            } else if (idleFollowAction.isComplete()) {
                idleFollowAction = new IdleFollowAction(steve);
                scheduler.scheduleAction(idleFollowAction, ActionPriority.BACKGROUND);
            }
        } else if (idleFollowAction != null) {
            scheduler.interruptAction(idleFollowAction);
            idleFollowAction = null;
        }
    }

    /**
     * Legacy tick method (original implementation)
     */
    private void tickLegacy() {
        ticksSinceLastAction++;

        if (currentAction != null) {
            if (currentAction.isComplete()) {
                ActionResult result = currentAction.getResult();
                SteveMod.LOGGER.info("Steve '{}' - Action completed: {} (Success: {})",
                    steve.getSteveName(), result.getMessage(), result.isSuccess());

                steve.getMemory().addAction(currentAction.getDescription());

                if (result.isSuccess()) {
                    // Record successful action in knowledge base
                    recordSuccess(currentAction.task.getAction());
                } else if (result.requiresReplanning()) {
                    // Action failed, attempt error recovery
                    handleErrorRecovery(currentAction.getDescription(), result.getMessage());
                }

                currentAction = null;
            } else {
                if (ticksSinceLastAction % 100 == 0) {
                    SteveMod.LOGGER.info("Steve '{}' - Ticking action: {}",
                        steve.getSteveName(), currentAction.getDescription());
                }
                currentAction.tick();
                return;
            }
        }

        if (ticksSinceLastAction >= SteveConfig.ACTION_TICK_DELAY.get()) {
            if (!taskQueue.isEmpty()) {
                Task nextTask = taskQueue.poll();
                executeTask(nextTask);
                ticksSinceLastAction = 0;
                return;
            }
        }

        // When completely idle (no tasks, no goal), follow nearest player
        if (taskQueue.isEmpty() && currentAction == null && currentGoal == null) {
            if (idleFollowAction == null) {
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else if (idleFollowAction.isComplete()) {
                // Restart idle following if it stopped
                idleFollowAction = new IdleFollowAction(steve);
                idleFollowAction.start();
            } else {
                // Continue idle following
                idleFollowAction.tick();
            }
        } else if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }
    }

    /**
     * Schedule a task with a priority using the new scheduler
     */
    private void scheduleTask(Task task, ActionPriority priority) {
        SteveMod.LOGGER.info("Steve '{}' scheduling task: {} (priority: {})",
            steve.getSteveName(), task, priority);

        BaseAction action = createAction(task);

        if (action == null) {
            SteveMod.LOGGER.error("FAILED to create action for task: {}", task);
            return;
        }

        action.setPriority(priority);
        scheduler.scheduleAction(action, priority);
    }

    /**
     * Legacy method for executing task immediately
     */
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

    private BaseAction createAction(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(steve, task);
            case "mine" -> new MineBlockAction(steve, task);
            case "place" -> new PlaceBlockAction(steve, task);
            case "craft" -> new CraftItemAction(steve, task);
            case "attack" -> new CombatAction(steve, task);
            case "attack_ranged" -> new RangedCombatAction(steve, task);
            case "retreat" -> new TacticalRetreatAction(steve, task);
            case "follow" -> new FollowPlayerAction(steve, task);
            case "gather" -> new GatherResourceAction(steve, task);
            case "build" -> new BuildStructureAction(steve, task);
            case "store" -> new StoreItemsAction(steve, task);
            case "retrieve" -> new RetrieveItemsAction(steve, task);
            case "place_chest" -> new PlaceChestAction(steve, task);
            case "farm" -> new FarmAction(steve, task);
            case "breed" -> new BreedingAction(steve, task);
            default -> {
                SteveMod.LOGGER.warn("Unknown action type: {}", task.getAction());
                yield null;
            }
        };
    }

    public void stopCurrentAction() {
        if (useScheduler) {
            // Stop all scheduled and running actions
            scheduler.clear();
            if (idleFollowAction != null) {
                idleFollowAction = null;
            }
        } else {
            // Legacy stop
            if (currentAction != null) {
                currentAction.cancel();
                currentAction = null;
            }
            if (idleFollowAction != null) {
                idleFollowAction.cancel();
                idleFollowAction = null;
            }
        }
        taskQueue.clear();
        currentGoal = null;
    }

    public boolean isExecuting() {
        if (useScheduler) {
            return scheduler.getRunningActionCount() > 0 || !taskQueue.isEmpty();
        } else {
            return currentAction != null || !taskQueue.isEmpty();
        }
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    /**
     * Get the action scheduler (for advanced usage)
     */
    public ActionScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Schedule a high-priority action (interrupts normal tasks)
     */
    public void scheduleHighPriorityAction(BaseAction action) {
        action.setPriority(ActionPriority.HIGH);
        scheduler.scheduleAction(action, ActionPriority.HIGH);
    }

    /**
     * Schedule a critical action (interrupts everything)
     */
    public void scheduleCriticalAction(BaseAction action) {
        action.setPriority(ActionPriority.CRITICAL);
        scheduler.scheduleAction(action, ActionPriority.CRITICAL);
    }

    /**
     * Enable or disable the new scheduler (for testing/debugging)
     */
    public void setUseScheduler(boolean useScheduler) {
        this.useScheduler = useScheduler;
    }

    /**
     * Check if scheduler is enabled
     */
    public boolean isUsingScheduler() {
        return useScheduler;
    }

    /**
     * Handle error recovery when an action fails
     * Uses LLM to replan based on error context
     */
    private void handleErrorRecovery(String failedAction, String errorMessage) {
        try {
            SteveMod.LOGGER.info("Steve '{}' attempting error recovery for: {}",
                steve.getSteveName(), failedAction);

            // Record failure in learning system
            recordFailure(failedAction, new HashMap<>(), errorMessage);

            // Notify user
            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "Problem: " + errorMessage + ". Replanning...");
            }

            // Build error recovery prompt with learned knowledge
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            String knowledgeSummary = knowledgeBase.generateKnowledgeSummary();
            String errorPrompt = PromptBuilder.buildErrorRecoveryPrompt(
                steve,
                failedAction,
                errorMessage,
                currentGoal != null ? currentGoal : "unknown goal"
            ) + knowledgeSummary;

            // Get recovery plan from LLM
            String provider = SteveConfig.AI_PROVIDER.get().toLowerCase();
            String response = null;

            switch (provider) {
                case "groq" -> response = new GroqClient().sendRequest(systemPrompt, errorPrompt);
                case "gemini" -> response = new GeminiClient().sendRequest(systemPrompt, errorPrompt);
                case "openai" -> response = new OpenAIClient().sendRequest(systemPrompt, errorPrompt);
                default -> response = new GroqClient().sendRequest(systemPrompt, errorPrompt);
            }

            if (response == null) {
                SteveMod.LOGGER.error("Failed to get error recovery response");
                if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                    sendToGUI(steve.getSteveName(), "I couldn't figure out how to fix this.");
                }
                return;
            }

            // Parse recovery plan
            ResponseParser.ParsedResponse parsedResponse = ResponseParser.parseAIResponse(response);

            if (parsedResponse == null) {
                SteveMod.LOGGER.error("Failed to parse error recovery response");
                return;
            }

            // Replace task queue with recovery tasks
            taskQueue.clear();
            taskQueue.addAll(parsedResponse.getTasks());
            currentGoal = parsedResponse.getPlan();

            SteveMod.LOGGER.info("Steve '{}' created recovery plan: {} ({} tasks)",
                steve.getSteveName(), currentGoal, taskQueue.size());

            if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                sendToGUI(steve.getSteveName(), "New plan: " + currentGoal);
            }

        } catch (Exception e) {
            SteveMod.LOGGER.error("Error during error recovery", e);
        }
    }

    /**
     * Record a failed action in the learning system
     */
    private void recordFailure(String action, Map<String, Object> parameters, String errorMessage) {
        try {
            String context = "Goal: " + (currentGoal != null ? currentGoal : "none") +
                           ", Inventory: " + steve.getInventory().getSlots() + " slots";
            failureTracker.recordFailure(action, parameters, errorMessage, context);
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error recording failure", e);
        }
    }

    /**
     * Record a successful action in the knowledge base
     */
    private void recordSuccess(String action) {
        try {
            knowledgeBase.recordSuccess(action);
        } catch (Exception e) {
            SteveMod.LOGGER.error("Error recording success", e);
        }
    }

    /**
     * Get learning system for external access
     */
    public LearningSystem getLearningSystem() {
        return learningSystem;
    }

    /**
     * Get failure tracker for external access
     */
    public FailureTracker getFailureTracker() {
        return failureTracker;
    }

    /**
     * Get knowledge base for external access
     */
    public ActionKnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    /**
     * Get adaptive retry strategy
     */
    public AdaptiveRetryStrategy getRetryStrategy() {
        return retryStrategy;
    }
}

