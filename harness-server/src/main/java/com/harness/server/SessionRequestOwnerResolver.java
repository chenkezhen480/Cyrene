package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.input.auth.Authenticator;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;

import java.util.Objects;

/** Resolves Session ownership at the authenticated HTTP boundary. */
final class SessionRequestOwnerResolver {

    private final String authMode;
    private final Authenticator authenticator;

    SessionRequestOwnerResolver() {
        this(
                EnvConfig.get().getString(EnvKey.AUTH_MODE, "none"),
                new Authenticator());
    }

    SessionRequestOwnerResolver(String authMode, Authenticator authenticator) {
        this.authMode = Objects.requireNonNull(authMode, "authMode");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    Owner resolve(Context context, String requestedUserId, String requestedTenantId) {
        RequestPrincipal principal = context.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE);
        if (principal != null && principal.authenticationType() != RequestPrincipal.AuthenticationType.ANONYMOUS) {
            String userId = principal.requireUserId();
            if ((requestedUserId != null && !requestedUserId.isBlank() && !userId.equals(requestedUserId.trim()))
                    || (requestedTenantId != null && !requestedTenantId.isBlank()
                    && !principal.tenantId().equals(requestedTenantId.trim()))) {
                throw new SecurityException("Requested owner does not match authenticated scope");
            }
            return new Owner(userId, principal.tenantId());
        }
        if ("none".equals(authMode)) {
            if (requestedUserId == null || requestedUserId.isBlank()) {
                throw new OwnerResolutionException("userId is required when authentication is disabled");
            }
            return new Owner(requestedUserId.trim(), normalizeTenant(requestedTenantId));
        }
        String authorization = context.header("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : null;
        final String authenticatedUserId;
        try {
            authenticatedUserId = authenticator.authenticate(token);
        } catch (RuntimeException e) {
            throw new OwnerResolutionException("Authentication failed", e);
        }
        if (requestedUserId != null && !requestedUserId.isBlank()
                && !authenticatedUserId.equals(requestedUserId.trim())) {
            throw new OwnerResolutionException("Authenticated user does not match requested userId");
        }
        if (requestedTenantId != null && !requestedTenantId.isBlank()) {
            throw new OwnerResolutionException(
                    "tenantId must come from a trusted server identity source");
        }
        return new Owner(authenticatedUserId, null);
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }

    record Owner(String userId, String tenantId) {
    }

    static final class OwnerResolutionException extends RuntimeException {
        OwnerResolutionException(String message) {
            super(message);
        }

        OwnerResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
