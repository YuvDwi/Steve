package com.steve.ai.action;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskTest {

    @Test
    void getStringListParameterReadsJsonArray() {
        JsonArray arr = new JsonArray();
        arr.add("a");
        arr.add("b");
        Task t = new Task("build", Map.of("structures", arr));
        assertEquals(List.of("a", "b"), t.getStringListParameter("structures"));
    }

    @Test
    void getStringListParameterReadsJavaList() {
        Task t = new Task("build", Map.of("structures", List.of("a", "b")));
        assertEquals(List.of("a", "b"), t.getStringListParameter("structures"));
    }

    @Test
    void getStringListParameterReturnsNullWhenMissing() {
        Task t = new Task("build", Map.of());
        assertNull(t.getStringListParameter("structures"));
    }

    @Test
    void getStringListParameterReturnsDefaultWhenMissing() {
        Task t = new Task("build", Map.of());
        assertEquals(List.of("fallback"),
            t.getStringListParameter("structures", List.of("fallback")));
    }
}
