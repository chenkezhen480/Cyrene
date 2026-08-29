package com.harness.core.modelconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and atomically replaces the standalone {@code model.conf} file. */
public final class ModelConfigFile {

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9.]*)\\s*=\\s*(.*)$");
    private final Path path;

    public ModelConfigFile(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public synchronized ModelConfig read() throws IOException {
        if (!Files.exists(path)) return ModelConfig.empty();
        Map<String, String> values = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            Matcher matcher = ASSIGNMENT.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "Invalid model.conf assignment at line " + lineNumber);
            }
            String key = matcher.group(1);
            if (values.put(key, decodeValue(matcher.group(2))) != null) {
                throw new IllegalStateException("Duplicate model.conf key: " + key);
            }
        }
        return ModelConfig.of(values);
    }

    public synchronized void replace(ModelConfig config) throws IOException {
        List<String> lines = ModelConfigKey.DEFINITIONS.stream()
                .filter(definition -> config.values().containsKey(definition.key()))
                .map(definition -> definition.key() + "="
                        + encodeValue(config.values().get(definition.key())))
                .toList();
        Path parent = path.getParent();
        if (parent == null) throw new IOException("model.conf path has no parent: " + path);
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, ".model-config-", ".tmp");
        try {
            String content = String.join(System.lineSeparator(), lines);
            if (!content.isEmpty()) content += System.lineSeparator();
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
            Files.move(temporaryFile, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    public Path path() { return path; }

    private static String decodeValue(String rawValue) {
        String value = rawValue.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        int commentIndex = value.indexOf(" #");
        return commentIndex >= 0 ? value.substring(0, commentIndex).trim() : value;
    }

    private static String encodeValue(String value) {
        if (value.matches("[A-Za-z0-9_./:@?&=,+\\-]*")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
