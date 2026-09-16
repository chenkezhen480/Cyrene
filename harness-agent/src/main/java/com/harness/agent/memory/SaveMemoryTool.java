package com.harness.agent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.harness.agent.knowledge.KnowledgeToolRuntimeContext;
import com.harness.core.exception.ToolExecutionException;
import com.harness.core.knowledge.*;
import com.harness.core.model.ToolCapability;
import com.harness.core.model.ToolSpec;
import com.harness.tool.Tool;
import com.harness.tool.knowledge.WikiIdentityResolver;
import com.harness.tool.knowledge.authority.*;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Conversation-driven memory capture; ownership never comes from model arguments. */
public final class SaveMemoryTool implements Tool {
    public static final String TOOL_NAME = "save_memory";
    private final KnowledgeRepository repository;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Runnable signalIndex;
    private final KnowledgeExtractionSanitizer sanitizer;
    private final PreferenceKeyRegistry preferenceKeys;
    private final WikiIdentityResolver identityResolver;

    public SaveMemoryTool(KnowledgeRepository repository, ObjectMapper mapper,
                          Clock clock, Runnable signalIndex) {
        this(repository, mapper, clock, signalIndex, PreferenceKeyRegistry.standard(), null);
    }

    public SaveMemoryTool(KnowledgeRepository repository, ObjectMapper mapper,
                          Clock clock, Runnable signalIndex, PreferenceKeyRegistry preferenceKeys) {
        this(repository, mapper, clock, signalIndex, preferenceKeys, null);
    }

    public SaveMemoryTool(KnowledgeRepository repository, ObjectMapper mapper,
                          Clock clock, Runnable signalIndex, PreferenceKeyRegistry preferenceKeys,
                          WikiIdentityResolver identityResolver) {
        this.repository = Objects.requireNonNull(repository);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.signalIndex = Objects.requireNonNull(signalIndex);
        this.sanitizer = new KnowledgeExtractionSanitizer();
        this.preferenceKeys = Objects.requireNonNull(preferenceKeys);
        this.identityResolver = identityResolver;
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("memoryType").put("type", "string").putArray("enum")
                .add("USER_PREFERENCE").add("USER_EPISODE").add("OPERATION_PLAYBOOK");
        properties.set("memoryKey", textSchema(256));
        properties.set("title", textSchema(512));
        properties.set("summary", textSchema(2048));
        properties.set("content", textSchema(16000));
        properties.set("eventTime", textSchema(64));
        properties.set("expiresAt", textSchema(64));
        ObjectNode activationTags = properties.putObject("activationTags").put("type", "array")
                .put("minItems", 1).put("uniqueItems", true);
        var tagEnum = activationTags.putObject("items").put("type", "string").putArray("enum");
        preferenceKeys.activationTagRegistry().tags().stream().sorted().forEach(tagEnum::add);
        activationTags.put("description", "For USER_PREFERENCE only. Required for a custom memoryKey; "
                + "registered keys use their configured activation tags.");
        schema.putArray("required").add("memoryType").add("memoryKey")
                .add("title").add("summary").add("content");
        return new ToolSpec(TOOL_NAME,
                "The Agent decides proactively during conversation whether durable memory exists. "
                + "USER_PREFERENCE records the user's lasting habits, preferences or response constraints; "
                + "USER_EPISODE answers what happened; OPERATION_PLAYBOOK answers how to handle a similar situation in the future. "
                + "A conversation may produce zero, one or multiple memory types; call separately for each applicable type. "
                + "Never force a memory just to fill a type. USER_EPISODE records a concrete "
                + "user event and its context/outcome; OPERATION_PLAYBOOK records an observed reusable "
                + "Agent procedure, conditions, steps and verification, without user-specific facts. "
                + "Use a stable memoryKey to revise the same memory; scoped semantic candidates are checked before "
                + "creating a different episode or playbook. Do not save secrets, speculation or raw tool output. "
                + "Preferences are saved only in MySQL and injected before later model calls, without Wiki/vector indexing. "
                + "For preferences, content is the lasting preference statement; registered memoryKeys are "
                + preferenceKeys.definitions().keySet().stream().sorted().toList()
                + ". Use a specific stable custom memoryKey and activationTags for any other preference; "
                + "custom content is limited to 2048 characters, registered content to 4096. "
                + "For episodes/playbooks, summary is the searchable Wiki entry and content is the memory vector block. "
                + "Optional eventTime/expiresAt are ISO-8601 instants; preferences do not accept eventTime, "
                + "and custom preferences do not accept expiresAt.",
                schema, ToolCapability.MUTATION);
    }

