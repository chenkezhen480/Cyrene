package com.harness.tool.knowledge.authority;

import com.harness.core.knowledge.KnowledgeConcept;
import com.harness.core.knowledge.KnowledgeConceptType;
import com.harness.core.knowledge.KnowledgeRevision;
import com.harness.core.knowledge.KnowledgeRouteTarget;
import java.util.LinkedHashMap;
import java.util.Map;

/** MySQL authority determines the current version and the final content route. */
public record KnowledgeHead(KnowledgeConcept concept, KnowledgeRevision currentRevision,
                            KnowledgeRouteTarget routeType, Map<String, Object> routeData) {
    public KnowledgeHead(KnowledgeConcept concept, KnowledgeRevision currentRevision) {
        this(concept, currentRevision, routeTypeFor(concept.conceptType()), routeDataFor(concept, currentRevision));
    }

    public KnowledgeHead {
        if (concept == null) throw new IllegalArgumentException("concept is required");
        if (concept.currentRevisionId() == null && currentRevision != null)
            throw new IllegalArgumentException("headless concept cannot have a currentRevision");
        if (currentRevision != null && (!currentRevision.id().equals(concept.currentRevisionId())
                || !currentRevision.conceptId().equals(concept.id())))
            throw new IllegalArgumentException("currentRevision must match concept head");
        if (routeType != routeTypeFor(concept.conceptType()))
            throw new IllegalArgumentException("Authority route type does not match knowledge type");
        routeData = Map.copyOf(routeData);
    }

    public String currentVersion() { return concept.currentRevisionId(); }

    public KnowledgeHead withRevision(KnowledgeRevision revision) {
        return new KnowledgeHead(concept, revision, routeType, routeData);
    }

    public String routeText(String key) {
        Object value = routeData.get(key);
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalStateException("MySQL knowledge route is missing " + key);
        return text;
    }

    public static KnowledgeRouteTarget routeTypeFor(KnowledgeConceptType type) {
        return switch (type) {
            case SOURCE_DOCUMENT -> KnowledgeRouteTarget.DOCUMENT;
            case GRAPH_SCHEMA, GRAPH_SPACE -> KnowledgeRouteTarget.GRAPH;
            case USER_EPISODE -> KnowledgeRouteTarget.USER_MEMORY;
            case OPERATION_PLAYBOOK -> KnowledgeRouteTarget.OPERATION_MEMORY;
            case USER_PREFERENCE -> null;
        };
    }

    /** Built by trusted ingestion code; never copied from a Milvus hit or tool arguments. */
    public static Map<String, Object> routeDataFor(KnowledgeConcept concept, KnowledgeRevision revision) {
        Map<String, Object> route = new LinkedHashMap<>();
        switch (concept.conceptType()) {
            case SOURCE_DOCUMENT -> {
                route.put("collectionKey", concept.namespaceKey());
                route.put("documentId", concept.id());
            }
            case USER_EPISODE, OPERATION_PLAYBOOK -> route.put("memoryId", concept.id());
            case GRAPH_SCHEMA -> route.put("schemaId", concept.namespaceKey());
            case GRAPH_SPACE -> {
                if (revision != null) {
                    for (String key : java.util.List.of("graphId", "schemaId")) {
                        Object value = revision.metadata().get(key);
                        if (value != null) route.put(key, value);
                    }
                }
            }
            case USER_PREFERENCE -> { }
        }
        return Map.copyOf(route);
    }
}
