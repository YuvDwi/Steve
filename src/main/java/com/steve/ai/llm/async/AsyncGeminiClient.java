package com.steve.ai.llm.async;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous Google Gemini API client using Java HttpClient's sendAsync().
 *
 * <p>Gemini provides powerful multi-modal AI capabilities with competitive pricing.
 * Uses Google's proprietary API format (different from OpenAI).</p>
 *
 * <p><b>API Endpoint:</b> https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent</p>
 *
 * <p><b>Supported Models:</b></p>
 * <ul>
 *   <li>gemini-2.0-flash-exp (newest, experimental)</li>
 *   <li>gemini-1.5-flash (fast, recommended)</li>
 *   <li>gemini-1.5-pro (best quality)</li>
 *   <li>gemini-pro (legacy, still supported)</li>
 * </ul>
 *
 * <p><b>Free Tier Limits:</b></p>
 * <ul>
 *   <li>15 requests per minute</li>
 *   <li>1,500 requests per day</li>
 * </ul>
 *
 * <p><b>API Format Differences:</b></p>
 * <ul>
 *   <li>Uses "contents" array with "parts" (not "messages")</li>
 *   <li>API key in query string (not Authorization header)</li>
 *   <li>Response in "candidates[].content.parts[].text"</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Thread-safe. HttpClient is thread-safe and immutable.</p>
 *
 * @since 1.1.0
 */
