package com.harness.agent;

import com.harness.core.model.Artifact;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** One artifact delivery requirement in a sub-agent completion contract. */
public record RequiredArtifact(
        String artifactType,
        Set<String> allowedMimeTypes,
        int minCount
) {
    public RequiredArtifact {
        if (artifactType == null || artifactType.isBlank()) {
            throw new IllegalArgumentException("artifactType must not be blank");
        }
        try {
            Artifact.ArtifactType.valueOf(artifactType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported artifactType: " + artifactType, e);
        }
        if (minCount <= 0) {
            throw new IllegalArgumentException("minCount must be positive");
        }
        artifactType = artifactType.toUpperCase(Locale.ROOT);
        if (allowedMimeTypes == null) {
            allowedMimeTypes = Set.of();
        } else {
            if (allowedMimeTypes.stream().anyMatch(
                    value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "allowedMimeTypes entries must not be blank");
            }
            allowedMimeTypes = allowedMimeTypes.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
