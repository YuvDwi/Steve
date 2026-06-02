package com.steve.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResponseParser {

    /**
     * Parse a single ReAct step response. Format:
     * <pre>
     * {"thought": "...", "action": "build", "parameters": {...}, "is_final": false}
     * </pre>
     * Or final answer:
     * <pre>
     * {"thought": "...", "is_final": true, "final_answer": "..."}
     * </pre>
     *
     * <p>If <code>is_final=true</code> and <code>final_answer</code> present, the
     * returned <code>ParsedResponse</code> has empty <code>tasks</code> and
     * <code>isFinal=true</code>. If a single action is present, <code>tasks</code>
     * contains exactly one element. Returns <code>null</code> if the input cannot
     * be parsed as JSON.</p>
     */
    public static ParsedResponse parseReActStep(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        try {
            String jsonString = extractJSON(response);
            JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();

            String thought = json.has("thought") ? json.get("thought").getAsString() : "";
            boolean isFinal = json.has("is_final") && json.get("is_final").getAsBoolean();
            String finalAnswer = json.has("final_answer") ? json.get("final_answer").getAsString() : null;

            if (isFinal) {
                String answer = finalAnswer != null ? finalAnswer : thought;
                SteveMod.LOGGER.info("[ReAct] Parsed final answer: {}", answer);
                return new ParsedResponse(thought, thought, java.util.Collections.emptyList(), true, answer);
            }

            if (!json.has("action") || json.get("action").isJsonNull()) {
                SteveMod.LOGGER.warn("[ReAct] Response missing 'action' field: {}", response);
                return null;
            }

            String action = json.get("action").getAsString();
            Map<String, Object> parameters = new HashMap<>();
            if (json.has("parameters") && json.get("parameters").isJsonObject()) {
                JsonObject paramsObj = json.getAsJsonObject("parameters");
                for (String key : paramsObj.keySet()) {
                    parameters.put(key, extractValue(paramsObj.get(key)));
                }
            }

            Task task = new Task(action, parameters);
            SteveMod.LOGGER.info("[ReAct] Parsed step: thought='{}' action={} parameters={}",
                thought, action, parameters);
            return new ParsedResponse(thought, thought, List.of(task), false, null);
        } catch (Exception e) {
            SteveMod.LOGGER.error("[ReAct] Failed to parse step: {}", response, e);
            return null;
        }
    }

    private static Object extractValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonPrimitive()) {
            if (value.getAsJsonPrimitive().isNumber()) {
                return value.getAsNumber();
            } else if (value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            } else {
                return value.getAsString();
            }
        }
        if (value.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                list.add(extractValue(element));
            }
            return list;
        }
        return value.toString();
    }

    public static ParsedResponse parseAIResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            String jsonString = extractJSON(response);
            
            JsonObject json = JsonParser.parseString(jsonString).getAsJsonObject();
            
            String reasoning = json.has("reasoning") ? json.get("reasoning").getAsString() : "";
            String plan = json.has("plan") ? json.get("plan").getAsString() : "";
            List<Task> tasks = new ArrayList<>();
            
            if (json.has("tasks") && json.get("tasks").isJsonArray()) {
                JsonArray tasksArray = json.getAsJsonArray("tasks");
                
                for (JsonElement taskElement : tasksArray) {
                    if (taskElement.isJsonObject()) {
                        JsonObject taskObj = taskElement.getAsJsonObject();
                        Task task = parseTask(taskObj);
                        if (task != null) {
                            tasks.add(task);
                        }
                    }
                }
            }
            
            if (!reasoning.isEmpty()) {            }

            SteveMod.LOGGER.info("[Parser] Plan: {} ({} tasks)", plan, tasks.size());
            return new ParsedResponse(reasoning, plan, tasks);

        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to parse AI response: {}", response, e);
            return null;
        }
    }

    private static String extractJSON(String response) {
        String cleaned = response.trim();
        
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        
        cleaned = cleaned.trim();
        
        // Fix common JSON formatting issues
        cleaned = cleaned.replaceAll("\\n\\s*", " ");
        
        // Fix missing commas between array/object elements (common AI mistake)
        cleaned = cleaned.replaceAll("}\\s+\\{", "},{");
        cleaned = cleaned.replaceAll("}\\s+\\[", "},[");
        cleaned = cleaned.replaceAll("]\\s+\\{", "],{");
        cleaned = cleaned.replaceAll("]\\s+\\[", "],[");
        
        return cleaned;
    }

    private static Task parseTask(JsonObject taskObj) {
        if (!taskObj.has("action")) {
            return null;
        }

        String action = taskObj.get("action").getAsString();
        Map<String, Object> parameters = new HashMap<>();

        if (taskObj.has("parameters") && taskObj.get("parameters").isJsonObject()) {
            JsonObject paramsObj = taskObj.getAsJsonObject("parameters");
            for (String key : paramsObj.keySet()) {
                parameters.put(key, extractValue(paramsObj.get(key)));
            }
        }

        return new Task(action, parameters);
    }

    public static class ParsedResponse {
        private final String reasoning;
        private final String plan;
        private final List<Task> tasks;
        private final boolean isFinal;
        private final String finalAnswer;

        public ParsedResponse(String reasoning, String plan, List<Task> tasks) {
            this(reasoning, plan, tasks, false, null);
        }

        public ParsedResponse(String reasoning, String plan, List<Task> tasks, boolean isFinal, String finalAnswer) {
            this.reasoning = reasoning;
            this.plan = plan;
            this.tasks = tasks;
            this.isFinal = isFinal;
            this.finalAnswer = finalAnswer;
        }

        public String getReasoning() {
            return reasoning;
        }

        public String getPlan() {
            return plan;
        }

        public List<Task> getTasks() {
            return tasks;
        }

        public boolean isFinal() {
            return isFinal;
        }

        public String getFinalAnswer() {
            return finalAnswer;
        }
    }
}

