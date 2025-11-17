![ArchGPT Logo.](https://i.ibb.co/zn8fx1Y/ARCHGPT-banner.png)
# ArchGPT - Conversational AI in Minecraft!

**Downloads:** Spigot | Modrinth<br>
**Discord:** https://discord.gg/emDFbsKNV4<br>
**Donations:** https://ko-fi.com/xidentified

## About
Hey, thanks for checking this out! ArchGPT aims to add life-like conversational capabilities to your server's NPCs.
The plugin utilizes the OpenAI API to generate relevant responses for your players, and LibreTranslate to translate
responses if needed.

### Features
- Set a 'default prompt' with base information to feed to all NPCs
- Set individual prompts for each NPC through config.yml or command
- Report inaccurate or inappropriate responses by clicking message
- NPCs are contextually aware, see this page for more info
- Context includes notable locations in the server for them to reference
- Parse PlaceholderAPI placeholders in your prompts
- 'Loading' bubble over NPC head when they type
- New "thinking" idle animation while NPCs generate a response (periodic crouch/uncrouch, occasional hand swing)
- Chat colors supporting MiniMessage syntax
- Prompts new players to interact with NPCs to begin conversation
- Translate plugin messages and API responses to player locale
- Adjustable NPC response length
- Optionally split long reponses into seperate chats
- Broadcast a message from any NPC server-wide for events, etc
- End conversations by typing 'cancel' or whatever word you want
- Conversations end by exiting radius, or changing worlds
- NPCs remember their conversations with individual players
- Reset one NPC's memory, or all of them if needed
- SQLite and MySQL storage

### What's New
The latest update focuses on richer presentation, a clearer configuration experience, broader provider support, and sturdier error handling.

- NPC “Thinking” Animation
  - Lightweight idle animation plays while an NPC is generating a reply.
  - NPCs periodically crouch/uncrouch and occasionally swing their hand until a response is ready.
  - Animation starts after player message cooldown validation and stops on response send or conversation end.

- Config Restructure & Documentation
  - `config.yml` now has clear section dividers and detailed comments.
  - Options reordered into a logical flow: Core Mode → OpenAI Direct → MCP → Behavior → Translation → Prompts → NPC Prompts → World Context → Presentation → Storage.
  - Expanded examples and a default empty `knowledge` entry to help you get started quickly.

- Knowledge System
  - New `knowledge` config option supports multiline blocks, lists, and structured maps.
  - Configuration is normalized in code (compiled into one long string via `getKnowledge()`), so you can choose the format you prefer.
  - Compiled knowledge is injected into the request context for both MCP and direct OpenAI modes.

- Improved System Message (Direct Mode)
  - All direct OpenAI requests now include a full-context system message.
  - System message prepends `default_prompt`, instructs the model to treat Knowledge as canonical server facts, and appends environment/player/NPC/knowledge context.

- OpenAI Direct Mode Support
  - New `use_mcp` toggle (default: `true`) lets you switch between MCP and direct OpenAI.
  - Provide `api_key` and reuse `chatgpt_engine` / `max_response_length` for direct Chat Completions.
  - Direct path added to request handling with robust parsing and graceful fallbacks.

- Error Handling Improvements
  - Clearer logging with exception class names and actionable messages (goodbye, vague “MCP Request Failed: null”).

#### Quick Start for Direct Mode
1) Open `config.yml` and set:
   - `use_mcp: false`
   - `api_key: "sk-..."`
   - Optionally adjust `chatgpt_engine` and `max_response_length`.
2) Restart your server.
3) Talk to an NPC and watch the new “thinking” animation while responses are generated.

### Compatibility
The plugin has been tested on Paper and Spigot 1.20.2. Requires Citizens.
