package com.steve.ai.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class SteveConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> AI_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_MODEL;
    public static final ForgeConfigSpec.IntValue MAX_TOKENS;
    public static final ForgeConfigSpec.DoubleValue TEMPERATURE;
    public static final ForgeConfigSpec.ConfigValue<String> OPENAI_BASE_URL;
    public static final ForgeConfigSpec.IntValue ACTION_TICK_DELAY;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CHAT_RESPONSES;
    public static final ForgeConfigSpec.BooleanValue CREATIVE_MODE;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_STEVES;
    public static final ForgeConfigSpec.IntValue BUILD_TICK_DELAY;
    public static final ForgeConfigSpec.BooleanValue MCP_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> MCP_SERVERS;
    public static final ForgeConfigSpec.IntValue MCP_TIMEOUT_MS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("AI API Configuration").push("ai");
        
        AI_PROVIDER = builder
            .comment("AI provider to use: 'groq' (FASTEST, FREE), 'openai', or 'gemini'")
            .define("provider", "groq");
        
        builder.pop();

        builder.comment("OpenAI/Gemini API Configuration (same key field used for both)").push("openai");
        
        OPENAI_API_KEY = builder
            .comment("Your OpenAI API key (required)")
            .define("apiKey", "");
        
        OPENAI_MODEL = builder
            .comment("OpenAI model to use (gpt-4, gpt-4-turbo-preview, gpt-3.5-turbo)")
            .define("model", "gpt-4-turbo-preview");
        
        MAX_TOKENS = builder
            .comment("Maximum tokens per API request")
            .defineInRange("maxTokens", 8000, 100, 65536);
        
        TEMPERATURE = builder
            .comment("Temperature for AI responses (0.0-2.0, lower is more deterministic)")
            .defineInRange("temperature", 0.7, 0.0, 2.0);

        OPENAI_BASE_URL = builder
            .comment("Custom OpenAI API URL (leave empty for default)")
            .define("baseUrl", "");

        builder.pop();

        builder.comment("Steve Behavior Configuration").push("behavior");
        
        ACTION_TICK_DELAY = builder
            .comment("Ticks between action checks (20 ticks = 1 second)")
            .defineInRange("actionTickDelay", 20, 1, 100);
        
        ENABLE_CHAT_RESPONSES = builder
            .comment("Allow Steves to respond in chat")
            .define("enableChatResponses", true);

        CREATIVE_MODE = builder
            .comment("Creative mode - Steve has unlimited building materials (no mining needed)")
            .define("creativeMode", true);

        MAX_ACTIVE_STEVES = builder
            .comment("Maximum number of Steves that can be active simultaneously")
            .defineInRange("maxActiveSteves", 10, 1, 50);

        BUILD_TICK_DELAY = builder
            .comment("Ticks between each block placement during building (20 ticks = 1 second, default 20 = 1 block/sec)")
            .defineInRange("buildTickDelay", 20, 1, 200);

        builder.pop();

        builder.comment("MCP (Model Context Protocol) Configuration").push("mcp");

        MCP_ENABLED = builder
            .comment("Enable MCP tool calling")
            .define("enabled", false);

        MCP_SERVERS = builder
            .comment("JSON array of MCP server configurations: [{\"name\":\"mempalace\",\"url\":\"http://localhost:6060\"}]")
            .define("servers", "[{\"name\":\"mempalace\",\"url\":\"http://localhost:6060\"}]");

        MCP_TIMEOUT_MS = builder
            .comment("MCP tool call timeout in milliseconds")
            .defineInRange("timeoutMs", 30000, 1000, 120000);

        builder.pop();

        SPEC = builder.build();
    }
}

