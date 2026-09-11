package com.harness.core.knowledge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class KnowledgeOkfMapper {

    public OkfKnowledgeDocument map(
            KnowledgeConcept concept,
            KnowledgeRevision revision,
            List<KnowledgeSource> sources,
            List<KnowledgeVerification> verifications
    ) {
        Objects.requireNonNull(concept, "concept");
        Objects.requireNonNull(revision, "revision");
        if (!concept.id().equals(revision.conceptId())) {
            throw new IllegalArgumentException("revision does not belong to concept");
        }
        if (!revision.id().equals(concept.currentRevisionId())) {
            throw new IllegalArgumentException("only the current revision can be mapped to the current OKF view");
        }

        List<OkfKnowledgeDocument.Source> okfSources = safeList(sources).stream()
                .peek(source -> requireRevision(revision, source.revisionId(), "source"))
                .map(source -> new OkfKnowledgeDocument.Source(
                        source.sourceId(), source.sourceResource(), source.observedAt()))
                .toList();
        List<OkfKnowledgeDocument.Verified> okfVerifications = safeList(verifications).stream()
                .peek(verification -> requireRevision(revision, verification.revisionId(), "verification"))
                .filter(verification -> verification.result() == KnowledgeVerificationResult.PASSED)
                .map(verification -> new OkfKnowledgeDocument.Verified(
                        verification.verifiedBy(), verification.verifiedAt()))
                .toList();

        LinkedHashMap<String, Object> extensions = new LinkedHashMap<>();
        Object importedExtensions = revision.metadata().get("okfExtensions");
        if (importedExtensions instanceof Map<?, ?> imported) {
            imported.forEach((key, value) -> {
                if (key instanceof String field && !field.isBlank()) {
                    extensions.put(field, value);
                }
            });
        }
        extensions.put("x-cyrene-concept-id", concept.id());
        extensions.put("x-cyrene-revision-id", revision.id());
        extensions.put("x-cyrene-namespace-type", concept.namespaceType().name().toLowerCase());
        if (concept.namespaceKey() != null) {
            extensions.put("x-cyrene-namespace-key", concept.namespaceKey());
        }
        if (concept.logicalKey() != null) {
            extensions.put("x-cyrene-logical-key", concept.logicalKey());
        }
        copyMetadataExtension(revision.metadata(), extensions, "graphId", "x-cyrene-graph-id");
        copyMetadataExtension(revision.metadata(), extensions, "schemaId", "x-cyrene-schema-id");
        copyMetadataExtension(revision.metadata(), extensions, "queryIds", "x-cyrene-query-ids");
        copyMetadataExtension(revision.metadata(), extensions, "graphBindings", "x-cyrene-graph-bindings");

        return new OkfKnowledgeDocument(
                concept.conceptType().displayName(),
                revision.title(),
                revision.description(),
                resource(revision.metadata()),
                new OkfKnowledgeDocument.Generated(revision.generatedBy(), revision.generatedAt()),
                okfVerifications,
                concept.status(),
                concept.staleAfter(),
                okfSources,
                extensions,
                revision.body());
    }

    private static String resource(Map<String, Object> metadata) {
        Object value = metadata.get("resourceUri");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String resource) || resource.isBlank()) {
            throw new IllegalArgumentException("revision metadata resourceUri must be a non-blank string");
        }
        return resource.trim();
    }

    private static void copyMetadataExtension(
            Map<String, Object> metadata,
            Map<String, Object> extensions,
            String metadataKey,
            String extensionKey
    ) {
        Object value = metadata.get(metadataKey);
        if (value != null) {
            extensions.put(extensionKey, value);
        }
    }

    private static void requireRevision(KnowledgeRevision revision, String candidateRevisionId, String valueType) {
        if (!revision.id().equals(candidateRevisionId)) {
            throw new IllegalArgumentException(valueType + " does not belong to revision");
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
