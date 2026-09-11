package com.harness.core.knowledge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral OKF v0.2 concept representation. */
public record OkfKnowledgeDocument(
        String type,
        String title,
        String description,
        String resource,
        Generated generated,
        List<Verified> verified,
        KnowledgeStatus status,
        Instant staleAfter,
        List<Source> sources,
        Map<String, Object> extensions,
        String body
) {
    public OkfKnowledgeDocument {
        type = KnowledgeModelSupport.requiredText(type, "type", 128);
        title = KnowledgeModelSupport.optionalText(title, "title", 512);
        description = KnowledgeModelSupport.optionalText(description, "description", 2048);
        resource = KnowledgeModelSupport.optionalText(resource, "resource", 2048);
        generated = Objects.requireNonNull(generated, "generated");
        verified = KnowledgeModelSupport.immutableList(verified);
        status = Objects.requireNonNull(status, "status");
        sources = KnowledgeModelSupport.immutableList(sources);
        extensions = KnowledgeModelSupport.immutableMap(extensions);
        body = Objects.requireNonNull(body, "body");
        if (extensions.keySet().stream().anyMatch(key -> key == null || key.isBlank())) {
            throw new IllegalArgumentException("OKF extension field names must not be blank");
        }
    }

    public Map<String, Object> frontMatter() {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("type", type);
        putIfPresent(values, "title", title);
        putIfPresent(values, "description", description);
        putIfPresent(values, "resource", resource);
        values.put("generated", generated.asMap());
        if (!verified.isEmpty()) {
            values.put("verified", verified.stream().map(Verified::asMap).toList());
        }
        values.put("status", status.storageValue());
        putIfPresent(values, "stale_after", staleAfter);
        if (!sources.isEmpty()) {
            values.put("sources", sources.stream().map(Source::asMap).toList());
        }
        values.putAll(extensions);
        return Map.copyOf(values);
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    public record Generated(String by, Instant at) {
        public Generated {
            by = KnowledgeModelSupport.requiredText(by, "generated.by", 256);
            at = Objects.requireNonNull(at, "generated.at");
        }

        Map<String, Object> asMap() {
            return Map.of("by", by, "at", at);
        }
    }

    public record Verified(String by, Instant at) {
        public Verified {
            by = KnowledgeModelSupport.requiredText(by, "verified.by", 256);
            at = Objects.requireNonNull(at, "verified.at");
        }

        Map<String, Object> asMap() {
            return Map.of("by", by, "at", at);
        }
    }

    public record Source(String id, String resource, Instant lastModified) {
        public Source {
            id = KnowledgeModelSupport.optionalText(id, "source.id", 128);
            resource = KnowledgeModelSupport.requiredText(resource, "source.resource", 2048);
        }

        Map<String, Object> asMap() {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            putIfPresent(values, "id", id);
            values.put("resource", resource);
            putIfPresent(values, "last_modified", lastModified);
            return Map.copyOf(values);
        }
    }
}
