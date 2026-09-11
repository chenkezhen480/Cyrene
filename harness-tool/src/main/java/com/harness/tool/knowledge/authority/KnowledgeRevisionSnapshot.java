package com.harness.tool.knowledge.authority;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.knowledge.*;
import java.util.List;

/** Version content and provenance stored with its vector record, or in a pending task. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeRevisionSnapshot(
        KnowledgeConceptType conceptType, String collection, KnowledgeRevision revision,
        List<KnowledgeSource> sources, List<KnowledgeVerification> verifications) {
    public KnowledgeRevisionSnapshot {
        conceptType = java.util.Objects.requireNonNull(conceptType, "conceptType");
        revision = java.util.Objects.requireNonNull(revision, "revision");
        sources = List.copyOf(sources);
        var originalVerifications = List.copyOf(verifications);
        verifications = java.util.stream.IntStream.range(0, verifications.size())
                .mapToObj(i -> { var v = originalVerifications.get(i); return new KnowledgeVerification(
                        (long) i + 1, v.revisionId(), v.verifiedBy(), v.verificationType(),
                        v.result(), v.reason(), v.verifiedAt()); }).toList();
    }
    @com.fasterxml.jackson.annotation.JsonProperty("sourceKeys")
    public List<String> sourceKeys() {
        return sources.stream().map(s -> s.sourceType().name() + ":" + s.sourceId()).distinct().toList();
    }
    public KnowledgeRevisionSnapshot withBody(String body) {
        var r = revision;
        return new KnowledgeRevisionSnapshot(conceptType, collection, new KnowledgeRevision(
                r.id(), r.conceptId(), r.revisionNumber(), r.title(), r.description(), body,
                r.generatedBy(), r.generatedAt(), r.contentHash(), r.metadata(), r.createdAt()),
                sources, verifications);
    }
    public String toJson(ObjectMapper mapper) {
        try { return mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writeValueAsString(this); }
        catch (java.io.IOException e) { throw new KnowledgePersistenceException("Cannot serialize knowledge version", e); }
    }
    public static KnowledgeRevisionSnapshot fromJson(ObjectMapper mapper, String json) {
        try { return mapper.readValue(json, KnowledgeRevisionSnapshot.class); }
        catch (java.io.IOException e) { throw new KnowledgePersistenceException("Cannot read knowledge version", e); }
    }
}
