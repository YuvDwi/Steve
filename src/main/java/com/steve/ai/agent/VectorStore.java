package com.steve.ai.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;
import com.steve.ai.ai.EmbeddingClient;
import com.steve.ai.ai.OpenAIEmbeddingClient;
import com.steve.ai.util.VectorMath;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Real vector store with semantic embeddings
 * Supports adding memories, semantic search, and persistence
 * Uses OpenAI embeddings API for generating vectors
 */
public class VectorStore {
    private final Map<String, VectorEntry> store;
    private final EmbeddingClient embeddingClient;
    private final int dimensions;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Create vector store with default OpenAI embedding client
     */
    public VectorStore() {
        this(new OpenAIEmbeddingClient());
    }

    /**
     * Create vector store with custom embedding client
     * @param embeddingClient Client for generating embeddings
     */
    public VectorStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
        this.dimensions = embeddingClient.getDimensions();
        this.store = new HashMap<>();

        SteveMod.LOGGER.info("Initialized VectorStore with {} ({}D)",
            embeddingClient.getName(), dimensions);
    }

    /**
     * Add a memory to the vector store
     * @param id Unique identifier for this memory
     * @param text Text to embed and store
     * @param metadata Additional metadata
     */
    public void addMemory(String id, String text, Map<String, Object> metadata) {
        try {
            float[] embedding = embeddingClient.generateEmbedding(text);

            // Normalize embedding for faster cosine similarity
            embedding = VectorMath.normalize(embedding);

            VectorEntry entry = new VectorEntry(id, text, embedding, metadata);
            store.put(id, entry);

            SteveMod.LOGGER.debug("Added memory to vector store: {} ({})",
                id, text.substring(0, Math.min(50, text.length())));

        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to add memory to vector store: {}", id, e);
        }
    }

    /**
     * Add multiple memories in batch
     * @param memories List of memories to add
     */
    public void addMemories(List<MemoryInput> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }

        try {
            // Extract texts for batch embedding
            List<String> texts = memories.stream()
                .map(m -> m.text)
                .collect(Collectors.toList());

            // Generate embeddings in batch
            List<float[]> embeddings = embeddingClient.generateEmbeddings(texts);

            // Store entries
            for (int i = 0; i < memories.size(); i++) {
                MemoryInput memory = memories.get(i);
                float[] embedding = VectorMath.normalize(embeddings.get(i));

                VectorEntry entry = new VectorEntry(
                    memory.id,
                    memory.text,
                    embedding,
                    memory.metadata
                );

                store.put(memory.id, entry);
            }

            SteveMod.LOGGER.info("Added {} memories to vector store in batch", memories.size());

        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to add memories in batch", e);
        }
    }

    /**
     * Search for similar memories using semantic similarity
     * @param query Query text
     * @param topK Number of results to return
     * @return List of search results sorted by similarity
     */
    public List<SearchResult> search(String query, int topK) {
        if (store.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // Generate query embedding
            float[] queryEmbedding = embeddingClient.generateEmbedding(query);
            queryEmbedding = VectorMath.normalize(queryEmbedding);

            // Calculate similarities and sort
            return store.values().stream()
                .map(entry -> {
                    float similarity = VectorMath.cosineSimilarity(
                        entry.embedding,
                        queryEmbedding
                    );
                    return new SearchResult(
                        entry.id,
                        entry.text,
                        similarity,
                        entry.metadata
                    );
                })
                .sorted(Comparator.comparingDouble(r -> -r.similarity))
                .limit(topK)
                .collect(Collectors.toList());

        } catch (Exception e) {
            SteveMod.LOGGER.error("Failed to search vector store", e);
            return new ArrayList<>();
        }
    }

    /**
     * Get memory by ID
     * @param id Memory identifier
     * @return Vector entry or null if not found
     */
    public VectorEntry getMemory(String id) {
        return store.get(id);
    }

    /**
     * Remove memory by ID
     * @param id Memory identifier
     * @return True if memory was removed
     */
    public boolean removeMemory(String id) {
        return store.remove(id) != null;
    }

    /**
     * Clear all memories
     */
    public void clear() {
        store.clear();
    }

    /**
     * Get total number of memories
     * @return Memory count
     */
    public int size() {
        return store.size();
    }

    /**
     * Check if store contains a memory
     * @param id Memory identifier
     * @return True if memory exists
     */
    public boolean contains(String id) {
        return store.containsKey(id);
    }

    /**
     * Get all memory IDs
     * @return Set of memory IDs
     */
    public Set<String> getMemoryIds() {
        return new HashSet<>(store.keySet());
    }

    /**
     * Save vector store to file
     * @param path File path
     */
    public void saveToFile(Path path) {
        try {
            Files.createDirectories(path.getParent());

            // Convert to serializable format (arrays to lists for JSON)
            List<SerializableEntry> serializable = store.values().stream()
                .map(entry -> new SerializableEntry(
                    entry.id,
                    entry.text,
                    toFloatList(entry.embedding),
                    entry.metadata
                ))
                .collect(Collectors.toList());

            try (Writer writer = new FileWriter(path.toFile())) {
                GSON.toJson(serializable, writer);
            }

            SteveMod.LOGGER.info("Saved {} vector entries to {}", store.size(), path);

        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save vector store to {}", path, e);
        }
    }

    /**
     * Load vector store from file
     * @param path File path
     */
    public void loadFromFile(Path path) {
        try {
            if (!Files.exists(path)) {
                SteveMod.LOGGER.info("No existing vector store file at {}", path);
                return;
            }

            try (Reader reader = new FileReader(path.toFile())) {
                Type listType = new TypeToken<List<SerializableEntry>>(){}.getType();
                List<SerializableEntry> loaded = GSON.fromJson(reader, listType);

                if (loaded != null) {
                    store.clear();

                    for (SerializableEntry entry : loaded) {
                        float[] embedding = toFloatArray(entry.embedding);
                        VectorEntry vectorEntry = new VectorEntry(
                            entry.id,
                            entry.text,
                            embedding,
                            entry.metadata
                        );
                        store.put(entry.id, vectorEntry);
                    }

                    SteveMod.LOGGER.info("Loaded {} vector entries from {}", store.size(), path);
                }
            }

        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load vector store from {}", path, e);
        }
    }

    /**
     * Save to default location
     */
    public void save() {
        Path defaultPath = Paths.get("config", "steve", "vector_store", "embeddings.json");
        saveToFile(defaultPath);
    }

    /**
     * Load from default location
     */
    public void load() {
        Path defaultPath = Paths.get("config", "steve", "vector_store", "embeddings.json");
        loadFromFile(defaultPath);
    }

    // Helper methods

    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }

    private float[] toFloatArray(List<Float> list) {
        float[] array = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    // Data classes

    /**
     * Vector entry stored in memory
     */
    public static class VectorEntry {
        public final String id;
        public final String text;
        public final float[] embedding;
        public final Map<String, Object> metadata;

        public VectorEntry(String id, String text, float[] embedding,
                         Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.embedding = embedding;
            this.metadata = metadata != null ? metadata : new HashMap<>();
        }
    }

    /**
     * Serializable entry for JSON storage
     */
    private static class SerializableEntry {
        private final String id;
        private final String text;
        private final List<Float> embedding;
        private final Map<String, Object> metadata;

        public SerializableEntry(String id, String text, List<Float> embedding,
                               Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.embedding = embedding;
            this.metadata = metadata;
        }
    }

    /**
     * Search result with similarity score
     */
    public static class SearchResult {
        public final String id;
        public final String text;
        public final float similarity;
        public final Map<String, Object> metadata;

        public SearchResult(String id, String text, float similarity,
                          Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.similarity = similarity;
            this.metadata = metadata;
        }

        @Override
        public String toString() {
            return String.format("SearchResult{id='%s', similarity=%.3f, text='%s'}",
                id, similarity, text.substring(0, Math.min(50, text.length())));
        }
    }

    /**
     * Input for batch memory addition
     */
    public static class MemoryInput {
        public final String id;
        public final String text;
        public final Map<String, Object> metadata;

        public MemoryInput(String id, String text, Map<String, Object> metadata) {
            this.id = id;
            this.text = text;
            this.metadata = metadata;
        }
    }
}
