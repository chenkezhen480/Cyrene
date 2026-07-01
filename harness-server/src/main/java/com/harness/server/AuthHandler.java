package com.harness.server;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import com.harness.input.auth.JwtUtil;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
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

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            log.error("MySQL JDBC Driver not found", e);
        }
    }

    public AuthHandler() {
        this.jwtUtil = new JwtUtil();
    }

    public void handle(Context ctx) {
        long start = System.currentTimeMillis();
        try {
            AuthRequest req = ctx.bodyAsClass(AuthRequest.class);

            if ((req.userId() == null || req.userId().isBlank())
                    && (req.username() == null || req.username().isBlank())) {
                ctx.status(400).json(Map.of("error", "userId or username is required"));
                return;
            }
            if (req.password() == null || req.password().isBlank()) {
                ctx.status(400).json(Map.of("error", "password is required"));
                return;
            }

            String identifier = req.userId() != null && !req.userId().isBlank()
                    ? req.userId() : req.username();
            log.debug("[Server] POST /api/auth/token: identifier={}", identifier);

            // Verify credentials against users table
            String userId = verifyCredentials(identifier, req.password());
            if (userId == null) {
                log.warn("[Server] Auth failed for identifier={}", identifier);
                ctx.status(401).json(Map.of("error", "Invalid credentials"));
                return;
            }

            // Generate JWT
            String token = jwtUtil.generateToken(userId);
            long duration = System.currentTimeMillis() - start;
            log.info("[Server] Auth success: userId={}, duration={}ms", userId, duration);

            ctx.json(Map.of(
                    "token", token,
                    "userId", userId,
                    "tokenType", "Bearer",
                    "expiresIn", 86400
            ));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[Server] Auth error after {}ms: {}", duration, e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }

    private String verifyCredentials(String identifier, String password) {
        String passwordHash = sha256(password);
        EnvConfig cfg = EnvConfig.get();
        String dbUrl = cfg.getString(EnvKey.AUDIT_DB_URL, "");
        String dbUser = cfg.getString(EnvKey.AUDIT_DB_USER, "");
        String dbPass = cfg.getString(EnvKey.AUDIT_DB_PASS, "");

        // Try matching by user_id first, then by username
        String sql = "SELECT user_id FROM users WHERE (user_id = ? OR username = ?) AND password_hash = ? AND status = 'active'";

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, identifier);
            ps.setString(2, identifier);
            ps.setString(3, passwordHash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("user_id");
                }
            }
        } catch (Exception e) {
            log.error("[Server] DB error during auth: {}", e.getMessage(), e);
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
