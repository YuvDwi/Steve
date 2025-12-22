package com.steve.ai.action;

import com.steve.ai.SteveMod;
import com.steve.ai.action.actions.*;
import com.steve.ai.ai.ResponseParser;
import com.steve.ai.ai.TaskPlanner;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.client.Minecraft;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

public class ActionExecutor {
    private final SteveEntity steve;
    private TaskPlanner taskPlanner;
    private final Queue<Task> taskQueue;

    private BaseAction currentAction;
    private String currentGoal;
    private int ticksSinceLastAction;
    private BaseAction idleFollowAction;

    public ActionExecutor(SteveEntity steve) {
        this.steve = steve;
        this.taskPlanner = null;
        this.taskQueue = new LinkedList<>();
        this.ticksSinceLastAction = 0;
        this.idleFollowAction = null;
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

        if (currentAction != null) {
            currentAction.cancel();
            currentAction = null;
        }

        if (idleFollowAction != null) {
            idleFollowAction.cancel();
            idleFollowAction = null;
        }

        // Executar fora da thread do jogo
        CompletableFuture.runAsync(() -> {
            try {
                ResponseParser.ParsedResponse response =
                        getTaskPlanner().planTasks(steve, command);

                if (response == null) {
                    sendToGUI(steve.getSteveName(),
                            "I couldn't understand that command.");
                    return;
                }

                // Voltar para a main thread do Minecraft
                Minecraft.getInstance().execute(() -> {
                    currentGoal = response.getPlan();
                    steve.getMemory().setCurrentGoal(currentGoal);

                    taskQueue.clear();
                    taskQueue.addAll(response.getTasks());

                    if (SteveConfig.ENABLE_CHAT_RESPONSES.get()) {
                        sendToGUI(steve.getSteveName(),
                                "Okay! " + currentGoal);
                    }

                    SteveMod.LOGGER.info(
                            "Steve '{}' queued {} tasks",
                            steve.getSteveName(),
                            taskQueue.size()
                    );
                });

            } catch (Exception e) {
                SteveMod.LOGGER.error("Failed to process command", e);
                sendToGUI(steve.getSteveName(),
                        "Sorry, I had trouble processing that!");
            }
        });
    }

    private void sendToGUI(String steveName, String message) {
        if (steve.level().isClientSide) {
            com.steve.ai.client.SteveGUI.addSteveMessage(steveName, message);
        }
    }

    public void tick() {
        ticksSinceLastAction++;

        if (currentAction != null) {
            if (currentAction.isComplete()) {
                ActionResult result = currentAction.getResult();

                SteveMod.LOGGER.info(
                        "Steve '{}' - Action completed: {} (Success: {})",
                        steve.getSteveName(),
                        result.getMessage(),
                        result.isSuccess()
                );

                steve.getMemory().addAction(currentAction.getDescription());
                currentAction = null;
            } else {
                currentAction.tick();
                return;
            }
        }

        if (ticksSinceLastAction >= SteveConfig.ACTION_TICK_DELAY.get()) {
            if (!taskQueue.isEmpty()) {
                executeTask(taskQueue.poll());
                ticksSinceLastAction = 0;
                return;
            }
        }

        if (taskQueue.isEmpty() && currentAction == null && currentGoal == null) {
            if (idleFollowAction == null || idleFollowAction.isComplete()) {
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
        currentAction = createAction(task);

        if (currentAction == null) {
            SteveMod.LOGGER.error("FAILED to create action for task: {}", task);
            return;
        }

        currentAction.start();
    }

    private BaseAction createAction(Task task) {
        return switch (task.getAction()) {
            case "pathfind" -> new PathfindAction(steve, task);
            case "mine" -> new MineBlockAction(steve, task);
            case "place" -> new PlaceBlockAction(steve, task);
            case "craft" -> new CraftItemAction(steve, task);
            case "attack" -> new CombatAction(steve, task);
            case "follow" -> new FollowPlayerAction(steve, task);
            case "gather" -> new GatherResourceAction(steve, task);
            case "build" -> new BuildStructureAction(steve, task);
            default -> null;
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
        taskQueue.clear();
        currentGoal = null;
    }

    public boolean isExecuting() {
        return currentAction != null || !taskQueue.isEmpty();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }
}