public class AsyncGeminiClient implements AsyncLLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncGeminiClient.class);
    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String PROVIDER_ID = "gemini";
    /** Used when no valid Gemini model is configured (e.g. an OpenAI model name leaked in). */
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;

    /**
     * Constructs an AsyncGeminiClient.
     *
     * @param apiKey      Google AI Studio API key (required)
     * @param model       Model to use (e.g., "gemini-1.5-flash")
     * @param maxTokens   Maximum tokens in response (e.g., 1000)
     * @param temperature Response randomness (0.0 - 2.0)
     * @throws IllegalArgumentException if apiKey is null or empty
     */
    public AsyncGeminiClient(String apiKey, String model, int maxTokens, double temperature) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Gemini API key cannot be null or empty");
        }

        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        LOGGER.info("AsyncGeminiClient initialized (model: {}, maxTokens: {}, temperature: {})",
            model, maxTokens, temperature);
    }

    @Override
    public CompletableFuture<LLMResponse> sendAsync(String prompt, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        // Honor the model from the request params (set from config) instead of the
        // value baked in at construction time. This is what makes the configured
        // model (e.g. gemini-2.5-flash) actually take effect.
        String modelToUse = resolveModel(params);

        String requestBody = buildRequestBody(prompt, params, modelToUse);
        String urlWithKey = GEMINI_API_BASE + modelToUse + ":generateContent?key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(urlWithKey))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(60)) // Gemini can be slower
            .build();

        LOGGER.debug("[gemini] Sending async request (prompt length: {} chars)", prompt.length());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                long latencyMs = System.currentTimeMillis() - startTime;

                if (response.statusCode() != 200) {
                    LLMException.ErrorType errorType = determineErrorType(response.statusCode());
                    boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;

                    LOGGER.error("[gemini] API error: status={}, body={}", response.statusCode(),
                        truncate(response.body(), 200));

                    throw new LLMException(
                        "Gemini API error: HTTP " + response.statusCode(),
                        errorType,
                        PROVIDER_ID,
                        retryable
                    );
                }

                return parseResponse(response.body(), latencyMs, modelToUse);
            });
    }

    /**
     * Resolves which Gemini model to use for a request.
     *
     * <p>Prefers the "model" supplied in the request params (which comes from the
     * mod config), falling back to the model set at construction time. If neither is
     * a Gemini model (for example an OpenAI model name was left in the shared config),
     * a sane Gemini default is used so requests don't hit a non-existent endpoint.</p>
     *
     * @param params Request params, possibly containing a "model" entry
     * @return A usable Gemini model id
     */
    private String resolveModel(Map<String, Object> params) {
        Object requested = params.get("model");
        String candidate = (requested instanceof String s && !s.isBlank()) ? s : this.model;

        if (candidate == null || !candidate.toLowerCase().startsWith("gemini")) {
            LOGGER.warn("[gemini] Configured model '{}' is not a Gemini model; falling back to '{}'. "
                + "Set [openai] model to a Gemini model (e.g. gemini-2.5-flash) in steve-common.toml.",
                candidate, DEFAULT_MODEL);
            return DEFAULT_MODEL;
        }
        return candidate;
    }

    /**
     * Builds the JSON request body in Gemini's format.
     *
     * <p>Gemini format uses "contents" with "parts" instead of OpenAI's "messages".</p>
     *
     * @param prompt User prompt
     * @param params Additional parameters
     * @return JSON string
     */
    private String buildRequestBody(String prompt, Map<String, Object> params, String modelUsed) {
        JsonObject body = new JsonObject();

        // Build contents array (Gemini format)
        JsonArray contents = new JsonArray();

        // Combine system prompt and user prompt into a single user message
        // (Gemini handles system instructions differently)
        String systemPrompt = (String) params.get("systemPrompt");
        String combinedPrompt = systemPrompt != null && !systemPrompt.isEmpty()
            ? systemPrompt + "\n\n" + prompt
            : prompt;

        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");

        JsonArray parts = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", combinedPrompt);
        parts.add(textPart);

        userContent.add("parts", parts);
        contents.add(userContent);

        body.add("contents", contents);

        // Generation config
        JsonObject generationConfig = new JsonObject();
        double tempToUse = (double) params.getOrDefault("temperature", this.temperature);
        int maxTokensToUse = (int) params.getOrDefault("maxTokens", this.maxTokens);

        generationConfig.addProperty("temperature", tempToUse);
        generationConfig.addProperty("maxOutputTokens", maxTokensToUse);

        // Gemini 2.5 models enable "thinking" by default, which consumes the output token
        // budget on hidden reasoning and can return a MAX_TOKENS response with no text.
        // For our structured-JSON use case we disable thinking so the model spends its
        // budget on the actual answer. (thinkingBudget=0 is supported by 2.5 Flash.)
        if (modelUsed != null && modelUsed.toLowerCase().contains("2.5")
                && modelUsed.toLowerCase().contains("flash")) {
            JsonObject thinkingConfig = new JsonObject();
            thinkingConfig.addProperty("thinkingBudget", 0);
            generationConfig.add("thinkingConfig", thinkingConfig);
        }

        body.add("generationConfig", generationConfig);

        return body.toString();
    }

    /**
     * Parses Gemini API response.
     *
     * <p>Response format: candidates[0].content.parts[0].text</p>
     *
     * @param responseBody Raw JSON response
     * @param latencyMs    Request latency
     * @return Parsed LLMResponse
     */
    private LLMResponse parseResponse(String responseBody, long latencyMs, String modelUsed) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            // Check for candidates array
            if (!json.has("candidates") || json.getAsJsonArray("candidates").isEmpty()) {
                // Check if there's a blocked response
                if (json.has("promptFeedback")) {
                    JsonObject feedback = json.getAsJsonObject("promptFeedback");
                    if (feedback.has("blockReason")) {
                        String reason = feedback.get("blockReason").getAsString();
                        throw new LLMException(
                            "Gemini blocked the prompt: " + reason,
                            LLMException.ErrorType.CLIENT_ERROR,
                            PROVIDER_ID,
                            false
                        );
                    }
                }

                throw new LLMException(
                    "Gemini response missing 'candidates' array",
                    LLMException.ErrorType.INVALID_RESPONSE,
                    PROVIDER_ID,
                    false
                );
            }

            JsonObject firstCandidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();

            // Check finish reason
            if (firstCandidate.has("finishReason")) {
                String finishReason = firstCandidate.get("finishReason").getAsString();
                if ("MAX_TOKENS".equals(finishReason)) {
                    LOGGER.warn("[gemini] Response truncated due to MAX_TOKENS limit");
                }
            }

            // Extract content
            if (!firstCandidate.has("content")) {
                throw new LLMException(
                    "Gemini response missing 'content' in candidate",
                    LLMException.ErrorType.INVALID_RESPONSE,
                    PROVIDER_ID,
                    false
                );
            }

            JsonObject content = firstCandidate.getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");

            if (parts == null || parts.isEmpty()) {
                throw new LLMException(
                    "Gemini response has no parts in content",
                    LLMException.ErrorType.INVALID_RESPONSE,
                    PROVIDER_ID,
                    false
                );
            }

            String text = parts.get(0).getAsJsonObject().get("text").getAsString();

            // Gemini doesn't always provide usage metrics
            int tokensUsed = 0;
            if (json.has("usageMetadata")) {
                JsonObject usage = json.getAsJsonObject("usageMetadata");
                if (usage.has("totalTokenCount")) {
                    tokensUsed = usage.get("totalTokenCount").getAsInt();
                }
            }

            LOGGER.debug("[gemini] Response received (latency: {}ms, tokens: {})", latencyMs, tokensUsed);

            return LLMResponse.builder()
                .content(text)
                .model(modelUsed)
                .providerId(PROVIDER_ID)
                .latencyMs(latencyMs)
                .tokensUsed(tokensUsed)
                .fromCache(false)
                .build();

        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("[gemini] Failed to parse response: {}", truncate(responseBody, 200), e);
            throw new LLMException(
                "Failed to parse Gemini response: " + e.getMessage(),
                LLMException.ErrorType.INVALID_RESPONSE,
                PROVIDER_ID,
                false,
                e
            );
        }
    }

    private LLMException.ErrorType determineErrorType(int statusCode) {
        return switch (statusCode) {
            case 429 -> LLMException.ErrorType.RATE_LIMIT;
            case 401, 403 -> LLMException.ErrorType.AUTH_ERROR;
            case 400 -> LLMException.ErrorType.CLIENT_ERROR;
            case 408, 504 -> LLMException.ErrorType.TIMEOUT;
            default -> {
                if (statusCode >= 500) {
                    yield LLMException.ErrorType.SERVER_ERROR;
                }
                yield LLMException.ErrorType.CLIENT_ERROR;
            }
        };
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "[null]";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isHealthy() {
        return true;
    }
}
