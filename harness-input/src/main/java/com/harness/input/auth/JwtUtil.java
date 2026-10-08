package com.harness.input.auth;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.security.RequestPrincipal;
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
    private final String audience;

    public JwtUtil() {
        EnvConfig cfg = EnvConfig.get();
        String secret = cfg.getString(EnvKey.AUTH_JWT_SECRET, "");
        this.issuer = cfg.getString(EnvKey.AUTH_JWT_ISSUER, "harness-agent");
        this.audience = cfg.requireString(EnvKey.AUTH_JWT_AUDIENCE);

        if (secret.isBlank()) {
            throw new IllegalStateException(EnvKey.AUTH_JWT_SECRET + " is required for JWT auth");
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
    public String generateToken(String userId, String tenantId, String identity) {
        new RequestPrincipal(userId, tenantId, identity, RequestPrincipal.AuthenticationType.JWT);
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .subject(userId)
                .issuer(issuer)
                .audience().add(audience).and()
                .claim("tenantId", tenantId)
                .claim("identity", identity)
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
        Claims claims = verifyTokenClaims(token);
        String userId = claims.getSubject();
        log.debug("[Auth-JWT] Token verified: userId={}", userId);
        return userId;
    }

    /**
     * Verify a JWT token and return the full Claims.
     *
     * @throws JwtException if token is invalid or expired
     */
    public Claims verifyTokenClaims(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (claims.getExpiration() == null) throw new JwtException("JWT expiration is required");
        try {
            principal(claims);
        } catch (IllegalArgumentException e) {
            throw new JwtException("JWT trusted identity claims are missing or invalid", e);
        }
        return claims;
    }

    /**
     * Check if a token should be refreshed based on remaining lifetime.
     *
     * @param claims           the parsed claims from the token
     * @param thresholdMinutes if remaining lifetime is less than this, refresh is needed
     * @return true if the token should be refreshed
     */
    public boolean shouldRefresh(Claims claims, int thresholdMinutes) {
        Date expiration = claims.getExpiration();
        if (expiration == null) return false;
        long remainingMs = expiration.getTime() - System.currentTimeMillis();
        long thresholdMs = thresholdMinutes * 60L * 1000L;
        boolean needsRefresh = remainingMs < thresholdMs;
        if (needsRefresh) {
            log.debug("[Auth-JWT] Token refresh needed: remainingMs={}, thresholdMs={}", remainingMs, thresholdMs);
        }
        return needsRefresh;
    }

    /**
     * Generate a refreshed JWT token for the given userId, preserving the original expiration duration.
     */
    public String refreshToken(Claims claims) {
        var principal = principal(claims);
        return generateToken(principal.userId(), principal.tenantId(), principal.identity());
    }

    public RequestPrincipal principal(Claims claims) {
        return new RequestPrincipal(claims.getSubject(),
                claims.get("tenantId", String.class), claims.get("identity", String.class),
                RequestPrincipal.AuthenticationType.JWT);
    }
}
