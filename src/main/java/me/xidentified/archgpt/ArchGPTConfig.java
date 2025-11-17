package me.xidentified.archgpt;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import me.xidentified.archgpt.context.ContextManager;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventPriority;

import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Getter
public class ArchGPTConfig {
    private final Logger logger;
    private final JavaPlugin plugin;
    private boolean debugMode;
    private long npcChatTimeoutMillis;
    private String defaultPrompt;
    private String chatGptEngine;
    private String apiKey;
    private Duration npcMemoryDuration;
    private int minCharLength;
    private int maxResponseLength;
    private long chatCooldownMillis;
    private boolean shouldSplitLongMsg;
    private ContextManager contextManager;
    private String mcpServerUrl;
    private String mcpProvider;
    private String mcpModel;
    private int mcpMaxTokens;
    private EventPriority chatListenerPriority;
    private boolean useMcp;
    private String knowledge; // Long-form server knowledge compiled from config

    public ArchGPTConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        loadConfig();
    }

    private void loadConfig() {
        // Load the configuration file and set default values
        saveDefaultConfig();
        FileConfiguration config = plugin.getConfig();
        debugMode = config.getBoolean("debug_mode", false);
        npcChatTimeoutMillis = config.getLong("response_timeout", 60000);
        defaultPrompt = config.getString("default_prompt", "Hello!");
        chatGptEngine = config.getString("chatgpt_engine", "gpt-3.5-turbo-1106");
        apiKey = config.getString("api_key", "");
        minCharLength = config.getInt("min_char_length", 10);
        maxResponseLength = config.getInt("max_response_length", 200); // in tokens
        chatCooldownMillis = config.getLong("chat_cooldown", 3000);
        String durationString = config.getString("npc_memory_duration", "7d");
        npcMemoryDuration = parseMinecraftDuration(durationString);
        shouldSplitLongMsg = config.getBoolean("split_long_messages", false);
        
        // Knowledge section (can be string, list, or map); compile into a single string
        this.knowledge = compileKnowledge(config);
        
        // MCP Configuration
        useMcp = config.getBoolean("use_mcp", true);
        mcpServerUrl = config.getString("mcp.server_url", "http://localhost:3000/query");
        mcpProvider = config.getString("mcp.provider", "openai");
        mcpModel = config.getString("mcp.model", "gpt-3.5-turbo");
        mcpMaxTokens = config.getInt("mcp.max_tokens", 200);

        // Chat listener priority
        String priorityStr = config.getString("chat_listener_priority", "LOWEST");
        chatListenerPriority = parsePriority(priorityStr);

        // Set the logger level based on debugMode
        Level loggerLevel = debugMode ? Level.INFO : Level.WARNING;
        logger.setLevel(loggerLevel);

        // Log the active mode
        if (useMcp) {
            logger.info("Using MCP server mode");
            logger.info("MCP Server URL: " + mcpServerUrl);
            logger.info("MCP Provider: " + mcpProvider);
            logger.info("MCP Model: " + mcpModel);
        } else {
            logger.info("Using direct OpenAI mode with model: " + chatGptEngine);
        }
    }

    private String compileKnowledge(FileConfiguration config) {
        if (!config.contains("knowledge")) {
            return "";
        }
        Object raw = config.get("knowledge");
        if (raw == null) return "";

        // 1) Simple string
        if (raw instanceof String) {
            return ((String) raw).trim();
        }

        // 2) List of strings
        if (raw instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) raw;
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (o != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(String.valueOf(o));
                }
            }
            return sb.toString().trim();
        }

        // 3) Section (map-like)
        if (raw instanceof ConfigurationSection || config.isConfigurationSection("knowledge")) {
            ConfigurationSection section = config.getConfigurationSection("knowledge");
            if (section == null) return "";
            StringBuilder sb = new StringBuilder();
            appendSection(sb, section, 0);
            return sb.toString().trim();
        }

        // Fallback
        return String.valueOf(raw).trim();
    }

    private void appendSection(StringBuilder sb, ConfigurationSection section, int depth) {
        String indent = "".repeat(Math.max(0, depth));
        for (String key : section.getKeys(false)) {
            Object val = section.get(key);
            if (val instanceof ConfigurationSection) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(key.toUpperCase()).append(':').append('\n');
                appendSection(sb, (ConfigurationSection) val, depth + 1);
            } else if (val instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) val;
                if (sb.length() > 0) sb.append('\n');
                sb.append(key).append(':').append('\n');
                for (Object item : list) {
                    sb.append(indent).append("- ").append(String.valueOf(item)).append('\n');
                }
            } else {
                if (sb.length() > 0) sb.append('\n');
                sb.append(key).append(':').append(' ').append(String.valueOf(val));
            }
        }
    }

    private EventPriority parsePriority(String raw) {
        if (raw == null) return EventPriority.LOWEST;
        try {
            return EventPriority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid chat_listener_priority '" + raw + "', defaulting to LOWEST");
            return EventPriority.LOWEST;
        }
    }

    public void saveDefaultConfig() {
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveDefaultConfig();
    }

    public String getNpcPrompt(String npcName, Player player) {
        FileConfiguration config = plugin.getConfig();

        // Check if the NPC is specifically configured in config.yml
        if (!config.contains("npcs." + npcName)) {
            return null;  // Return null if the NPC is not configured
        }

        // Fetch default prompt from config
        String defaultPrompt = config.getString("default_prompt", "You are an intelligent NPC on a Minecraft Java server.");

        // Fetch prompt for NPC from config
        String npcSpecificPrompt = config.getString("npcs." + npcName);

        // Combine the default prompt with the NPC prompt
        String combinedPrompt = defaultPrompt + (npcSpecificPrompt.isEmpty() ? "" : " " + npcSpecificPrompt);

        // If PAPI is installed, parse the prompt
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, combinedPrompt);
        }

        // Return the combined prompt
        return combinedPrompt;
    }

    // Get in game time from config string
    private Duration parseMinecraftDuration(String durationString) {
        Pattern pattern = Pattern.compile("(?:(\\d+)w)?\\s*(?:(\\d+)d)?\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?");
        Matcher matcher = pattern.matcher(durationString);

        if (matcher.matches()) {
            long weeks = matcher.group(1) != null ? Long.parseLong(matcher.group(1)) : 0;
            long days = matcher.group(2) != null ? Long.parseLong(matcher.group(2)) : 0;
            long hours = matcher.group(3) != null ? Long.parseLong(matcher.group(3)) : 0;
            long minutes = matcher.group(4) != null ? Long.parseLong(matcher.group(4)) : 0;

            // Convert Minecraft days to real-time minutes
            return Duration.ofMinutes(minutes)
                    .plusHours(hours)
                    .plusMinutes(days * 20)
                    .plusMinutes(weeks * 7 * 20);
        }
        throw new IllegalArgumentException("Invalid duration format: " + durationString);
    }

    public void toggleDebugMode() {
        debugMode = !debugMode; // Toggle debug mode
        Level loggerLevel = debugMode ? Level.INFO : Level.WARNING;
        logger.setLevel(loggerLevel);

        // Save the updated debug mode to the config
        FileConfiguration config = plugin.getConfig();
        config.set("debug_mode", debugMode);
        plugin.saveConfig();

        logger.info("Debug mode is now " + (debugMode ? "enabled" : "disabled"));
    }

    public void printConfigToConsole() {
        // ANSI color codes
        String RESET = "\u001B[0m";
        String YELLOW = "\u001B[33m";
        String DARK_BLUE = "\u001B[34m"; // Dark Blue ANSI code
        String LIGHT_BLUE = "\u001B[94m"; // Light Blue ANSI code

        String asciiArt =
                "\n" + LIGHT_BLUE +
                        "    _          _    ___ ___ _____ \n" + LIGHT_BLUE +
                        "   /_\\  _ _ __| |_ / __| _ \\_   _|\n" + LIGHT_BLUE +
                        "  / _ \\| '_/ _| ' \\ (_ |  _/ | |  \n" + LIGHT_BLUE +
                        " /_/ \\_\\_| \\__|_||_\\___|_|   |_|  \n" + LIGHT_BLUE +
                        "                                  \n"
                        + RESET + DARK_BLUE + "--- Settings ---\n" + RESET +
                        YELLOW + "Debug Mode: " + debugMode + "\n" + YELLOW +
                        "ChatGPT Engine: " + chatGptEngine + "\n" + YELLOW +
                        "Max Response Length: " + maxResponseLength + " tokens" + "\n" + YELLOW +
                        "Base Prompt: " + defaultPrompt + "\n" + YELLOW +
                        "NPC Memory Duration: " + plugin.getConfig().getString("npc_memory_duration") + "\n" + YELLOW +
                        "Conversation Timeout: " + npcChatTimeoutMillis + "\n" + YELLOW +
                        "Split Long Chats: " + shouldSplitLongMsg + "\n";

        logger.info(LIGHT_BLUE + asciiArt + RESET);

    }

}
