package com.harness.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Built-in tool for saving user preferences to long-term memory.
 * Called by the LLM when the user explicitly asks to remember something.
 *
 * Uses ThreadLocal for userId/sessionId context, set by AgentOrchestrator before each run.
 * The actual save logic is provided via a callback to avoid module dependency issues.
 */
public class SavePreferenceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SavePreferenceTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    /**
     * Callback interface for saving preferences.
     * Arguments: userId, category, content, sessionId
     */
    @FunctionalInterface
    public interface PreferenceSaver {
        void save(String userId, String category, String content, String sessionId);
    }

    private final PreferenceSaver saver;

    public SavePreferenceTool(PreferenceSaver saver) {
        this.saver = saver;
    }

    public static void setCurrentUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static void setCurrentSessionId(String sessionId) {
        CURRENT_SESSION_ID.set(sessionId);
    }

    public static void clearContext() {
        CURRENT_USER_ID.remove();
        CURRENT_SESSION_ID.remove();
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "save_preference",
                "Save a user preference to long-term memory. Use when the user explicitly asks to remember something, " +
                "or when the user states a clear preference (e.g. 'remember I prefer...', 'always reply in...', 'I like...'). " +
                "Categories: language, tone, code_style, format, domain, workflow, other.",
                mapper.createObjectNode()
                        .put("type", "object")
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("properties",
                                mapper.createObjectNode()
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("category",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "Preference category: language, tone, code_style, format, domain, workflow, other"))
                                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("content",
                                                mapper.createObjectNode()
                                                        .put("type", "string")
                                                        .put("description", "The preference description. Be specific and concise.")))
                        .<com.fasterxml.jackson.databind.node.ObjectNode>set("required",
                                mapper.createArrayNode().add("category").add("content"))
        );
    }

    @Override
    public String execute(JsonNode arguments) {
        String category = arguments.has("category") ? arguments.get("category").asText().trim() : null;
        String content = arguments.has("content") ? arguments.get("content").asText().trim() : null;

        if (category == null || category.isEmpty()) {
            return "ERROR: 'category' is required";
        }
        if (content == null || content.isEmpty()) {
            return "ERROR: 'content' is required";
        }

        String userId = CURRENT_USER_ID.get();
        if (userId == null || userId.isEmpty()) {
            return "ERROR: No user context available. Cannot save preference without authentication.";
        }

        String sessionId = CURRENT_SESSION_ID.get();

        try {
            saver.save(userId, category.toLowerCase(), content, sessionId);
            log.info("Saved preference for user {}: {} = {}", userId, category, content);
            return "Preference saved: " + category + " = " + content;
        } catch (Exception e) {
            log.error("Failed to save preference: {}", e.getMessage(), e);
            return "ERROR: Failed to save preference: " + e.getMessage();
        }
    }
}
