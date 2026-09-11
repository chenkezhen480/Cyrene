package com.harness.tool.knowledge.okf;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.harness.core.knowledge.KnowledgeStatus;
import com.harness.core.knowledge.OkfKnowledgeDocument;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded Markdown/YAML codec for OKF v0.2 exchange documents. */
public final class OkfMarkdownCodec {

    private static final int MAX_DOCUMENT_CHARS = 1_048_576;
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() { };
    private static final Set<String> STANDARD_FIELDS = Set.of(
            "type", "title", "description", "resource", "generated", "verified",
            "status", "stale_after", "sources");

    private final ObjectMapper yamlMapper;

    public OkfMarkdownCodec() {
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        yamlMapper = new ObjectMapper(yamlFactory)
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String write(OkfKnowledgeDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        try {
            String yaml = yamlMapper.writeValueAsString(document.frontMatter()).stripTrailing();
            return "---\n" + yaml + "\n---\n\n" + document.body().stripTrailing() + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize OKF document", e);
        }
    }

    public OkfKnowledgeDocument read(String markdown) {
        String normalized = normalize(markdown);
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("OKF document must start with YAML front matter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalArgumentException("OKF YAML front matter is not terminated");
        }
        String yaml = normalized.substring(4, end);
        String body = normalized.substring(end + 5);
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        try {
            LinkedHashMap<String, Object> values = yamlMapper.readValue(yaml, MAP_TYPE);
            return fromValues(values, body.stripTrailing());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid OKF YAML front matter", e);
        }
    }

    private static OkfKnowledgeDocument fromValues(Map<String, Object> values, String body) {
        if (values == null) {
            throw new IllegalArgumentException("OKF YAML front matter is empty");
        }
        String type = requiredString(values.get("type"), "type");
        OkfKnowledgeDocument.Generated generated = generated(values.get("generated"));
        List<OkfKnowledgeDocument.Verified> verified = verified(values.get("verified"));
        KnowledgeStatus status = status(values.get("status"));
        List<OkfKnowledgeDocument.Source> sources = sources(values.get("sources"));
        LinkedHashMap<String, Object> extensions = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!STANDARD_FIELDS.contains(key)) {
                extensions.put(key, value);
            }
        });
        return new OkfKnowledgeDocument(
                type,
                optionalString(values.get("title"), "title"),
                optionalString(values.get("description"), "description"),
                optionalString(values.get("resource"), "resource"),
                generated,
                verified,
                status,
                instant(values.get("stale_after"), "stale_after", false),
                sources,
                extensions,
                body);
    }

    private static OkfKnowledgeDocument.Generated generated(Object value) {
        Map<?, ?> map = requiredMap(value, "generated");
        return new OkfKnowledgeDocument.Generated(
                requiredString(map.get("by"), "generated.by"),
                instant(map.get("at"), "generated.at", true));
    }

    private static List<OkfKnowledgeDocument.Verified> verified(Object value) {
        if (value == null) {
            return List.of();
        }
        List<?> list = requiredList(value, "verified");
        List<OkfKnowledgeDocument.Verified> result = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<?, ?> map = requiredMap(item, "verified item");
            result.add(new OkfKnowledgeDocument.Verified(
                    requiredString(map.get("by"), "verified.by"),
                    instant(map.get("at"), "verified.at", true)));
        }
        return List.copyOf(result);
    }

    private static List<OkfKnowledgeDocument.Source> sources(Object value) {
        if (value == null) {
            return List.of();
        }
        List<?> list = requiredList(value, "sources");
        List<OkfKnowledgeDocument.Source> result = new ArrayList<>(list.size());
        Set<String> identities = new LinkedHashSet<>();
        for (Object item : list) {
            Map<?, ?> map = requiredMap(item, "source item");
            String id = optionalString(map.get("id"), "source.id");
            String resource = requiredString(map.get("resource"), "source.resource");
            Instant lastModified = instant(map.get("last_modified"), "source.last_modified", false);
            String identity = id + "\u0000" + resource;
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Duplicate OKF source: " + resource);
            }
            result.add(new OkfKnowledgeDocument.Source(id, resource, lastModified));
        }
        return List.copyOf(result);
    }

    private static KnowledgeStatus status(Object value) {
        String text = requiredString(value, "status");
        try {
            return KnowledgeStatus.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported OKF status: " + text, e);
        }
    }

    private static Instant instant(Object value, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(field + " is required");
            }
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 instant", e);
        }
    }

    private static String requiredString(Object value, String field) {
        String result = optionalString(value, field);
        if (result == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return result;
    }

    private static String optionalString(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return text.trim();
    }

    private static Map<?, ?> requiredMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return map;
    }

    private static List<?> requiredList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a list");
        }
        return list;
    }

    private static String normalize(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("markdown is required");
        }
        if (markdown.length() > MAX_DOCUMENT_CHARS) {
            throw new IllegalArgumentException("OKF document exceeds the size limit");
        }
        return markdown.replace("\r\n", "\n").replace('\r', '\n');
    }
}
