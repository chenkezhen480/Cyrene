package com.harness.core.security;

import java.util.Objects;

public record ApiEndpointDescriptor(String endpointKey, String name, String method,
                                    String pathTemplate, String module, ResourcePolicy resourcePolicy) {
    public enum ResourcePolicy { PUBLIC, TENANT, GLOBAL_MANAGEMENT, USER, SESSION, TRACE, ARTIFACT, BOOTSTRAP }

    public ApiEndpointDescriptor {
        for (String value : new String[]{endpointKey, name, method, pathTemplate, module}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Endpoint metadata is required");
        }
        Objects.requireNonNull(resourcePolicy, "resourcePolicy");
    }
}
