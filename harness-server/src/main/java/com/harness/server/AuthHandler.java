package com.harness.server;

import com.harness.core.security.RequestPrincipal;
import com.harness.input.auth.JwtUtil;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * JWT token issuance endpoint.
 * POST /api/auth/token - Accepts userId or username + password, returns JWT.
 */
public class AuthHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);
    private final JwtUtil jwtUtil;
    private final java.util.function.BiFunction<String, String, RequestPrincipal> credentialsVerifier;

    public AuthHandler(JwtUtil jwtUtil,
                       java.util.function.BiFunction<String, String, RequestPrincipal> credentialsVerifier) {
        this.jwtUtil = java.util.Objects.requireNonNull(jwtUtil, "jwtUtil");
        this.credentialsVerifier = java.util.Objects.requireNonNull(credentialsVerifier, "credentialsVerifier");
    }

    public void handle(Context ctx) {
        long start = System.currentTimeMillis();
        try {
            AuthRequest req = ctx.bodyAsClass(AuthRequest.class);

            if ((req.userId() == null || req.userId().isBlank())
                    && (req.username() == null || req.username().isBlank())) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST,
                        "userId or username is required");
                return;
            }
            if (req.password() == null || req.password().isBlank()) {
                ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, "password is required");
                return;
            }

            String identifier = req.userId() != null && !req.userId().isBlank()
                    ? req.userId() : req.username();
            log.debug("[Server] POST /api/auth/token: identifier={}", identifier);

            // Verify credentials against users table
            RequestPrincipal principal = credentialsVerifier.apply(identifier, req.password());
            if (principal == null) {
                log.warn("[Server] Auth failed for identifier={}", identifier);
                ApiResponses.error(ctx, 401, ApiErrorCode.UNAUTHORIZED, "Invalid credentials");
                return;
            }

            // Generate JWT
            String userId = principal.requireUserId();
            String token = jwtUtil.generateToken(userId, principal.tenantId(), principal.identity());
            com.harness.server.security.RequestPrincipalResolver.setMediaCookie(ctx, token);
            long duration = System.currentTimeMillis() - start;
            log.info("[Server] Auth success: userId={}, duration={}ms", userId, duration);

            ctx.json(Map.of(
                    "token", token,
                    "userId", userId,
                    "tokenType", "Bearer",
                    "expiresIn", 86400
            ));
        } catch (SecurityException e) {
            ApiResponses.error(ctx, 403, ApiErrorCode.FORBIDDEN, e.getMessage());
        } catch (IllegalStateException e) {
            ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Server] Auth error after {}ms: {}", duration, e.getMessage(), e);
            ApiResponses.error(ctx, 500, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    public record AuthRequest(String userId, String username, String password) {}
}
