package com.steve.ai.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.steve.ai.SteveMod;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores conversation history between Steve and players
 * Used for LLM context to maintain conversation continuity
 * Persisted to JSON files
 */
public class ConversationHistory {
    private final String steveName;
    private final List<Message> messages;
    private static final int MAX_MESSAGES = 100; // Prevent unbounded growth
    private static final int MAX_TOKENS_ESTIMATE = 4096; // Rough token limit
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public ConversationHistory(String steveName) {
        this.steveName = steveName;
        this.messages = new ArrayList<>();
    }

    /**
     * Add a message to conversation history
     */
    public void addMessage(String role, String content) {
        Message message = new Message(
            System.currentTimeMillis(),
            role,
            content
        );

        messages.add(message);

        // Prune if exceeding limits
        if (messages.size() > MAX_MESSAGES) {
            pruneOldMessages();
        }

        SteveMod.LOGGER.debug("Steve '{}' recorded conversation: [{}] {}",
            steveName, role, content);
    }

    /**
     * Get recent messages
     */
    public List<Message> getRecentMessages(int count) {
        int startIndex = Math.max(0, messages.size() - count);
        return new ArrayList<>(messages.subList(startIndex, messages.size()));
    }

    /**
     * Get messages within token limit (rough estimate)
     */
    public List<Message> getMessagesWithinTokenLimit() {
        List<Message> result = new ArrayList<>();
        int estimatedTokens = 0;

        // Work backwards from most recent
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            int msgTokens = estimateTokens(msg.content);

            if (estimatedTokens + msgTokens > MAX_TOKENS_ESTIMATE) {
                break;
            }

            result.add(0, msg); // Add to front
            estimatedTokens += msgTokens;
        }

        return result;
    }

    /**
     * Get all messages
     */
    public List<Message> getAllMessages() {
        return new ArrayList<>(messages);
    }

    /**
     * Get formatted conversation for LLM
     */
    public String getFormattedConversation(int maxMessages) {
        List<Message> recent = getRecentMessages(maxMessages);

        if (recent.isEmpty()) {
            return "";
        }

        StringBuilder formatted = new StringBuilder();
        for (Message msg : recent) {
            formatted.append("[").append(msg.role).append("]: ")
                    .append(msg.content).append("\n");
        }

        return formatted.toString();
    }

    /**
     * Clear all messages
     */
    public void clear() {
        messages.clear();
    }

    /**
     * Get message count
     */
    public int size() {
        return messages.size();
    }

    /**
     * Prune old messages to stay under limit
     * Keeps system messages and recent messages
     */
    private void pruneOldMessages() {
        if (messages.size() <= MAX_MESSAGES) {
            return;
        }

        // Keep first 10 messages (likely contain important context)
        // Keep last 70 messages (recent conversation)
        // Remove middle messages
        int toKeepStart = 10;
        int toKeepEnd = 70;

        if (messages.size() > toKeepStart + toKeepEnd) {
            List<Message> kept = new ArrayList<>();

            // Keep first 10
            kept.addAll(messages.subList(0, toKeepStart));

            // Keep last 70
            kept.addAll(messages.subList(
                messages.size() - toKeepEnd,
                messages.size()
            ));

            messages.clear();
            messages.addAll(kept);

            SteveMod.LOGGER.info("Steve '{}' pruned conversation history to {} messages",
                steveName, messages.size());
        }
    }

    /**
     * Rough token estimation (4 chars ≈ 1 token)
     */
    private int estimateTokens(String text) {
        return text.length() / 4;
    }

    /**
     * Save to JSON file
     */
    public void saveToFile() {
        try {
            Path memoryDir = Paths.get("config", "steve", "memory");
            Files.createDirectories(memoryDir);

            Path conversationFile = memoryDir.resolve(steveName + "_conversation.json");

            try (Writer writer = new FileWriter(conversationFile.toFile())) {
                GSON.toJson(messages, writer);
            }

            SteveMod.LOGGER.info("Saved {} conversation messages for Steve '{}'",
                messages.size(), steveName);
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to save conversation history for Steve '{}'",
                steveName, e);
        }
    }

    /**
     * Load from JSON file
     */
    public void loadFromFile() {
        try {
            Path conversationFile = Paths.get("config", "steve", "memory",
                steveName + "_conversation.json");

            if (!Files.exists(conversationFile)) {
                SteveMod.LOGGER.info("No existing conversation file for Steve '{}'", steveName);
                return;
            }

            try (Reader reader = new FileReader(conversationFile.toFile())) {
                Type listType = new TypeToken<List<Message>>(){}.getType();
                List<Message> loaded = GSON.fromJson(reader, listType);

                if (loaded != null) {
                    messages.clear();
                    messages.addAll(loaded);
                    SteveMod.LOGGER.info("Loaded {} conversation messages for Steve '{}'",
                        messages.size(), steveName);
                }
            }
        } catch (IOException e) {
            SteveMod.LOGGER.error("Failed to load conversation history for Steve '{}'",
                steveName, e);
        }
    }

    /**
     * Message data class
     */
    public static class Message {
        private final long timestamp;
        private final String role; // "user", "assistant", "system"
        private final String content;

        public Message(long timestamp, String role, String content) {
            this.timestamp = timestamp;
            this.role = role;
            this.content = content;
        }

        public long getTimestamp() { return timestamp; }
        public String getRole() { return role; }
        public String getContent() { return content; }

        @Override
        public String toString() {
            return String.format("[%s] %s", role, content);
        }
    }
}
