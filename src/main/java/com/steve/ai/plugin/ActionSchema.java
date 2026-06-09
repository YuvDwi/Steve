package com.steve.ai.plugin;

import com.steve.ai.action.Task;

import java.util.List;
import java.util.StringJoiner;
import java.util.function.Predicate;

public record ActionSchema(
    String name,
    String description,
    List<ParamSpec> params,
    String promptHint,
    Predicate<Task> customValidator
) {
    public record ParamSpec(String key, String type, boolean required, String hint) {}

    public ActionSchema(String name, String description, List<ParamSpec> params, String promptHint) {
        this(name, description, params, promptHint, null);
    }

    public boolean validate(Task task) {
        if (customValidator != null) return customValidator.test(task);
        for (ParamSpec p : params) {
            if (p.required && !task.hasParameters(p.key)) return false;
        }
        return true;
    }

    public String toPromptLine() {
        return "- " + name + ": " + promptHint;
    }

    public static ParamSpec required(String key, String type, String hint) {
        return new ParamSpec(key, type, true, hint);
    }

    public static ParamSpec optional(String key, String type, String hint) {
        return new ParamSpec(key, type, false, hint);
    }

    public static String generatePromptSection(List<ActionSchema> schemas) {
        StringJoiner sj = new StringJoiner("\n");
        for (ActionSchema s : schemas) {
            sj.add(s.toPromptLine());
        }
        return sj.toString();
    }
}
