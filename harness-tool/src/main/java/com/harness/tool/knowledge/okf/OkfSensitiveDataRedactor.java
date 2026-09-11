package com.harness.tool.knowledge.okf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Deterministic last-line defense for exchange artifacts. */
public final class OkfSensitiveDataRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?im)(authorization\\s*[:=]\\s*)(?:bearer\\s+)?[^\\s,;]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE = Pattern.compile(
            "(?im)((?:set-)?cookie\\s*[:=]\\s*)[^\\r\\n]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)"
                    + "\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{8,}");

    public String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = AUTHORIZATION.matcher(value).replaceAll("$1" + REDACTED);
        result = COOKIE.matcher(result).replaceAll("$1" + REDACTED);
        result = NAMED_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        return BEARER.matcher(result).replaceAll(REDACTED);
    }

    public boolean containsSensitiveData(String value) {
        return value != null && !redact(value).equals(value);
    }

    public Object redactValue(Object value) {
        if (value instanceof String text) {
            return redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, item) -> sanitized.put(String.valueOf(key), redactValue(item)));
            return Map.copyOf(sanitized);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>(list.size());
            list.forEach(item -> sanitized.add(redactValue(item)));
            return List.copyOf(sanitized);
        }
        return value;
    }
}
