package com.harness.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.PreferenceKeyRegistry;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;

import java.util.Objects;

/** Type-specific model entrypoint backed by the shared memory save service. */
public final class SaveMemoryTool implements Tool {
    public static final String OPERATION_NAME = "save_operation_playbook";
    public static final String PREFERENCE_NAME = "save_user_preference";
    public static final String EPISODE_NAME = "save_user_episode";

    private final KnowledgeConceptType type;
    private final MemorySaveService service;
    private final ObjectMapper mapper;
    private final PreferenceKeyRegistry preferenceKeys;
    private final String name;

    public SaveMemoryTool(KnowledgeConceptType type, MemorySaveService service, ObjectMapper mapper,
                          PreferenceKeyRegistry preferenceKeys) {
        this.type = Objects.requireNonNull(type);
        this.service = Objects.requireNonNull(service);
        this.mapper = Objects.requireNonNull(mapper);
        this.preferenceKeys = Objects.requireNonNull(preferenceKeys);
        this.name = nameFor(type);
    }

    public static String nameFor(KnowledgeConceptType type) {
        return switch (type) {
            case OPERATION_PLAYBOOK -> OPERATION_NAME;
            case USER_PREFERENCE -> PREFERENCE_NAME;
            case USER_EPISODE -> EPISODE_NAME;
            default -> throw new IllegalArgumentException("Not a memory type: " + type);
        };
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        ObjectNode fields = schema.putObject("properties");
        var required = schema.putArray("required");
        String description;
        switch (type) {
            case OPERATION_PLAYBOOK -> {
                fields.set("playbookKey", textSchema(256, "Stable key for the same reusable procedure."));
                fields.set("title", textSchema(512, "Procedure title."));
                fields.set("summary", textSchema(2048, "Searchable Wiki summary."));
                fields.set("procedure", textSchema(16000, "Applicable conditions, steps, pitfalls and verification."));
                fields.set("expiresAt", textSchema(64, "Optional ISO-8601 expiry time."));
                required.add("playbookKey").add("title").add("summary").add("procedure");
                description = "Save an Agent procedure learned from observed work for similar future tasks. "
                        + "Example: verify knowledge-base evidence before answering a domain question. "
                        + "A user's personal response style belongs in save_user_preference. "
                        + "Exclude user-specific facts, guesses, secrets and raw tool output.";
            }
            case USER_PREFERENCE -> {
                fields.set("preferenceKey", textSchema(256, "Use a registered key when applicable: "
                        + preferenceKeys.definitions().keySet().stream()
                                .filter(key -> !PreferenceKeyRegistry.OTHER_KEY.equals(key)).sorted().toList()
                        + ". Otherwise use a specific stable custom key; do not send 'other'."));
                fields.set("preferenceStatement", textSchema(4096, "Lasting preference explicitly expressed by this user."));
                ObjectNode tags = fields.putObject("activationTags").put("type", "array")
                        .put("minItems", 1).put("uniqueItems", true);
                var allowed = tags.putObject("items").put("type", "string").putArray("enum");
                preferenceKeys.activationTagRegistry().tags().stream().sorted().forEach(allowed::add);
                tags.put("description", "Required for a custom preferenceKey; registered keys use their default tags.");
                fields.set("expiresAt", textSchema(64, "Optional ISO-8601 expiry for registered keys only."));
                required.add("preferenceKey").add("preferenceStatement");
                description = "Save a lasting personal habit or response constraint explicitly expressed by this user. "
                        + "Example: 'Please answer me in Chinese from now on.' "
                        + "An Agent procedure learned from work belongs in save_operation_playbook. "
                        + "Preferences are injected from MySQL before later model calls.";
            }
            case USER_EPISODE -> {
                fields.set("episodeKey", textSchema(256, "Stable key for this concrete event."));
                fields.set("title", textSchema(512, "Event title."));
                fields.set("summary", textSchema(2048, "Searchable Wiki summary."));
                fields.set("content", textSchema(16000, "What happened, with context, decisions and outcome."));
                fields.set("eventTime", textSchema(64, "Optional ISO-8601 time when the event happened."));
                fields.set("expiresAt", textSchema(64, "Optional ISO-8601 expiry time."));
                required.add("episodeKey").add("title").add("summary").add("content");
                description = "Save a concrete event that happened to this user. "
                        + "Example: the user completed a picture-book lesson and chose watercolor on a specific date. "
                        + "A reusable Agent procedure belongs in save_operation_playbook; a lasting personal preference "
                        + "belongs in save_user_preference.";
            }
            default -> throw new IllegalStateException("Unsupported memory type: " + type);
        }
        return new ToolSpec(name, description, schema, ToolCapability.MUTATION);
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            ObjectNode values = mapper.createObjectNode();
            switch (type) {
                case OPERATION_PLAYBOOK -> {
                    copy(values, arguments, "playbookKey", "memoryKey", 256);
                    copy(values, arguments, "title", "title", 512);
                    copy(values, arguments, "summary", "summary", 2048);
                    copy(values, arguments, "procedure", "content", 16000);
                }
                case USER_PREFERENCE -> {
                    String key = copy(values, arguments, "preferenceKey", "memoryKey", 256);
                    values.put("title", key);
                    values.put("summary", key);
                    copy(values, arguments, "preferenceStatement", "content", 4096);
                    if (arguments.has("activationTags")) values.set("activationTags", arguments.get("activationTags"));
                }
                case USER_EPISODE -> {
                    copy(values, arguments, "episodeKey", "memoryKey", 256);
                    copy(values, arguments, "title", "title", 512);
                    copy(values, arguments, "summary", "summary", 2048);
                    copy(values, arguments, "content", "content", 16000);
                    if (arguments.has("eventTime")) values.set("eventTime", arguments.get("eventTime"));
                }
                default -> throw new IllegalStateException("Unsupported memory type: " + type);
            }
            if (arguments.has("expiresAt")) values.set("expiresAt", arguments.get("expiresAt"));
            return service.save(type, values, name);
        } catch (ToolExecutionException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new ToolExecutionException(name, "Memory save failed: " + failure.getMessage());
        }
    }

    private static String copy(ObjectNode values, JsonNode arguments, String source, String target, int limit) {
        String value = MemorySaveService.text(arguments, source, limit);
        values.put(target, value);
        return value;
    }

    private ObjectNode textSchema(int limit, String description) {
        return mapper.createObjectNode().put("type", "string").put("minLength", 1)
                .put("maxLength", limit).put("description", description);
    }
}
