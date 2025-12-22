package com.steve.ai.ai;

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
                        Task task = parseTask(taskElement.getAsJsonObject());
                        if (task != null) {
                            tasks.add(task);
                        }
                    }
                }
            }

            return new ParsedResponse(reasoning, plan, tasks);

        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to parse AI response: {}", response, e);
            return null;
        }
    }

    /**
     * Extrai JSON de respostas da IA de forma segura.
     * Nunca tenta "consertar" estrutura JSON (chaves, colchetes, vírgulas),
     * apenas remove markdown e normaliza whitespace se necessário.
     */
    private static String extractJSON(String response) {
        String cleaned = response.trim();

        // Remover markdown code blocks
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        cleaned = cleaned.trim();

        // Tentar parsear como está primeiro (JSON válido não deve ser alterado)
        try {
            JsonParser.parseString(cleaned);
            return cleaned;
        } catch (Exception e) {
            // Se falhou, aplicar correções mínimas e seguras
            SteveMod.LOGGER.warn("JSON parsing failed, attempting safe fixes: {}", e.getMessage());

            // Apenas normalizar whitespace
            cleaned = cleaned.replaceAll("\\n\\s*", " ");

            return cleaned;
        }
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
                JsonElement value = paramsObj.get(key);

                if (value.isJsonPrimitive()) {
                    if (value.getAsJsonPrimitive().isNumber()) {
                        parameters.put(key, value.getAsNumber());
                    } else if (value.getAsJsonPrimitive().isBoolean()) {
                        parameters.put(key, value.getAsBoolean());
                    } else {
                        parameters.put(key, value.getAsString());
                    }
                } else if (value.isJsonArray()) {
                    List<Object> list = new ArrayList<>();
                    for (JsonElement element : value.getAsJsonArray()) {
                        if (element.isJsonPrimitive()) {
                            if (element.getAsJsonPrimitive().isNumber()) {
                                list.add(element.getAsNumber());
                            } else {
                                list.add(element.getAsString());
                            }
                        }
                    }
                    parameters.put(key, list);
                }
            }
        }

        return new Task(action, parameters);
    }

    public static class ParsedResponse {
        private final String reasoning;
        private final String plan;
        private final List<Task> tasks;

        public ParsedResponse(String reasoning, String plan, List<Task> tasks) {
            this.reasoning = reasoning;
            this.plan = plan;
            this.tasks = tasks;
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
    }
}
