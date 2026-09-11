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

    public SaveMemoryTool(KnowledgeRepository repository, ObjectMapper mapper,
                          Clock clock, Runnable signalIndex) {
        this.repository = Objects.requireNonNull(repository);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.signalIndex = Objects.requireNonNull(signalIndex);
        this.sanitizer = new KnowledgeExtractionSanitizer();
    }

    @Override
    public ToolSpec spec() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object")
                .put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("memoryType").put("type", "string").putArray("enum")
                .add("USER_EPISODE").add("OPERATION_PLAYBOOK");
        properties.set("memoryKey", textSchema(256));
        properties.set("title", textSchema(512));
        properties.set("summary", textSchema(2048));
        properties.set("content", textSchema(16000));
        properties.set("eventTime", textSchema(64));
        properties.set("expiresAt", textSchema(64));
        schema.putArray("required").add("memoryType").add("memoryKey")
                .add("title").add("summary").add("content");
        return new ToolSpec(TOOL_NAME,
                "The Agent decides proactively during conversation whether durable memory exists. "
                + "USER_EPISODE answers what happened; OPERATION_PLAYBOOK answers how to handle a similar situation in the future. "
                + "A conversation may produce zero memories, one memory, or both types; when both apply, call this tool separately for each type. "
                + "Never force a memory just to fill a type. USER_EPISODE records a concrete "
                + "user event and its context/outcome; OPERATION_PLAYBOOK records an observed reusable "
                + "Agent procedure, conditions, steps and verification, without user-specific facts. "
                + "Use a stable memoryKey to revise the same memory. Do not save preferences, secrets, "
                + "speculation or raw tool output. summary is the searchable Wiki entry; content is the "
                + "memory vector block. Optional eventTime/expiresAt are ISO-8601 instants.",
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
            if (type != KnowledgeConceptType.USER_EPISODE && type != KnowledgeConceptType.OPERATION_PLAYBOOK) {
                throw new IllegalArgumentException("Only user episodes and operation playbooks are supported");
            }
            String userId = type == KnowledgeConceptType.USER_EPISODE ? context.userId() : null;
            if (type == KnowledgeConceptType.USER_EPISODE && userId == null) {
                throw new SecurityException("User episode requires an authenticated user");
            }
            String key = text(arguments, "memoryKey", 256);
            String title = text(arguments, "title", 512);
            String summary = text(arguments, "summary", 2048);
            String content = text(arguments, "content", 16000);
            if (sanitizer.containsSensitiveMaterial(key + "\n" + title + "\n" + summary + "\n" + content)) {
                throw new IllegalArgumentException("Memory must not contain credentials or secrets");
            }
            String conceptId = KnowledgeIdentity.sha256(mapper.writeValueAsString(
                    java.util.Arrays.asList(type.name(), context.tenantId(), userId, key)));
            KnowledgeHead previous = repository.findById(conceptId).orElse(null);
            Instant now = clock.instant();
            Instant expiresAt = instant(arguments, "expiresAt", null);
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                throw new IllegalArgumentException("expiresAt must be in the future");
            }
            LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("captureMode", "conversation");
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
                    type == KnowledgeConceptType.USER_EPISODE ? KnowledgeNamespaceType.USER_MEMORY
                            : KnowledgeNamespaceType.OPERATION_MEMORY,
                    null, type, key, KnowledgeStatus.STABLE, revision.id(), version + 1,
                    expiresAt, previous == null ? now : previous.concept().createdAt(), now);
            KnowledgeIndexTask task = new KnowledgeIndexTask(null, conceptId, revision.id(),
                    KnowledgeIndexOperation.UPSERT_CURRENT, KnowledgeIndexTaskStatus.PENDING,
                    0, now, null, null, null, now);
            repository.commitChanges(List.of(new KnowledgeRevisionChange(concept, version, revision,
                    List.of(), List.of(), List.of(), List.of(task))));
            signalIndex.run();
            return mapper.writeValueAsString(java.util.Map.of("status", "pending",
                    "conceptId", conceptId, "revisionId", revision.id(), "memoryType", type.name()));
        } catch (Exception exception) {
            throw new ToolExecutionException(TOOL_NAME, "Memory save failed: " + exception.getMessage());
        }
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
