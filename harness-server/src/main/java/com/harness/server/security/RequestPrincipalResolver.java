package com.harness.server.security;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.AgentContext;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.auth.JwtUtil;
import com.harness.server.ApiRequestAuthenticator.RequestAuthenticationException;
import io.javalin.http.Context;
import io.jsonwebtoken.JwtException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.harness.core.security.RequestPrincipal.AuthenticationType.*;

/** Resolves verified identity once per HTTP request, including the WebSocket upgrade. */
public final class RequestPrincipalResolver {
    public static final String PRINCIPAL_ATTRIBUTE = RequestPrincipalResolver.class.getName();
    static final String MEDIA_AUTH_ATTRIBUTE = RequestPrincipalResolver.class.getName() + ".mediaAuth";
    public static final String MEDIA_COOKIE = "cyreneMediaAuth";
    private final EnvConfig config;
    private final String authMode;
    private final JwtUtil jwtUtil;
    private final java.util.function.Function<Context, String> trustedUserResolver;

    public RequestPrincipalResolver(EnvConfig config) {
        this(config, context -> null);
    }

    /** The injected adapter must authenticate its own user assertion; HTTP context fields are not assertions. */
    public RequestPrincipalResolver(EnvConfig config, java.util.function.Function<Context, String> trustedUserResolver) {
        this.config = config;
        this.trustedUserResolver = java.util.Objects.requireNonNull(trustedUserResolver, "trustedUserResolver");
        this.authMode = config.getString(EnvKey.AUTH_MODE, "none");
        this.jwtUtil = "jwt".equals(authMode) ? new JwtUtil() : null;
        if (!java.util.Set.of("jwt", "token", "none").contains(authMode)) {
            throw new IllegalArgumentException("Unknown auth mode: " + authMode);
        }
    }

    public RequestPrincipal resolve(Context context) {
        RequestPrincipal cached = context.attribute(PRINCIPAL_ATTRIBUTE);
        if (cached != null) return cached;
        String token = bearerToken(context);
        if (token == null && "jwt".equals(authMode) && Boolean.TRUE.equals(context.attribute(MEDIA_AUTH_ATTRIBUTE))) {
            token = context.cookie(MEDIA_COOKIE);
        }
        RequestPrincipal principal;
        if (matches(token, config.getString(EnvKey.INTERNAL_API_ADMIN_TOKEN, ""))) {
            principal = new RequestPrincipal(null,
                    config.getString(EnvKey.INTERNAL_API_ADMIN_TENANT_ID, AgentContext.DEFAULT_TENANT_ID),
                    "ADMIN_BOOTSTRAP", ADMIN_BOOTSTRAP);
        } else {
            principal = authenticate(context, token);
        }
        context.attribute(PRINCIPAL_ATTRIBUTE, principal);
        return principal;
    }

    private RequestPrincipal authenticate(Context context, String token) {
        if ("none".equals(authMode)) {
            if (config.getBool(EnvKey.INTERNAL_API_AUTHORIZATION_ENABLED, true)) {
                throw new RequestAuthenticationException("Authentication is required for protected endpoints");
            }
            return new RequestPrincipal(null, AgentContext.DEFAULT_TENANT_ID,
                    AgentContext.DEFAULT_IDENTITY, ANONYMOUS);
        }
        if (token == null || token.isBlank()) throw new RequestAuthenticationException("Missing Bearer token");
        if ("token".equals(authMode)) {
            if (!matches(token, config.requireString(EnvKey.AUTH_TOKEN))) {
                throw new RequestAuthenticationException("Invalid Bearer token");
            }
            return new RequestPrincipal(trustedUserResolver.apply(context),
                    config.getString(EnvKey.AUTH_TOKEN_TENANT_ID, AgentContext.DEFAULT_TENANT_ID),
                    config.getString(EnvKey.AUTH_TOKEN_IDENTITY, AgentContext.DEFAULT_IDENTITY), SERVICE_TOKEN);
        }
        try {
            var claims = jwtUtil.verifyTokenClaims(token);
            if (jwtUtil.shouldRefresh(claims, config.getInt(EnvKey.AUTH_JWT_REFRESH_THRESHOLD_MINUTES, 60))) {
                token = jwtUtil.refreshToken(claims);
                context.header("X-New-Token", token);
            }
            setMediaCookie(context, token);
            return jwtUtil.principal(claims);
        } catch (JwtException | IllegalArgumentException e) {
            throw new RequestAuthenticationException("Invalid JWT token", e);
        }
    }

    public static String bearerToken(Context context) {
        String header = context.header("Authorization");
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    /** Only registered media GET routes accept this cookie; write endpoints always require a Bearer token. */
    public static void setMediaCookie(Context context, String token) {
        var cookie = new io.javalin.http.Cookie(MEDIA_COOKIE, token);
        cookie.setHttpOnly(true);
        cookie.setSecure("https".equals(context.scheme()));
        cookie.setSameSite(io.javalin.http.SameSite.STRICT);
        context.cookie(cookie);
    }

    private static boolean matches(String token, String expected) {
        return token != null && expected != null && !expected.isBlank()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
