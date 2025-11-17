package me.xidentified.archgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.xidentified.archgpt.context.ContextManager;
import me.xidentified.archgpt.utils.ArchGPTConstants;
import me.xidentified.archgpt.utils.LocaleUtils;
import net.citizensnpcs.api.npc.NPC;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

public class ChatRequestHandler {
    private final ArchGPT plugin;
    private final ContextManager contextManager;

    public ChatRequestHandler(ArchGPT plugin) {
        this.plugin = plugin;
        this.contextManager = new ContextManager(plugin);
    }

    public enum RequestType {
        GREETING,
        CONVERSATION
    }

    public CompletableFuture<Object> processMCPRequest(Player player, NPC npc, String message, 
                                                    RequestType requestType, List<JsonObject> conversationState) {
        UUID playerUUID = player.getUniqueId();
        final String playerMessageFinal = message;
        final List<JsonObject> convoStateFinal = (conversationState != null) ? conversationState : new java.util.ArrayList<>();
        
        // Use a CompletableFuture to handle the async operation
        CompletableFuture<JsonObject> contextFuture = new CompletableFuture<>();
        
        // Schedule context gathering on the main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                JsonObject context = contextManager.getOrganizedContext(player, npc, requestType);
                contextFuture.complete(context);
            } catch (Exception e) {
                contextFuture.completeExceptionally(e);
            }
        });

        return contextFuture.thenCompose(context -> {
            plugin.playerSemaphores.putIfAbsent(playerUUID, new Semaphore(1));

            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Acquire the semaphore for this specific player
                    Semaphore semaphore = plugin.playerSemaphores.get(playerUUID);
                    semaphore.acquire();
                    
                    // Branch by mode: MCP or direct OpenAI
                    if (plugin.getConfigHandler().isUseMcp()) {
                        // Build MCP request using the context gathered on the main thread
                        JsonObject mcpRequest = buildMCPRequest(context, playerMessageFinal, convoStateFinal, requestType);
                        plugin.debugLog("MCP Request: " + mcpRequest.toString());

                        // Log the request for debugging
                        plugin.debugLog("Sending request to MCP server with provider: " + 
                        plugin.getConfigHandler().getMcpProvider() + ", model: " + plugin.getConfigHandler().getMcpModel());

                        // Send to MCP server
                        HttpRequest request = buildMCPHttpRequest(mcpRequest.toString());
                        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                        
                        int statusCode = response.statusCode();
                        plugin.debugLog("Received response from MCP server, Status Code: " + statusCode);

                        if (statusCode == 200) {
                            String jsonResponse = response.body();
                            JsonObject responseObject = JsonParser.parseString(jsonResponse).getAsJsonObject();
                            return extractAssistantResponseText(responseObject);
                        } else {
                            plugin.getLogger().severe("MCP Server Error: Status Code " + statusCode + " - " + response.body());
                            throw new RuntimeException("MCP Server Error: Status Code " + statusCode);
                        }
                    } else {
                        // Direct OpenAI mode
                        String openAiBody = buildOpenAIChatRequestBody(context, playerMessageFinal, convoStateFinal, requestType);
                        HttpRequest request = buildOpenAIHttpRequest(openAiBody);
                        HttpResponse<String> response = plugin.getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                        int statusCode = response.statusCode();
                        plugin.debugLog("Received response from OpenAI, Status Code: " + statusCode);
                        if (statusCode == 200) {
                            return extractOpenAIResponseText(response.body());
                        } else {
                            String body = response.body();
                            plugin.getLogger().severe("OpenAI API Error: Status Code " + statusCode + " - " + body);
                            throw new RuntimeException("OpenAI API Error: Status Code " + statusCode);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread was interrupted: " + e.getMessage());
                } catch (IOException | RuntimeException e) {
                    String errClass = e.getClass().getName();
                    String msg = e.getMessage();
                    plugin.getLogger().severe("Request Failed [" + errClass + "]: " + msg);
                    throw new RuntimeException("Request Failed: " + (msg != null ? msg : errClass));
                } finally {
                    // Ensure the semaphore is released for this player
                    Semaphore semaphore = plugin.playerSemaphores.get(playerUUID);
                    if (semaphore != null) {
                        semaphore.release();
                    }
                }
            }).thenCompose(assistantResponseText -> {
                // Check if translation is needed, but be defensive about locale formatting
                try {
                    String playerLocale = LocaleUtils.getPlayerLocale(player);
                    plugin.debugLog("Player locale read as: " + playerLocale);
                    String langCode = (playerLocale != null && playerLocale.length() >= 2)
                            ? playerLocale.substring(0, 2)
                            : "en";

                    if (!langCode.equalsIgnoreCase("en")) {
                        String targetLang = langCode;
                        return plugin.getTranslationService().translateText(assistantResponseText, targetLang)
                                .thenApply(translatedText -> translatedText != null ? translatedText : assistantResponseText);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Locale/translation handling issue: " + e.getMessage());
                }
                plugin.debugLog("Final Processed Response: " + assistantResponseText);
                return CompletableFuture.completedFuture(assistantResponseText);

            }).exceptionally(ex -> {
                // Handle exceptions - log the error and keep the conversation alive with a fallback reply
                plugin.getLogger().severe("Error processing MCP request: " + ex.getMessage());
                return "Sorry, I had a little hiccup understanding that. Could you say it again?";
            });
        }).thenApply(assistantResponseText -> {
            // Process the response and prepare final result
            String safeText = assistantResponseText != null ? assistantResponseText : "";
            String response = safeText.trim();
            if (response.isEmpty()) {
                response = "...";
            }

            if (requestType == RequestType.GREETING) {
                return response;
            } else {
                // Additional processing for non-greeting requests
                String sanitizedPlayerMessage = playerMessageFinal;

                // convoStateFinal is effectively final; we can mutate its contents
                if (convoStateFinal.size() > ArchGPTConstants.MAX_CONVERSATION_STATE_SIZE * 2) {
                    convoStateFinal.subList(0, 2).clear();
                }

                JsonObject userMessageJson = new JsonObject();
                userMessageJson.addProperty("role", "user");
                userMessageJson.addProperty("content", sanitizedPlayerMessage);
                convoStateFinal.add(userMessageJson);

                JsonObject assistantMessageJson = new JsonObject();
                assistantMessageJson.addProperty("role", "assistant");
                assistantMessageJson.addProperty("content", response);
                convoStateFinal.add(assistantMessageJson);

                return Pair.of(response, convoStateFinal);
            }
        });
    }

    private JsonObject buildMCPRequest(JsonObject context, String message, 
                                     List<JsonObject> conversationState, RequestType requestType) {
        JsonObject mcpRequest = new JsonObject();
        
        // Add context
        mcpRequest.add("context", context);
        
        // Add message
        mcpRequest.addProperty("message", message);
        
        // Build a system message from context so MCP providers that ignore `context` still get the info
        StringBuilder sys = new StringBuilder();
        sys.append("Context -> ");
        if (context.has("environment")) sys.append("Environment: ").append(context.get("environment").getAsString()).append(" | ");
        if (context.has("player")) sys.append("Player: ").append(context.get("player").getAsString()).append(" | ");
        if (context.has("npc")) sys.append("NPC: ").append(context.get("npc").getAsString()).append(" | ");
        if (context.has("knowledge")) sys.append("Knowledge: ").append(context.get("knowledge").getAsString()).append(" | ");

        String systemContent = sys.toString();
        // Basic safety cap to avoid overlong system messages
        int maxLen = 6000; // chars; conservative cap
        if (systemContent.length() > maxLen) {
            systemContent = systemContent.substring(0, maxLen);
        }

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemContent);

        // Build conversation history, ensuring the system message appears first once
        JsonArray history = new JsonArray();
        boolean hasSystemAlready = false;
        if (conversationState != null && !conversationState.isEmpty()) {
            // Check if the first message is already a system message
            try {
                JsonObject first = conversationState.get(0);
                if (first != null && first.has("role") && "system".equalsIgnoreCase(first.get("role").getAsString())) {
                    hasSystemAlready = true;
                }
            } catch (Exception ignored) {}

            for (JsonObject msg : conversationState) {
                history.add(msg);
            }
        }

        if (!hasSystemAlready) {
            // Prepend system message: create a new array with system first
            JsonArray newHistory = new JsonArray();
            newHistory.add(systemMsg);
            for (int i = 0; i < history.size(); i++) {
                newHistory.add(history.get(i));
            }
            history = newHistory;
        }

        if (history.size() > 0) {
            mcpRequest.add("conversation_history", history);
        }
        
        // Add request type
        mcpRequest.addProperty("request_type", requestType.name());
        
        // Add provider information from config using the config handler
        mcpRequest.addProperty("provider", plugin.getConfigHandler().getMcpProvider());
        mcpRequest.addProperty("model", plugin.getConfigHandler().getMcpModel());
        mcpRequest.addProperty("max_tokens", plugin.getConfigHandler().getMcpMaxTokens());
        
        return mcpRequest;
    }

    private String buildOpenAIChatRequestBody(JsonObject context, String message,
                                              List<JsonObject> conversationState, RequestType requestType) {
        JsonObject root = new JsonObject();
        root.addProperty("model", plugin.getConfigHandler().getChatGptEngine());
        root.addProperty("max_tokens", plugin.getConfigHandler().getMaxResponseLength());

        JsonArray messages = new JsonArray();

        // Always add a system message from context so knowledge and other context are present
        StringBuilder sys = new StringBuilder();

        // 0) Prepend default prompt/instructions if provided, and give the model clear guidance
        String defaultPrompt = plugin.getConfigHandler().getDefaultPrompt();
        if (defaultPrompt != null && !defaultPrompt.isBlank()) {
            sys.append("Instruction: ").append(defaultPrompt).append(" | ");
        }
        sys.append("Guidelines: Use the Knowledge section as the canonical source of server facts (rules, staff, FAQs, links). If the answer isn't in Knowledge, say you don't know. | ");

        // 1) Append contextual fields
        sys.append("Context -> ");
        if (context.has("environment")) sys.append("Environment: ").append(context.get("environment").getAsString()).append(" | ");
        if (context.has("player")) sys.append("Player: ").append(context.get("player").getAsString()).append(" | ");
        if (context.has("npc")) sys.append("NPC: ").append(context.get("npc").getAsString()).append(" | ");
        if (context.has("knowledge")) sys.append("Knowledge: ").append(context.get("knowledge").getAsString()).append(" | ");
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", sys.toString());
        messages.add(sysMsg);

        // If we have conversation state, map it after the system message
        if (conversationState != null && !conversationState.isEmpty()) {
            for (JsonObject msg : conversationState) {
                JsonObject m = new JsonObject();
                m.addProperty("role", msg.get("role").getAsString());
                m.addProperty("content", msg.get("content").getAsString());
                messages.add(m);
            }
        }

        // Current user message last
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", message);
        messages.add(userMsg);

        root.add("messages", messages);
        return root.toString();
    }

    private String extractOpenAIResponseText(String responseBody) {
        try {
            JsonObject obj = JsonParser.parseString(responseBody).getAsJsonObject();
            if (obj.has("choices")) {
                var choices = obj.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject first = choices.get(0).getAsJsonObject();
                    if (first.has("message")) {
                        JsonObject msg = first.getAsJsonObject("message");
                        if (msg.has("content")) {
                            return msg.get("content").getAsString().trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse OpenAI response: " + e.getMessage());
        }
        plugin.getLogger().warning("Invalid response structure from OpenAI");
        return "I'm having trouble processing that right now.";
    }

    private HttpRequest buildOpenAIHttpRequest(String jsonRequestBody) {
        String apiKey = plugin.getConfigHandler().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("Missing OpenAI API key. Set 'api_key' in config.yml or enable MCP.");
        }
        URI uri = URI.create("https://api.openai.com/v1/chat/completions");
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody, StandardCharsets.UTF_8))
                .build();
    }

    private String extractAssistantResponseText(JsonObject responseObject) {
        if (responseObject.has("output")) {
            return responseObject.get("output").getAsString().trim();
        }
        plugin.getLogger().warning("Invalid response structure from MCP server");
        plugin.debugLog("MCP server response object: " + responseObject);
        return "I'm having trouble processing that right now.";
    }

    private HttpRequest buildMCPHttpRequest(String jsonRequestBody) {
        // Use the config handler to get the MCP server URL
        String mcpServerUrl = plugin.getConfigHandler().getMcpServerUrl();
        URI uri = URI.create(mcpServerUrl);

        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody, StandardCharsets.UTF_8))
                .build();
    }
}