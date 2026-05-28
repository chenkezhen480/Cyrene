package com.harness.input.auth;

import com.harness.env.EnvConfig;
import com.harness.env.EnvKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

/**
 * JWT token creation and verification utility.
 * Uses HMAC-SHA256 with secret from HARNESS_AUTH_JWT_SECRET.
 */
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);
    private static final long EXPIRATION_MS = 24 * 60 * 60 * 1000; // 24 hours

    private final SecretKey secretKey;
    private final String issuer;

    public JwtUtil() {
        EnvConfig cfg = EnvConfig.get();
        String secret = cfg.getString(EnvKey.AUTH_JWT_SECRET, "");
        this.issuer = cfg.getString(EnvKey.AUTH_JWT_ISSUER, "harness-agent");

        if (secret.isBlank()) {
            throw new IllegalStateException("HARNESS_AUTH_JWT_SECRET is required for JWT auth");
        }

        // Decode base64 secret if it's base64-encoded, otherwise use raw bytes
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("[Auth-JWT] JwtUtil initialized: issuer={}", issuer);
    }

    /**
     * Generate a JWT token for the given userId.
     */
    public String generateToken(String userId) {
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .subject(userId)
                .issuer(issuer)
                .issuedAt(new Date(now))
                .expiration(new Date(now + EXPIRATION_MS))
                .signWith(secretKey)
                .compact();
        log.debug("[Auth-JWT] Token generated for userId={}", userId);
        return token;
    }

    /**
     * Verify a JWT token and return the userId (subject).
     *
     * @throws JwtException if token is invalid or expired
     */
    public String verifyToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String userId = claims.getSubject();
        log.debug("[Auth-JWT] Token verified: userId={}", userId);
        return userId;
    }
}
