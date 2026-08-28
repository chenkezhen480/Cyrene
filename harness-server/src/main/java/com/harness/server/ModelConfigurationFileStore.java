package com.harness.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persists model settings in the project dotenv file without rewriting unrelated entries. */
public final class ModelConfigurationFileStore {

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=(.*)$");

    private final Path envFile;

    public ModelConfigurationFileStore(Path envFile) {
        this.envFile = Objects.requireNonNull(envFile, "envFile")
                .toAbsolutePath()
                .normalize();
    }

    public synchronized Map<String, String> read() throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.exists(envFile)) return values;
        for (String line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            Matcher matcher = ASSIGNMENT.matcher(line);
            if (matcher.matches()) {
                values.put(matcher.group(1), decodeValue(matcher.group(2)));
            }
        }
        return values;
    }

    public synchronized void update(Map<String, String> values, Set<String> clearKeys)
            throws IOException {
        Map<String, String> remainingValues = new LinkedHashMap<>(values);
        Set<String> removals = Set.copyOf(clearKeys);
        Set<String> writtenKeys = new HashSet<>();
        List<String> currentLines = Files.exists(envFile)
                ? Files.readAllLines(envFile, StandardCharsets.UTF_8)
                : List.of();
        List<String> updatedLines = new ArrayList<>(currentLines.size() + remainingValues.size());

        for (String line : currentLines) {
            Matcher matcher = ASSIGNMENT.matcher(line);
            if (!matcher.matches()) {
                updatedLines.add(line);
                continue;
            }
            String key = matcher.group(1);
            if (removals.contains(key)) continue;
            if (!values.containsKey(key)) {
                updatedLines.add(line);
                continue;
            }
            if (writtenKeys.add(key)) {
                updatedLines.add(key + "=" + encodeValue(values.get(key)));
                remainingValues.remove(key);
            }
        }

        if (!remainingValues.isEmpty()) {
            if (!updatedLines.isEmpty() && !updatedLines.getLast().isBlank()) {
                updatedLines.add("");
            }
            remainingValues.forEach((key, value) ->
                    updatedLines.add(key + "=" + encodeValue(value)));
        }

        Path parent = envFile.getParent();
        if (parent == null) {
            throw new IOException("Model configuration path has no parent: " + envFile);
        }
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, ".cyrene-model-config-", ".tmp");
        try {
            String content = String.join(System.lineSeparator(), updatedLines);
            if (!content.isEmpty()) content += System.lineSeparator();
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
            Files.move(
                    temporaryFile,
                    envFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    Path path() {
        return envFile;
    }

    private static String decodeValue(String rawValue) {
        String value = rawValue.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        int commentIndex = value.indexOf(" #");
        return commentIndex >= 0 ? value.substring(0, commentIndex).trim() : value;
    }

    private static String encodeValue(String value) {
        if (value.matches("[A-Za-z0-9_./:@?&=,+\\-]*")) return value;
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
