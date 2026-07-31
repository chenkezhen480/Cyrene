package com.harness.server;

import com.harness.input.auth.Authenticator;
import io.javalin.http.Context;

import java.util.Objects;

/**
 * Applies the server's existing authentication mode to graph management requests.
 * Graph-space selection is intentionally independent from user identity.
 */
final class GraphRequestAuthenticator {

    private final Authenticator authenticator;

    GraphRequestAuthenticator() {
        this(new Authenticator());
    }

    GraphRequestAuthenticator(Authenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    void authenticate(Context context) {
        String authorization = context.header("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : null;
        authenticator.authenticate(token);
    }
}
