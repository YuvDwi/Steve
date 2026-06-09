package com.steve.ai.llm;

import com.steve.ai.SteveMod;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.llm.async.*;
import com.steve.ai.llm.resilience.LLMFallbackHandler;
import com.steve.ai.llm.resilience.ResilientLLMClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TaskPlanner {
    private final AsyncLLMClient asyncOpenAIClient;
    private final AsyncLLMClient asyncGroqClient;
    private final AsyncLLMClient asyncGeminiClient;
    private final LLMCache llmCache;
    private final LLMFallbackHandler fallbackHandler;

    public TaskPlanner() {
        this.llmCache = new LLMCache();
        this.fallbackHandler = new LLMFallbackHandler();

        String apiKey = SteveConfig.OPENAI_API_KEY.get();
        String model = SteveConfig.OPENAI_MODEL.get();
        int maxTokens = SteveConfig.MAX_TOKENS.get();
        double temperature = SteveConfig.TEMPERATURE.get();
        String baseUrl = SteveConfig.OPENAI_BASE_URL.get();

        AsyncLLMClient baseOpenAI = new AsyncOpenAIClient(apiKey, model, maxTokens, temperature, baseUrl);
        AsyncLLMClient baseGroq = new AsyncGroqClient(apiKey, "llama-3.1-8b-instant", 500, temperature);
        AsyncLLMClient baseGemini = new AsyncGeminiClient(apiKey, "gemini-1.5-flash", maxTokens, temperature);

        this.asyncOpenAIClient = new ResilientLLMClient(baseOpenAI, llmCache, fallbackHandler);
        this.asyncGroqClient = new ResilientLLMClient(baseGroq, llmCache, fallbackHandler);
        this.asyncGeminiClient = new ResilientLLMClient(baseGemini, llmCache, fallbackHandler);

        SteveMod.LOGGER.info("TaskPlanner initialized with async resilient clients");
    }

    /**
     * Returns the appropriate async client based on provider config.
     *
     * @param provider Provider name ("openai", "groq", "gemini")
     * @return Resilient async client
     */
    public AsyncLLMClient getAsyncClient(String provider) {
        return switch (provider) {
            case "openai" -> asyncOpenAIClient;
            case "gemini" -> asyncGeminiClient;
            case "groq" -> asyncGroqClient;
            default -> {
                SteveMod.LOGGER.warn("[Async] Unknown provider '{}', using Groq", provider);
                yield asyncGroqClient;
            }
        };
    }

    /**
     * Returns the LLM cache for monitoring.
     *
     * @return LLM cache instance
     */
    public LLMCache getLLMCache() {
        return llmCache;
    }

    /**
     * Checks if the specified provider's async client is healthy.
     *
     * @param provider Provider name
     * @return true if healthy (circuit breaker not OPEN)
     */
    public boolean isProviderHealthy(String provider) {
        return getAsyncClient(provider).isHealthy();
    }

    public boolean validateTask(Task task) {
        com.steve.ai.plugin.ActionSchema schema =
            com.steve.ai.plugin.ActionRegistry.getInstance().getSchema(task.getAction());
        if (schema == null) {
            SteveMod.LOGGER.warn("Unknown action type: {}", task.getAction());
            return false;
        }
        return schema.validate(task);
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }

    /**
     * Build a fresh parameter map for a ReAct step. Each call must return a new
     * map because the prompt is rebuilt every step (scratchpad grows).
     */
    public Map<String, Object> buildReActParams() {
        return new HashMap<>(Map.of(
            "systemPrompt", PromptBuilder.buildReActSystemPrompt(SteveConfig.REACT_MAX_STEPS.get()),
            "model", SteveConfig.OPENAI_MODEL.get(),
            "maxTokens", SteveConfig.MAX_TOKENS.get(),
            "temperature", SteveConfig.TEMPERATURE.get()
        ));
    }
}

