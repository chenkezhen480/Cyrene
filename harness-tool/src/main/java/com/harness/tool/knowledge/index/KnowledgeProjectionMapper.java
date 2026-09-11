package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.provider.EmbeddingModelProvider;
import com.harness.tool.knowledge.authority.KnowledgeHead;
import dev.langchain4j.data.embedding.Embedding;

import java.util.Map;
import java.util.Optional;

/** Converts an authoritative current Head into one provider-neutral projection row. */
public final class KnowledgeProjectionMapper {

    private static final int MAX_CONTENT_CHARS = 65_535;

    private final EmbeddingModelProvider embeddingProvider;

    public KnowledgeProjectionMapper(EmbeddingModelProvider embeddingProvider) {
        this.embeddingProvider = java.util.Objects.requireNonNull(
                embeddingProvider, "embeddingProvider");
    }

    public Optional<KnowledgeProjection> map(KnowledgeHead head) {
        return map(head, false);
    }

    public Optional<KnowledgeProjection> mapCatalog(KnowledgeHead head) {
        return map(head, true);
    }

    private Optional<KnowledgeProjection> map(KnowledgeHead head, boolean catalog) {
        if (head == null || head.currentRevision() == null
                || head.concept().status() != KnowledgeStatus.STABLE) {
            return Optional.empty();
        }
        KnowledgeConcept concept = head.concept();
        KnowledgeRevision revision = head.currentRevision();
        if (!revision.id().equals(concept.currentRevisionId())) {
            throw new IllegalArgumentException(
                    "Knowledge Head current Revision does not match Concept");
        }
        if (!isSearchableType(concept.conceptType())) {
            return Optional.empty();
        }
        if (!embeddingProvider.isAvailable() || embeddingProvider.dimension() <= 0) {
            throw new IllegalStateException(
                    "Knowledge projection requires an available embedding provider");
        }

        String content = catalog
                ? revision.title() + (revision.description() == null ? "" : "\n\n" + revision.description())
                : concept.conceptType().isMemory() ? revision.body() : projectionContent(revision);
        Embedding embedded = embeddingProvider.embed(content);
        if (embedded == null || embedded.vector() == null
                || embedded.vector().length != embeddingProvider.dimension()) {
            throw new IllegalStateException(
                    "Embedding dimension does not match configured Knowledge projection schema");
        }
        Map<String, Object> metadata = revision.metadata();
        return Optional.of(new KnowledgeProjection(
                revision.id(),
                concept.id(),
                revision.id(),
                concept.tenantId(),
                concept.userId(),
                concept.namespaceType(),
                concept.namespaceKey(),
                concept.conceptType(),
                routeTarget(concept.conceptType()),
                resourceUri(concept, revision, metadata),
                revision.title(),
                revision.description(),
                content,
                revision.generatedAt(),
                eventTime(concept, revision),
                concept.logicalKey(),
                qualityScore(concept, metadata),
                requiredTools(concept, metadata),
                embedded.vector()));
    }

    public static boolean isSearchableType(KnowledgeConceptType type) {
        return type == KnowledgeConceptType.SOURCE_DOCUMENT
                || type == KnowledgeConceptType.GRAPH_SCHEMA
                || type == KnowledgeConceptType.GRAPH_SPACE
                || type == KnowledgeConceptType.USER_EPISODE
                || type == KnowledgeConceptType.OPERATION_PLAYBOOK;
    }

    public static KnowledgeRouteTarget routeTarget(KnowledgeConceptType type) {
        return switch (type) {
            case SOURCE_DOCUMENT -> KnowledgeRouteTarget.DOCUMENT;
            case GRAPH_SCHEMA, GRAPH_SPACE -> KnowledgeRouteTarget.GRAPH;
            case USER_EPISODE -> KnowledgeRouteTarget.USER_MEMORY;
            case OPERATION_PLAYBOOK -> KnowledgeRouteTarget.OPERATION_MEMORY;
            case USER_PREFERENCE -> throw new IllegalArgumentException(
                    "User Preference is not a vector projection");
        };
    }

    private static String projectionContent(KnowledgeRevision revision) {
        StringBuilder content = new StringBuilder(revision.title());
        if (revision.description() != null) {
            content.append("\n\n").append(revision.description());
        }
        content.append("\n\n").append(revision.body());
        if (content.length() > MAX_CONTENT_CHARS) {
            return content.substring(0, MAX_CONTENT_CHARS);
        }
        return content.toString();
    }

    private static String resourceUri(
            KnowledgeConcept concept,
            KnowledgeRevision revision,
            Map<String, Object> metadata
    ) {
        Object configured = metadata.get("resourceUri");
        if (configured instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return "cyrene://knowledge/" + concept.id() + "/revisions/" + revision.id();
    }

    private static java.time.Instant eventTime(
            KnowledgeConcept concept,
            KnowledgeRevision revision
    ) {
        if (concept.conceptType() != KnowledgeConceptType.USER_EPISODE) return null;
        Object value = revision.metadata().get("eventTime");
        if (value == null) return revision.generatedAt();
        if (value instanceof java.time.Instant instant) return instant;
        if (value instanceof String text) {
            try {
                return java.time.Instant.parse(text);
            } catch (java.time.format.DateTimeParseException exception) {
                throw new IllegalArgumentException("User Episode eventTime must be an instant", exception);
            }
        }
        throw new IllegalArgumentException("User Episode eventTime must be an instant");
    }

    private static Double qualityScore(
            KnowledgeConcept concept,
            Map<String, Object> metadata
    ) {
        if (concept.conceptType() != KnowledgeConceptType.OPERATION_PLAYBOOK) return null;
        Object value = metadata.get("qualityScore");
        if (value instanceof Number number) return number.doubleValue();
        return null;
    }

    private static java.util.List<String> requiredTools(
            KnowledgeConcept concept,
            Map<String, Object> metadata
    ) {
        if (concept.conceptType() != KnowledgeConceptType.OPERATION_PLAYBOOK) return java.util.List.of();
        Object value = metadata.get("requiredTools");
        if (!(value instanceof Iterable<?> iterable)) return java.util.List.of();
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        for (Object item : iterable) {
            if (item instanceof String tool && !tool.isBlank()) tools.add(tool.trim());
        }
        return java.util.List.copyOf(tools);
    }

}
