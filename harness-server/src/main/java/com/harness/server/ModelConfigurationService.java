package com.harness.server;

import com.harness.core.modelconfig.ModelConfig;
import com.harness.core.modelconfig.ModelConfigFile;
import com.harness.core.modelconfig.ModelConfigKey;
import com.harness.core.runtime.ModelConfigurationRuntime;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates, persists, and activates the standalone {@code model.conf}. */
public final class ModelConfigurationService {

    private final ModelConfigFile configFile;
    private final ModelConfigurationRuntime runtime;

    public ModelConfigurationService(
            ModelConfigFile configFile,
            ModelConfigurationRuntime runtime
    ) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public ModelConfigurationResponse current() throws IOException {
        ModelConfig persisted = configFile.read();
        ModelConfig active = runtime.currentConfiguration();
        List<ModelConfigurationSection> sections = ModelConfigKey.DEFINITIONS.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ModelConfigKey.Definition::section,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()))
                .entrySet().stream()
                .map(entry -> new ModelConfigurationSection(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(definition -> toField(definition, persisted, active))
                                .toList()))
                .toList();
        return new ModelConfigurationResponse(
                configFile.path().toString(),
                persisted.values().equals(active.values()),
                sections);
    }

    public synchronized ModelConfigurationResponse update(ModelConfigurationUpdateRequest request)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Map<String, String> values = request.values() == null ? Map.of() : request.values();
        Set<String> clearKeys = request.clearKeys() == null
                ? Set.of()
                : Set.copyOf(request.clearKeys());

        Set<String> requestedKeys = new HashSet<>(values.keySet());
        requestedKeys.addAll(clearKeys);
        Set<String> unknownKeys = requestedKeys.stream()
                .filter(key -> !ModelConfigKey.isKnown(key))
                .collect(java.util.stream.Collectors.toSet());
        if (!unknownKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unsupported model configuration keys: " + unknownKeys);
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

        ModelConfig previous = configFile.read();
        Map<String, String> candidateValues = new LinkedHashMap<>(previous.values());
        clearKeys.forEach(candidateValues::remove);
        candidateValues.putAll(normalizedValues);
        ModelConfig candidate = ModelConfig.of(candidateValues);
        ModelConfigurationRuntime.PreparedUpdate preparedUpdate = runtime.prepare(candidate);

        // Persist first so a published provider generation always has a durable source.
        configFile.replace(candidate);
        try {
            preparedUpdate.activate();
        } catch (RuntimeException activationFailure) {
            try {
                configFile.replace(previous);
            } catch (IOException rollbackFailure) {
                activationFailure.addSuppressed(rollbackFailure);
            }
            throw activationFailure;
        }
        return current();
    }

    private static ModelConfigurationField toField(
            ModelConfigKey.Definition definition,
            ModelConfig persisted,
            ModelConfig active
    ) {
        String persistedValue = persisted.getString(definition.key());
        String activeValue = active.getString(definition.key());
        boolean configured = persistedValue != null;
        return new ModelConfigurationField(
                definition.key(),
                definition.label(),
                definition.sensitive() ? null : persistedValue,
                configured,
                definition.sensitive(),
                Objects.equals(persistedValue, activeValue));
    }

    public record ModelConfigurationResponse(
            String path,
            boolean runtimeSynchronized,
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

    /** Label is returned by the backend so every client uses the same Chinese annotation. */
    public record ModelConfigurationField(
            String key,
            String label,
            String value,
            boolean configured,
            boolean sensitive,
            boolean runtimeSynchronized
    ) {
        public ModelConfigurationField {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(label, "label");
        }
    }

    public record ModelConfigurationUpdateRequest(
            Map<String, String> values,
            List<String> clearKeys
    ) {}
}
