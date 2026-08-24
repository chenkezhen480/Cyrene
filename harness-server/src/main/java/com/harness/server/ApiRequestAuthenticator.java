package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.input.auth.JwtUtil;
import io.javalin.http.Context;
import io.jsonwebtoken.Claims;

/** Shared HTTP authentication boundary for agent endpoints. */
public class ApiRequestAuthenticator {

    private final String authMode;
    private final JwtUtil jwtUtil;
    private final int refreshThresholdMinutes;

    public ApiRequestAuthenticator() {
        EnvConfig config = EnvConfig.get();
        this.authMode = config.getString(EnvKey.AUTH_MODE, "none");
        this.jwtUtil = "jwt".equals(authMode) ? new JwtUtil() : null;
        this.refreshThresholdMinutes = config.getInt(
                EnvKey.AUTH_JWT_REFRESH_THRESHOLD_MINUTES, 60);
    }

    public String authenticate(Context context) {
        if (!"jwt".equals(authMode)) {
            return null;
        }
        String authHeader = context.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RequestAuthenticationException("Missing Bearer token");
        }

        String rawToken = authHeader.substring(7);
        try {
            Claims claims = jwtUtil.verifyTokenClaims(rawToken);
            if (jwtUtil.shouldRefresh(claims, refreshThresholdMinutes)) {
                context.header("X-New-Token", jwtUtil.refreshToken(claims.getSubject()));
            }
            return rawToken;
        } catch (Exception e) {
            throw new RequestAuthenticationException(
                    "Invalid token: " + e.getMessage(), e);
        }
    }

    public String authMode() {
        return authMode;
    }

    public static final class RequestAuthenticationException extends RuntimeException {
        RequestAuthenticationException(String message) {
            super(message);
        }

        RequestAuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
