package com.steve.ai.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steve.ai.SteveMod;
import com.steve.ai.config.SteveConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Embeddings API client
 * Uses text-embedding-3-small model (1536 dimensions)
 * Cost: $0.02 / 1M tokens
 */
public class OpenAIEmbeddingClient implements EmbeddingClient {
    private static final String EMBEDDINGS_API_URL = "https://api.openai.com/v1/embeddings";
    private static final String MODEL = "text-embedding-3-small"; // 1536 dimensions
    private static final int DIMENSIONS = 1536;
    private static final int MAX_RETRIES = 3;
    private static final int INITIAL_RETRY_DELAY_MS = 1000;

    private final HttpClient client;
    private final String apiKey;

    public OpenAIEmbeddingClient() {
        this.apiKey = SteveConfig.OPENAI_API_KEY.get();
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Override
    public float[] generateEmbedding(String text) {
        if (apiKey == null || apiKey.isEmpty()) {
            SteveMod.LOGGER.error("OpenAI API key not configured for embeddings!");
            return createZeroVector();
        }

        if (text == null || text.trim().isEmpty()) {
            SteveMod.LOGGER.warn("Empty text provided for embedding");
            return createZeroVector();
        }

        JsonObject requestBody = buildRequestBody(text);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(EMBEDDINGS_API_URL))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build();

        // Retry logic with exponential backoff
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return parseEmbeddingResponse(response.body());
                }

                // Check if error is retryable
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    if (attempt < MAX_RETRIES - 1) {
                        int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                        SteveMod.LOGGER.warn("OpenAI Embeddings API failed with status {}, " +
                            "retrying in {}ms (attempt {}/{})",
                            response.statusCode(), delayMs, attempt + 1, MAX_RETRIES);
                        Thread.sleep(delayMs);
                        continue;
                    }
                }

                // Non-retryable error or final attempt
                SteveMod.LOGGER.error("OpenAI Embeddings API request failed: {}",
                    response.statusCode());
                SteveMod.LOGGER.error("Response body: {}", response.body());
                return createZeroVector();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                SteveMod.LOGGER.error("Embedding request interrupted", e);
                return createZeroVector();
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                    SteveMod.LOGGER.warn("Error communicating with OpenAI Embeddings API, " +
                        "retrying in {}ms (attempt {}/{})",
                        delayMs, attempt + 1, MAX_RETRIES, e);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return createZeroVector();
                    }
                } else {
                    SteveMod.LOGGER.error("Error communicating with OpenAI Embeddings API " +
                        "after {} attempts", MAX_RETRIES, e);
                    return createZeroVector();
                }
            }
        }

        return createZeroVector();
    }

    @Override
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        // For batch requests, we could optimize by sending all texts at once
        // OpenAI API supports up to 2048 texts per request
        // For simplicity, we'll process one at a time for now
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(generateEmbedding(text));
        }

        return embeddings;
    }

    @Override
    public int getDimensions() {
        return DIMENSIONS;
    }

    @Override
    public String getName() {
        return "OpenAI-" + MODEL;
    }

    /**
     * Build JSON request body for embeddings API
     */
    private JsonObject buildRequestBody(String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("input", text);
        return body;
    }

    /**
     * Parse embedding from API response
     */
    private float[] parseEmbeddingResponse(String responseBody) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray data = jsonResponse.getAsJsonArray("data");

            if (data == null || data.size() == 0) {
                SteveMod.LOGGER.error("No embedding data in response");
                return createZeroVector();
            }

            JsonObject embeddingData = data.get(0).getAsJsonObject();
            JsonArray embeddingArray = embeddingData.getAsJsonArray("embedding");

            if (embeddingArray == null || embeddingArray.size() != DIMENSIONS) {
                SteveMod.LOGGER.error("Invalid embedding dimensions: expected {}, got {}",
                    DIMENSIONS, embeddingArray != null ? embeddingArray.size() : 0);
                return createZeroVector();
            }

            float[] embedding = new float[DIMENSIONS];
            for (int i = 0; i < DIMENSIONS; i++) {
                embedding[i] = embeddingArray.get(i).getAsFloat();
            }

            return embedding;

        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to parse embedding response", e);
            return createZeroVector();
        }
    }

    /**
     * Create zero vector as fallback
     */
    private float[] createZeroVector() {
        return new float[DIMENSIONS];
    }
}
