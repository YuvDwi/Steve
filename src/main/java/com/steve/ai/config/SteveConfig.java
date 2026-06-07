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
    public static final ForgeConfigSpec.IntValue MAX_TEMPLATES_PER_PLAN;
    public static final ForgeConfigSpec.BooleanValue MCP_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> MCP_SERVERS;
    public static final ForgeConfigSpec.IntValue MCP_TIMEOUT_MS;
    public static final ForgeConfigSpec.IntValue REACT_MAX_STEPS;
    public static final ForgeConfigSpec.IntValue REACT_OBS_TRUNCATE;
    public static final ForgeConfigSpec.IntValue REACT_FAIL_TOLERANCE;
    public static final ForgeConfigSpec.IntValue DASHBOARD_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> DASHBOARD_FRONTEND_URL;

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

        MAX_TEMPLATES_PER_PLAN = builder
            .comment("Maximum number of NBT templates the LLM may combine in one /steve plan (1-10)")
            .defineInRange("maxTemplatesPerPlan", 4, 1, 10);

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

        builder.comment("ReAct (Reason + Act) Mode Configuration").push("react");

        REACT_MAX_STEPS = builder
            .comment("Maximum ReAct steps before force-finishing (1-50)")
            .defineInRange("maxSteps", 12, 1, 50);

        REACT_OBS_TRUNCATE = builder
            .comment("Per-observation character truncation (100-4000)")
            .defineInRange("observationTruncateChars", 800, 100, 4000);

        REACT_FAIL_TOLERANCE = builder
            .comment("Consecutive LLM parse failures before giving up (1-10)")
            .defineInRange("maxConsecutiveFailures", 3, 1, 10);

        builder.pop();

        builder.comment("HTTP Dashboard Configuration (external HTML plan UI)").push("dashboard");

        DASHBOARD_PORT = builder
            .comment("Port the /steve dashboard embedded HTTP server binds to. 127.0.0.1 only.")
            .defineInRange("port", 8765, 1024, 65535);

        DASHBOARD_FRONTEND_URL = builder
            .comment("URL the /steve dashboard command tells the player to open. "
                + "The embedded HTTP server only serves /events and /command; the "
                + "real UI lives at this URL (Vite dev server in development, "
                + "or a static host in production).")
            .define("frontendUrl", "http://localhost:5173");

        builder.pop();

        SPEC = builder.build();
    }
}

