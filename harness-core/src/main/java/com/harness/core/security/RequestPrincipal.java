package com.harness.core.security;

import java.util.Objects;

/** Identity from verified credentials; never constructed from model or HTTP context JSON. */
public record RequestPrincipal(String userId, String tenantId, String identity,
                               AuthenticationType authenticationType) {
    public enum AuthenticationType { JWT, SERVICE_TOKEN, ADMIN_BOOTSTRAP, ANONYMOUS }

    public RequestPrincipal {
        tenantId = required(tenantId, "tenantId");
        identity = required(identity, "identity");
        Objects.requireNonNull(authenticationType, "authenticationType");
        if (userId != null) userId = required(userId, "userId");
        if (authenticationType == AuthenticationType.JWT && userId == null) {
            throw new IllegalArgumentException("JWT userId is required");
        }
    }

    public String requireUserId() {
        if (userId == null) throw new SecurityException("Authenticated user scope is required");
        return userId;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be a nonblank identifier of at most 128 characters");
        }
        return value;
    }
}
