package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import com.steve.ai.mcp.MCPToolRegistry;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class SteveMemory {
    private final SteveEntity steve;
    private String currentGoal;
    private final Queue<String> taskQueue;
    private final LinkedList<String> recentActions;
    private static final int MAX_RECENT_ACTIONS = 20;

    public SteveMemory(SteveEntity steve) {
        this.steve = steve;
        this.currentGoal = "";
        this.taskQueue = new LinkedList<>();
        this.recentActions = new LinkedList<>();
    }

    public String getCurrentGoal() {
        return currentGoal;
    }

    public void setCurrentGoal(String goal) {
        this.currentGoal = goal;
    }

    public void addAction(String action) {
        recentActions.addLast(action);
        if (recentActions.size() > MAX_RECENT_ACTIONS) {
            recentActions.removeFirst();
        }
    }

    /**
     * Query long-term memory from mempalace.
     */
    public String queryLongTermMemory(String query) {
        try {
            MCPToolRegistry registry = MCPToolRegistry.getInstance();
            if (registry == null) return "";

            return registry.callTool("mempalace:mempalace_query", Map.of(
                "wing", "steve_memory",
                "room", steve.getSteveName(),
                "query", query
            ));
        } catch (Exception e) {
            return "";
        }
    }

    public List<String> getRecentActions(int count) {
        int size = Math.min(count, recentActions.size());
        List<String> result = new ArrayList<>();

        int startIndex = Math.max(0, recentActions.size() - count);
        for (int i = startIndex; i < recentActions.size(); i++) {
            result.add(recentActions.get(i));
        }

        return result;
    }

    public void clearTaskQueue() {
        taskQueue.clear();
        currentGoal = "";
    }
}

