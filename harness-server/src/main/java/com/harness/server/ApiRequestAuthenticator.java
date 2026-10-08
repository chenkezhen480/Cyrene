package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;

/** Shared HTTP authentication boundary for agent endpoints. */
public class ApiRequestAuthenticator {

    private final String authMode;
    private final RequestPrincipalResolver resolver;

    public ApiRequestAuthenticator() {
        this(new RequestPrincipalResolver(EnvConfig.get()));
    }

    public ApiRequestAuthenticator(RequestPrincipalResolver resolver) {
        this.resolver = java.util.Objects.requireNonNull(resolver, "resolver");
        this.authMode = EnvConfig.get().getString(EnvKey.AUTH_MODE, "none");
    }

    public String authenticate(Context context) {
        principal(context);
        return RequestPrincipalResolver.bearerToken(context);
    }

    public RequestPrincipal principal(Context context) {
        return resolver.resolve(context);
    }

    public String authMode() {
        return authMode;
    }

    public static final class RequestAuthenticationException extends RuntimeException {
        public RequestAuthenticationException(String message) {
            super(message);
        }

        public RequestAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
