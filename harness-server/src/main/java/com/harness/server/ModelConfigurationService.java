package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reads, validates, and persists model configuration managed by the Web console. */
public final class ModelConfigurationService {

    private static final List<String> SECTION_ORDER = List.of(
            "global",
            "chat",
            "vision",
            "voice",
            "embedding",
            "rerank",
            "realtime",
            "classifier",
            "imageGeneration",
            "videoGeneration"
    );

    private final EnvConfig config;
    private final ModelConfigurationFileStore fileStore;
    private final Set<String> pendingClearKeys = ConcurrentHashMap.newKeySet();

    public ModelConfigurationService(
            EnvConfig config,
            ModelConfigurationFileStore fileStore
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.fileStore = Objects.requireNonNull(fileStore, "fileStore");
    }

    public ModelConfigurationResponse current() throws IOException {
        Map<String, String> managedValues = fileStore.read();
        Map<String, List<ModelConfigurationField>> fieldsBySection = modelKeys().stream()
                .map(key -> toField(key, managedValues))
                .collect(java.util.stream.Collectors.groupingBy(
                        field -> sectionId(field.key()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));

        List<ModelConfigurationSection> sections = SECTION_ORDER.stream()
                .map(sectionId -> new ModelConfigurationSection(
                        sectionId,
                        fieldsBySection.getOrDefault(sectionId, List.of())))
                .filter(section -> !section.fields().isEmpty())
                .toList();
        boolean restartRequired = sections.stream()
                .flatMap(section -> section.fields().stream())
                .anyMatch(ModelConfigurationField::pendingRestart);
        return new ModelConfigurationResponse(
                fileStore.path().toString(),
                restartRequired,
                sections);
    }

    public ModelConfigurationResponse update(ModelConfigurationUpdateRequest request)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Map<String, String> values = request.values() == null
                ? Map.of()
                : request.values();
        Set<String> clearKeys = request.clearKeys() == null
                ? Set.of()
                : Set.copyOf(request.clearKeys());
        Set<String> allowedKeys = Set.copyOf(modelKeys());

        Set<String> requestedKeys = new HashSet<>(values.keySet());
        requestedKeys.addAll(clearKeys);
        if (!allowedKeys.containsAll(requestedKeys)) {
            requestedKeys.removeAll(allowedKeys);
            throw new IllegalArgumentException(
                    "Unsupported model configuration keys: " + requestedKeys);
        }
        Set<String> conflictingKeys = new HashSet<>(values.keySet());
        conflictingKeys.retainAll(clearKeys);
        if (!conflictingKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Keys cannot be updated and cleared together: " + conflictingKeys);
        }

        Map<String, String> normalizedValues = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Model configuration value must not be blank; clear the key explicitly: " + key);
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(
                        "Model configuration value must be a single line: " + key);
            }
            normalizedValues.put(key, value.trim());
        });

        fileStore.update(normalizedValues, clearKeys);
        pendingClearKeys.removeAll(normalizedValues.keySet());
        pendingClearKeys.addAll(clearKeys);
        return current();
    }

    private ModelConfigurationField toField(String key, Map<String, String> managedValues) {
        boolean managed = managedValues.containsKey(key);
        boolean pendingClear = pendingClearKeys.contains(key);
        String managedValue = managedValues.get(key);
        String effectiveValue = config.all().get(key);
        String displayedValue = pendingClear ? null : managed ? managedValue : effectiveValue;
        boolean configured = displayedValue != null && !displayedValue.isBlank();
        boolean effectiveConfigured = effectiveValue != null && !effectiveValue.isBlank();
        boolean sensitive = key.endsWith("_API_KEY");
        boolean pendingRestart = pendingClear
                ? effectiveConfigured
                : managed && !Objects.equals(managedValue, effectiveValue);
        return new ModelConfigurationField(
                key,
                sensitive ? null : displayedValue,
                configured,
                sensitive,
                managed,
                sensitive ? null : effectiveValue,
                effectiveConfigured,
                pendingRestart);
    }

    static List<String> modelKeys() {
        return Arrays.stream(EnvKey.class.getFields())
                .filter(field -> field.getType() == String.class)
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .map(ModelConfigurationService::readStringConstant)
                .filter(ModelConfigurationService::isModelKey)
                .distinct()
                .sorted()
                .toList();
    }

    private static String readStringConstant(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to read environment key " + field.getName(), e);
        }
    }

    private static boolean isModelKey(String key) {
        return key.startsWith("HARNESS_MODEL_")
                || key.startsWith("HARNESS_RERANK_")
                || key.startsWith("HARNESS_TOOL_IMAGE_GEN_")
                || key.startsWith("HARNESS_TOOL_VIDEO_GEN_");
    }

    private static String sectionId(String key) {
        if (key.startsWith("HARNESS_MODEL_CHAT_")) return "chat";
        if (key.startsWith("HARNESS_MODEL_VISION_")) return "vision";
        if (key.startsWith("HARNESS_MODEL_VOICE_")) return "voice";
        if (key.startsWith("HARNESS_MODEL_EMBEDDING_")) return "embedding";
        if (key.startsWith("HARNESS_MODEL_RERANK_") || key.startsWith("HARNESS_RERANK_")) {
            return "rerank";
        }
        if (key.startsWith("HARNESS_MODEL_REALTIME_")) return "realtime";
        if (key.startsWith("HARNESS_MODEL_CLASSIFIER_")) return "classifier";
        if (key.startsWith("HARNESS_TOOL_IMAGE_GEN_")) return "imageGeneration";
        if (key.startsWith("HARNESS_TOOL_VIDEO_GEN_")) return "videoGeneration";
        return "global";
    }

    public record ModelConfigurationResponse(
            String path,
            boolean restartRequired,
            List<ModelConfigurationSection> sections
    ) {
        public ModelConfigurationResponse {
            Objects.requireNonNull(path, "path");
            sections = List.copyOf(sections);
        }
    }

    public record ModelConfigurationSection(String id, List<ModelConfigurationField> fields) {
        public ModelConfigurationSection {
            Objects.requireNonNull(id, "id");
            fields = List.copyOf(fields);
        }
    }

    public record ModelConfigurationField(
            String key,
            String value,
            boolean configured,
            boolean sensitive,
            boolean managed,
            String effectiveValue,
            boolean effectiveConfigured,
            boolean pendingRestart
    ) {
        public ModelConfigurationField {
            Objects.requireNonNull(key, "key");
        }
    }

    public record ModelConfigurationUpdateRequest(
            Map<String, String> values,
            List<String> clearKeys
    ) {}
}
