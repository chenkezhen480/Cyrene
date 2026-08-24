package com.harness.core.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** Trusted server scope for one knowledge-base request. */
public record KnowledgeRequestContext(
        String collection,
        Set<String> allowedDocumentIds
) {
    public KnowledgeRequestContext {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("knowledge collection is required");
        }
        collection = collection.trim();
        if (collection.length() > 128) {
            throw new IllegalArgumentException("knowledge collection must not exceed 128 characters");
        }
        Set<String> validatedIds = new LinkedHashSet<>();
        if (allowedDocumentIds != null) {
            if (allowedDocumentIds.size() > 1000) {
                throw new IllegalArgumentException("allowedDocumentIds must not exceed 1000 entries");
            }
            for (String documentId : allowedDocumentIds) {
                if (documentId == null || documentId.isBlank() || documentId.length() > 128) {
                    throw new IllegalArgumentException("allowedDocumentIds contains an invalid documentId");
                }
                validatedIds.add(documentId);
            }
        }
        allowedDocumentIds = Set.copyOf(validatedIds);
    }

    public boolean hasDocumentScope() {
        return !allowedDocumentIds.isEmpty();
    }

    public boolean allowsDocument(String documentId) {
        return !hasDocumentScope() || allowedDocumentIds.contains(documentId);
    }
}
