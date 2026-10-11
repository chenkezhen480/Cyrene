package com.harness.core.modelconfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and atomically replaces the standalone {@code model.conf} file. */
public final class ModelConfigFile {

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9.]*)\\s*=\\s*(.*)$");
    private final Path path;
    private final String readGroup;

    public ModelConfigFile(Path path) {
        this(path, "");
    }

    public ModelConfigFile(Path path, String readGroup) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.readGroup = Objects.requireNonNull(readGroup, "readGroup").trim();
    }

    public synchronized ModelConfig read() throws IOException {
        if (!Files.exists(path)) return ModelConfig.empty();
        return ModelConfig.of(readAssignments(Files.readString(path, StandardCharsets.UTF_8)));
    }

    private static Map<String, String> readAssignments(String content) {
        Map<String, String> values = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : content.lines().toList()) {
            lineNumber++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            Matcher matcher = ASSIGNMENT.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "Invalid model.conf assignment at line " + lineNumber);
            }
            String key = matcher.group(1);
            if (!ModelConfigKey.isKnown(key)) {
                throw new IllegalArgumentException("Unknown model configuration key: " + key);
            }
            if (values.put(key, decodeValue(matcher.group(2))) != null) {
                throw new IllegalStateException("Duplicate model.conf key: " + key);
            }
        }
        return values;
    }

    public synchronized void replace(ModelConfig config) throws IOException {
        List<String> lines = ModelConfigKey.DEFINITIONS.stream()
                .filter(definition -> !definition.hidden() || config.values().containsKey(definition.key()))
                .map(definition -> definition.key() + "="
                        + encodeValue(config.values().getOrDefault(definition.key(), "")))
                .toList();
        String content = String.join(System.lineSeparator(), lines);
        if (!content.isEmpty()) content += System.lineSeparator();
        writeAtomically(content);
    }

    synchronized void completeVisibleKeys() throws IOException {
        applyReadPermissions(path);
        String content = Files.readString(path, StandardCharsets.UTF_8);
        Map<String, String> assignments = readAssignments(content);
        List<String> missing = ModelConfigKey.DEFINITIONS.stream()
                .filter(definition -> !definition.hidden() && !assignments.containsKey(definition.key()))
                .map(definition -> definition.key() + "=").toList();
        if (missing.isEmpty()) return;
        String separator = System.lineSeparator();
        if (!content.isEmpty() && !content.endsWith("\n") && !content.endsWith("\r")) content += separator;
        writeAtomically(content + String.join(separator, missing) + separator);
    }

    private void writeAtomically(String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("model.conf path has no parent: " + path);
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, ".model-config-", ".tmp");
        try {
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
            applyReadPermissions(temporaryFile);
            Files.move(temporaryFile, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private void applyReadPermissions(Path file) throws IOException {
        if (readGroup.isEmpty()) return;
        PosixFileAttributeView attributes = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (attributes == null) throw new IOException(
                "Model configuration read group requires a POSIX filesystem: " + path);
        attributes.setGroup(path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByGroupName(readGroup));
        attributes.setPermissions(Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ));
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
