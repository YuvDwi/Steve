package com.steve.ai.ai;

import java.util.List;

/**
 * Interface for generating text embeddings
 * Implementations can use different embedding services (OpenAI, local models, etc.)
 */
public interface EmbeddingClient {
    /**
     * Generate embedding for a single text
     * @param text Input text
     * @return Embedding vector
     */
    float[] generateEmbedding(String text);

    /**
     * Generate embeddings for multiple texts (batch processing)
     * Implementations should optimize for batch requests
     * @param texts List of input texts
     * @return List of embedding vectors
     */
    List<float[]> generateEmbeddings(List<String> texts);

    /**
     * Get the dimensionality of embeddings produced by this client
     * @return Number of dimensions in embedding vectors
     */
    int getDimensions();

    /**
     * Get the name of this embedding client (for logging/debugging)
     * @return Client name
     */
    String getName();
}
