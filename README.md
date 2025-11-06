# Steve

https://github.com/user-attachments/assets/[ASSET-ID-HERE]

We built Cursor for Minecraft. Instead of AI that helps you write code, you get AI agents that actually play the game with you.

## What It Does

Steve acts as an Agent, or a series of Agents if you choose to employ all of them. You describe what you want, and he understands the context and executes. Same concept here, except instead of code editing, you get embodied Steves that operate in your Minecraft world.

The interface is simple: press K to open a panel, type what you need. The agents handle the interpretation, planning, and execution. Say "mine some iron" and the agent reasons about where iron spawns, navigates to the appropriate depth, locates ore veins, and extracts the resources. Ask for a house and it considers the available materials, generates an appropriate structure, and builds it block by block.

What makes this interesting is the multi-agent coordination. When multiple Steves work on the same task, they don't just independently execute, they actively coordinate to avoid conflicts and optimize workload distribution. Tell three agents to build a castle and they'll automatically partition the structure, divide sections among themselves, and parallelize the construction.

The agents aren't following predefined scripts. They're operating off natural language instructions, which means:
- **Resource extraction** where agents determine optimal mining locations and strategies
- **Autonomous building** with agents planning layouts and material usage
- **Combat and defense** where agents assess threats and coordinate responses
- **Exploration and gathering** with pathfinding and resource location
- **Collaborative execution** with automatic workload balancing and conflict resolution

## How It Works

Each Steve is basically running an agent loop. When you give a command:

1. It goes to an LLM; we're using Groq for fast inference
2. The LLM breaks down your request into structured code
3. Code gets executed using Minecraft's actual game mechanics
4. If something fails, the agent asks the LLM to replan

## Multi-Agent Coordination

The interesting part is when you have multiple Steves working together. We built a coordination system so they don't step on each other's toes.

When you tell several agents to build the same structure, they:
- Automatically split it into sections
- Each take a part
- Don't place blocks in the same spot
- Rebalance work if someone finishes early

The coordination happens server-side through a manager that tracks active builds and assigns work. It's deterministic, so there's no race conditions or weird conflicts.

## Setup

**You need:**
- Minecraft 1.20.1 with Forge
- Java 17
- An OpenAI API key (or Groq/Gemini if you prefer)

**Installation:**
1. Download the JAR from releases
2. Put it in your `mods` folder
3. Launch Minecraft
4. Copy `config/steve-common.toml.example` to `config/steve-common.toml`
5. Add your API key to the config

Config looks like this:
```toml
[openai]
apiKey = "your-api-key-here"
model = "gpt-3.5-turbo"
maxTokens = 1000
temperature = 0.7
```

Then just spawn a Steve with `/steve spawn Bob` and press K to start using them.

## How We Built This

**Tech Stack:**
- Minecraft Forge 47.2.0 for the modding framework
- Java 17
- Groq API for the agent reasoning (pluggable, also supports OpenAI and Gemini)
- Standard Minecraft pathfinding for movement
- Langchain

**Architecture:**

The core is in the agent package. Each Steve runs a ReAct-style loop:
- Reason about what to do
- Act by executing Java code
- Observe the results
- Repeat

For memory, each Steve maintains a conversation history and context about the world. This gets injected into every LLM call so agents can handle follow-up commands without you repeating context.

The collaborative building system was trickier. We had to build a manager that:
- Divides structures into spatial sections
- Assigns Steves to sections
- Prevents conflicts when placing blocks
- Handles reassignment when Steves finish

It's all server-side, so there's no synchronization issues.

**Project Structure:**
```
src/main/java/com/steve/ai/
├── entity/          # Steve entity class, spawning, lifecycle
├── ai/              # LLM clients (OpenAI, Groq, Gemini), prompt building
├── action/          # Action classes for mine, build, combat, etc
├── agent/           # Core agent loop and coordination
├── memory/          # Context management and world state
├── client/          # GUI (the Cursor-style panel)
└── command/         # Minecraft commands (/steve spawn, etc)
```

If you want to understand how it works, start in the agent package. That's where the reasoning loop lives.

## Building From Source

Standard Gradle stuff:

```bash
git clone https://github.com/YuvDwi/Steve.git
cd Steve
./gradlew build
```

Output JAR is in `build/libs/`.

## Usage Examples

Once you've got Steves spawned, just press K and start talking:

```
"mine 20 iron ore"
"build a house near me"
"help Alex with the tower"
"defend me from zombies"
"follow me"
"gather wood from that forest"
"make a cobblestone platform here"
"attack that creeper"
```

The agents are pretty good at figuring out what you mean. You don't need to be super specific.

## Known Issues

**The agents are only as smart as the LLM.** GPT-3.5 works but makes occasional weird decisions. GPT-4 is noticeably better at multi-step planning.

**No crafting yet.** Agents can mine and place blocks but can't craft tools. We're working on it.

**Actions are synchronous.** If a Steve is mining, it can't do anything else until done. Planning to add proper async execution.

**Memory resets on restart.** Right now context only persists during a play session. We're adding persistent memory with a vector DB.

## What's Next

Things we're working on:
- Crafting system so agents can make their own tools
- Voice commands via Whisper API
- Vector database for long-term memory
- Async action execution for multitasking
- More complex building templates

Goal is to make this actually useful for survival gameplay, not just a tech demo.

## Contributing

If you want to add stuff:
1. Fork the repo
2. Make your changes
3. Make sure it builds with `./gradlew build`
4. Submit a PR

If you're adding new actions, update the prompt template in `PromptBuilder.java` so the LLM knows about them.

## Why We Made This

We wanted to see if the Cursor model could work outside of coding. Turns out it translates pretty well. Same principles: deep environment integration, clear action primitives, persistent context.

Minecraft is actually a good testbed for agent research. Complex enough to be interesting, constrained enough that agents can actually succeed.

Plus it's just fun watching AIs build castles while you explore.

## Credits

- OpenAI for GPT
- Minecraft Forge for the modding API
- LangChain/AutoGPT for agent architecture inspiration

## License

MIT

## Issues

Found a bug? Open an issue: https://github.com/YuvDwi/Steve/issues
