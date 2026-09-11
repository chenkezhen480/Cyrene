package com.harness.core.knowledge;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PreferenceKeyRegistry {

    public static final String OTHER_KEY = "other";

    private final PreferenceActivationTagRegistry activationTagRegistry;
    private final Map<String, PreferenceKeyDefinition> definitions;

    public PreferenceKeyRegistry(
            PreferenceActivationTagRegistry activationTagRegistry,
            Collection<PreferenceKeyDefinition> definitions
    ) {
        this.activationTagRegistry = Objects.requireNonNull(activationTagRegistry, "activationTagRegistry");
        if (definitions == null || definitions.isEmpty()) {
            throw new IllegalArgumentException("preference key definitions must not be empty");
        }
        LinkedHashMap<String, PreferenceKeyDefinition> byKey = new LinkedHashMap<>();
        for (PreferenceKeyDefinition definition : definitions) {
            Objects.requireNonNull(definition, "preference key definition");
            if (!definition.defaultActivationTags().isEmpty()) {
                activationTagRegistry.validate(definition.defaultActivationTags());
            }
            if (byKey.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalArgumentException("duplicate preference key: " + definition.key());
            }
        }
        if (!byKey.containsKey(OTHER_KEY)) {
            throw new IllegalArgumentException("preference key registry must contain reserved key: other");
        }
        this.definitions = Map.copyOf(byKey);
    }

    public static PreferenceKeyRegistry standard() {
        PreferenceActivationTagRegistry tags = PreferenceActivationTagRegistry.standard();
        return new PreferenceKeyRegistry(tags, Set.of(
                new PreferenceKeyDefinition(
                        "response.verbosity", Set.of(PreferenceActivationTagRegistry.GENERAL_RESPONSE)),
                new PreferenceKeyDefinition(
                        "response.language", Set.of(PreferenceActivationTagRegistry.GENERAL_RESPONSE)),
                new PreferenceKeyDefinition(
                        "code.namingStyle", Set.of(PreferenceActivationTagRegistry.CODE_TASK)),
                new PreferenceKeyDefinition(
                        "image.visualStyle", Set.of(PreferenceActivationTagRegistry.IMAGE_TASK)),
                new PreferenceKeyDefinition(OTHER_KEY, Set.of())));
    }

    public Optional<PreferenceKeyDefinition> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitions.get(key.trim()));
    }

    public PreferenceKeyDefinition require(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException("unregistered preference key: " + key));
    }

    public Set<String> activationTagsFor(String key) {
        return require(key).defaultActivationTags();
    }

    public Set<String> validateActivationTags(String key, Collection<String> candidateTags) {
        PreferenceKeyDefinition definition = require(key);
        Set<String> validated = activationTagRegistry.validate(candidateTags);
        if (!OTHER_KEY.equals(definition.key()) && !validated.equals(definition.defaultActivationTags())) {
            throw new IllegalArgumentException("activationTags must match registered defaults for " + key);
        }
        return validated;
    }

    public Map<String, PreferenceKeyDefinition> definitions() {
        return definitions;
    }

    public PreferenceActivationTagRegistry activationTagRegistry() {
        return activationTagRegistry;
    }
}
