package com.harness.server;

import com.harness.core.env.MysqlConnectionPool;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.auth.JwtUtil;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/**
 * JWT token issuance endpoint.
 * POST /api/auth/token - Accepts userId or username + password, returns JWT.
 */
public class AuthHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);
    private final JwtUtil jwtUtil;
    private final java.util.function.BiFunction<String, String, RequestPrincipal> credentialsVerifier;

    public AuthHandler() {
        this(new JwtUtil(), AuthHandler::verifyCredentials);
    }

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

    private static RequestPrincipal verifyCredentials(String identifier, String password) {
        String passwordHash = sha256(password);

        // Try matching by user_id first, then by username
        String sql = "SELECT user_id, tenant_id, identity FROM users WHERE (user_id = ? OR username = ?) "
                + "AND password_hash = ? AND status = 'active' ORDER BY id LIMIT 2";

        try (Connection conn = MysqlConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, identifier);
            ps.setString(2, identifier);
            ps.setString(3, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    if (rs.getString("tenant_id") == null || rs.getString("identity") == null) {
                        throw new SecurityException("Trusted tenant and identity mapping is required");
                    }
                    RequestPrincipal principal = new RequestPrincipal(rs.getString("user_id"),
                            rs.getString("tenant_id"), rs.getString("identity"),
                            RequestPrincipal.AuthenticationType.JWT);
                    return rs.next() ? null : principal;
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Authentication identity store is unavailable", e);
        }
        return null;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public record AuthRequest(String userId, String username, String password) {}
}
