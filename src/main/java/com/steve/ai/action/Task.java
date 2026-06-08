package com.steve.ai.action;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Task {
    private final String action;
    private final Map<String, Object> parameters;

    public Task(String action, Map<String, Object> parameters) {
        this.action = action;
        this.parameters = parameters;
    }

    public String getAction() {
        return action;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public Object getParameter(String key) {
        return parameters.get(key);
    }

    public String getStringParameter(String key) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : null;
    }

    public String getStringParameter(String key, String defaultValue) {
        Object value = parameters.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    public List<String> getStringListParameter(String key) {
        Object value = parameters.get(key);
        if (value == null) return null;
        if (value instanceof JsonArray arr) {
            List<String> out = new ArrayList<>(arr.size());
            arr.forEach(e -> out.add(e.getAsString()));
            return out;
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o != null) out.add(o.toString());
            }
            return out;
        }
        return null;
    }

    public List<String> getStringListParameter(String key, List<String> defaultValue) {
        List<String> v = getStringListParameter(key);
        return v != null ? v : defaultValue;
    }

    /**
     * Read a parameter as a list of object maps — the shape used by the
     * module-composition protocol. Returns {@code null} when the key is
     * missing; returns an empty list when the key is present but the value
     * is not a list of maps. Elements that are not {@code Map}s are dropped
     * silently (the caller decides what to do with a malformed payload).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getModuleListParameter(String key) {
        Object value = parameters.get(key);
        if (value == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof JsonArray arr) {
            arr.forEach(e -> {
                if (e != null && e.isJsonObject()) {
                    // Gson JsonObject implements Map<String, JsonElement>; the
                    // canonical shape we want is Map<String, Object>. Round-trip
                    // through a plain HashMap to keep callers insulated from Gson.
                    out.add(new java.util.HashMap<>(e.getAsJsonObject().asMap()));
                }
            });
            return out;
        }
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return null;
    }

    public List<Map<String, Object>> getModuleListParameter(String key, List<Map<String, Object>> defaultValue) {
        List<Map<String, Object>> v = getModuleListParameter(key);
        return v != null ? v : defaultValue;
    }

    public int getIntParameter(String key, int defaultValue) {
        Object value = parameters.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    public boolean hasParameters(String... keys) {
        for (String key : keys) {
            if (!parameters.containsKey(key)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "Task{action='" + action + "', parameters=" + parameters + "}";
    }
}

