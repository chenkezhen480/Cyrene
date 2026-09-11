package com.harness.agent.memory;

import java.util.regex.Pattern;

/** Bounded sensitive-material check shared by memory capture paths. */
final class KnowledgeExtractionSanitizer {

    private static final Pattern BEARER_VALUE = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[a-z0-9._~+/=-]{8,}");
    private static final Pattern ASSIGNED_SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|token|secret|api[-_]?key)\\s*[:=]\\s*\\S+");

    boolean containsSensitiveMaterial(String value) {
        if (value == null) {
            return false;
        }
        return BEARER_VALUE.matcher(value).find() || ASSIGNED_SECRET.matcher(value).find();
    }
}