    @Override
    public String execute(JsonNode arguments) {
        try {
            KnowledgeToolRuntimeContext context = KnowledgeToolRuntimeContext.requireCurrent(TOOL_NAME);
            if (!context.authorizedTools().contains(TOOL_NAME)) {
                throw new SecurityException("Memory writing is not authorized for this run");
            }
            KnowledgeConceptType type = KnowledgeConceptType.valueOf(text(arguments, "memoryType", 64));
            if (!type.isMemory()) {
                throw new IllegalArgumentException("Only user preferences, user episodes and operation playbooks are supported");
            }
            String userId = type.isUserOwned() ? context.userId() : null;
            if (type.isUserOwned() && userId == null) {
                throw new SecurityException("User memory requires an authenticated user");
            }
            String key = text(arguments, "memoryKey", 256);
            String title = text(arguments, "title", 512);
            String summary = text(arguments, "summary", 2048);
            String content = text(arguments, "content", 16000);
            if (sanitizer.containsSensitiveMaterial(key + "\n" + title + "\n" + summary + "\n" + content)) {
                throw new IllegalArgumentException("Memory must not contain credentials or secrets");
            }
            boolean preference = type == KnowledgeConceptType.USER_PREFERENCE;
            String logicalKey = preference && preferenceKeys.find(key).isEmpty()
                    ? PreferenceKeyRegistry.OTHER_KEY : key;
            String conceptId = preference
                    ? KnowledgeIdentity.preferenceConceptId(context.tenantId(), userId, logicalKey)
                    : KnowledgeIdentity.sha256(mapper.writeValueAsString(
                            java.util.Arrays.asList(type.name(), context.tenantId(), userId, key)));
            KnowledgeHead previous = repository.findById(conceptId).orElse(null);
            if (!preference && previous == null && identityResolver != null) {
                var resolution = identityResolver.resolve(
                        type, context.tenantId(), userId,
                        type == KnowledgeConceptType.USER_EPISODE
                                ? KnowledgeNamespaceType.USER_MEMORY
                                : KnowledgeNamespaceType.OPERATION_MEMORY,
                        null, key, new WikiIdentityResolver.Draft(title, summary, content),
                        WikiIdentityResolver.RevisionMode.SYNTHESIZE).orElse(null);
                if (resolution != null) {
                    previous = resolution.previous();
                    conceptId = previous.concept().id();
                    logicalKey = previous.concept().logicalKey();
                    title = resolution.draft().title();
                    summary = resolution.draft().summary();
                    content = resolution.draft().content();
                }
            }
            Instant now = clock.instant();
            Instant expiresAt = instant(arguments, "expiresAt", null);
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                throw new IllegalArgumentException("expiresAt must be in the future");
            }
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("captureMode", "conversation");
            if (preference) {
                if (arguments.hasNonNull("eventTime")) {
                    throw new IllegalArgumentException("User preferences do not accept eventTime");
                }
                if (PreferenceKeyRegistry.OTHER_KEY.equals(logicalKey) && expiresAt != null) {
                    throw new IllegalArgumentException("Custom preferences do not accept expiresAt");
                }
                metadata.put("preference", preferenceData(logicalKey, key, content, arguments, previous));
            } else if (arguments.has("activationTags")) {
                throw new IllegalArgumentException("activationTags is only supported for user preferences");
            }
            if (type == KnowledgeConceptType.USER_EPISODE) {
                Instant eventTime = previous == null ? now : Instant.parse(
                        previous.currentRevision().metadata().get("eventTime").toString());
                metadata.put("eventTime", instant(arguments, "eventTime", eventTime).toString());
            }
            long version = previous == null ? 0 : previous.concept().version();
            String hash = KnowledgeIdentity.sha256(mapper.writeValueAsBytes(
                    java.util.Arrays.asList(title, summary, content, metadata,
                            expiresAt == null ? null : expiresAt.toString())));
            if (previous != null && previous.concept().status() == KnowledgeStatus.STABLE
                    && hash.equals(previous.currentRevision().contentHash())) {
                return mapper.writeValueAsString(java.util.Map.of(
                        "status", "unchanged", "conceptId", conceptId));
            }
            KnowledgeRevision revision = new KnowledgeRevision(
                    KnowledgeIdentity.revisionId(conceptId, version + 1, hash), conceptId,
                    version + 1, title, summary, content, TOOL_NAME, now, hash, metadata, now);
            KnowledgeConcept concept = new KnowledgeConcept(conceptId, context.tenantId(), userId,
                    type.isUserOwned() ? KnowledgeNamespaceType.USER_MEMORY
                            : KnowledgeNamespaceType.OPERATION_MEMORY,
                    null, type, logicalKey, KnowledgeStatus.STABLE, revision.id(), version + 1,
                    expiresAt, previous == null ? now : previous.concept().createdAt(), now);
            List<KnowledgeIndexTask> tasks = preference ? List.of() : List.of(new KnowledgeIndexTask(null, conceptId, revision.id(),
                    KnowledgeIndexOperation.UPSERT_CURRENT, KnowledgeIndexTaskStatus.PENDING,
                    0, now, null, null, null, now));
            repository.commitChanges(List.of(new KnowledgeRevisionChange(concept, version, revision,
                    List.of(), List.of(), List.of(), tasks)));
            if (!preference) signalIndex.run();
            return mapper.writeValueAsString(java.util.Map.of("status", preference ? "saved" : "pending",
                    "conceptId", conceptId, "revisionId", revision.id(), "memoryType", type.name()));
        } catch (Exception exception) {
            throw new ToolExecutionException(TOOL_NAME, "Memory save failed: " + exception.getMessage());
        }
    }

    private PreferenceRevisionData preferenceData(String preferenceKey, String memoryKey, String content,
                                                   JsonNode arguments, KnowledgeHead previous) {
        var tags = preferenceKeys.activationTagsFor(preferenceKey);
        if (arguments.has("activationTags")) {
            JsonNode raw = arguments.get("activationTags");
            if (!raw.isArray()) throw new IllegalArgumentException("activationTags must be an array");
            var requested = new java.util.ArrayList<String>();
            for (JsonNode tag : raw) {
                if (!tag.isTextual()) throw new IllegalArgumentException("activationTags must contain strings");
                requested.add(tag.asText());
            }
            tags = preferenceKeys.validateActivationTags(preferenceKey, requested);
        }
        if (!PreferenceKeyRegistry.OTHER_KEY.equals(preferenceKey)) {
            return new PreferenceRevisionData(preferenceKey, content, tags, List.of());
        }
        tags = preferenceKeys.validateActivationTags(preferenceKey, tags);
        LinkedHashMap<String, PreferenceItem> items = new LinkedHashMap<>();
        if (previous != null) {
            Object raw = previous.currentRevision().metadata().get("preference");
            if (raw == null) throw new IllegalStateException("Current preference metadata is missing");
            var stored = mapper.convertValue(raw, PreferenceRevisionData.class);
            stored.items().forEach(item -> items.put(item.itemId(), item));
        }
        String itemId = KnowledgeIdentity.sha256(memoryKey);
        items.put(itemId, new PreferenceItem(itemId, content, tags));
        return new PreferenceRevisionData(preferenceKey, null, java.util.Set.of(), List.copyOf(items.values()));
    }

    private ObjectNode textSchema(int limit) {
        return mapper.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", limit);
    }

    private static String text(JsonNode arguments, String field, int limit) {
        JsonNode value = arguments == null ? null : arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > limit) {
            throw new IllegalArgumentException(field + " must contain 1 to " + limit + " characters");
        }
        return value.asText().trim();
    }

    private static Instant instant(JsonNode arguments, String field, Instant defaultValue) {
        return arguments.hasNonNull(field) ? Instant.parse(text(arguments, field, 64)) : defaultValue;
    }
}
