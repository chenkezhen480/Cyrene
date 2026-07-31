package com.harness.input.auth;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token/JWT authentication for incoming requests.
 * Configured via HARNESS_AUTH_* environment variables.
 */
public class Authenticator {

    private static final Logger log = LoggerFactory.getLogger(Authenticator.class);
    private final String mode;
    private final JwtUtil jwtUtil;

    public Authenticator() {
        this.mode = EnvConfig.get().getString(EnvKey.AUTH_MODE, "none");
        this.jwtUtil = "jwt".equals(mode) ? new JwtUtil() : null;
//        log.info("[L1-Auth] Initialized: mode={}", mode);
        if ("none".equals(mode)) {
            log.warn("[L1-Auth] Auth disabled (mode=none), all requests will be anonymous");
        }
    }

    /**
     * Validate an incoming auth token. Returns userId if valid.
     *
     * @throws com.harness.core.exception.AgentException if auth fails
     */
    public String authenticate(String token) {
        log.debug("[L1-Auth] Authenticating with mode={}", mode);
        return switch (mode) {
            case "none" -> "anonymous";
            case "token" -> authenticateToken(token);
            case "jwt" -> authenticateJwt(token);
            default -> throw new IllegalStateException("Unknown auth mode: " + mode);
        };
    }

    private String authenticateToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("[L1-Auth] Token auth failed: missing token");
            throw new com.harness.core.exception.AgentException("Missing auth token");
        }
        String expected = EnvConfig.get().requireString(EnvKey.AUTH_TOKEN);
        if (!expected.equals(token)) {
            log.warn("[L1-Auth] Token auth failed: invalid token");
            throw new com.harness.core.exception.AgentException("Invalid auth token");
        }
        log.debug("[L1-Auth] Token auth succeeded");
        return "token-user";
    }

    private String authenticateJwt(String token) {
        if (token == null || token.isBlank()) {
            log.warn("[L1-Auth] JWT auth failed: missing token");
            throw new com.harness.core.exception.AgentException("Missing JWT token");
        }
        try {
            String userId = jwtUtil.verifyToken(token);
            log.debug("[L1-Auth] JWT auth succeeded: userId={}", userId);
            return userId;
        } catch (Exception e) {
            log.warn("[L1-Auth] JWT auth failed: {}", e.getMessage());
            throw new com.harness.core.exception.AgentException("Invalid JWT token: " + e.getMessage());
        }
    }
}
