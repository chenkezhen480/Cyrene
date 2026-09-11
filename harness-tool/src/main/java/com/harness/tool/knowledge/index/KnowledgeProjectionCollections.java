package com.harness.tool.knowledge.index;

import com.harness.core.knowledge.KnowledgeConceptType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated physical names for document chunks and the three searchable Wiki projections. */
public record KnowledgeProjectionCollections(
        String documentCollection,
        String catalogCollection,
        String userKnowledgeCollection,
        String operationKnowledgeCollection
) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    public KnowledgeProjectionCollections {
        documentCollection = requiredName(documentCollection, "documentCollection");
        catalogCollection = requiredName(catalogCollection, "catalogCollection");
        userKnowledgeCollection = requiredName(
                userKnowledgeCollection, "userKnowledgeCollection");
        operationKnowledgeCollection = requiredName(
                operationKnowledgeCollection, "operationKnowledgeCollection");
        List<String> names = List.of(documentCollection, catalogCollection,
                userKnowledgeCollection, operationKnowledgeCollection);
        Set<String> unique = new LinkedHashSet<>(names);
        if (unique.size() != names.size()) {
            throw new IllegalArgumentException(
                    "Document, Wiki, user memory, and operation memory collections must be distinct");
        }
    }

    public String projectionCollection(KnowledgeConceptType conceptType) {
        return switch (conceptType) {
            case SOURCE_DOCUMENT, GRAPH_SCHEMA, GRAPH_SPACE -> catalogCollection;
            case USER_EPISODE -> userKnowledgeCollection;
            case OPERATION_PLAYBOOK -> operationKnowledgeCollection;
            case USER_PREFERENCE -> throw new IllegalArgumentException(
                    "User Preference is not a vector projection");
        };
    }

    public List<String> projectionCollections() {
        return List.of(catalogCollection, userKnowledgeCollection, operationKnowledgeCollection);
    }

    private static String requiredName(String value, String field) {
        if (value == null || !NAME.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " is not a valid collection name");
        }
        return value.trim();
    }
}
