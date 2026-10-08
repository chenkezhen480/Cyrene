package com.harness.server.security;

import com.harness.core.env.EnvConfig;
import com.harness.server.ApiRequestAuthenticator;
import com.harness.input.auth.JwtUtil;
import io.javalin.http.Context;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import com.harness.core.model.AgentContext;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.AgentContextRequestMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestPrincipalResolverTest {
    private static final byte[] KEY = "todo13-test-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    @AfterEach
    void resetConfig() {
        EnvConfig.init(Map.of());
    }

    @Test
    void protectedRequestsRejectNoneModeUnlessAuthorizationIsExplicitlyDisabled() {
        configure("none");
        assertThatThrownBy(() -> new ApiRequestAuthenticator().authenticate(mock(Context.class)))
                .isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
    }

    @Test
    void tokenModeDoesNotSkipBearerValidation() {
        configure("token");
        assertThatThrownBy(() -> new ApiRequestAuthenticator().authenticate(mock(Context.class)))
                .isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
    }

    @Test
    void jwtRejectsWrongAudienceEvenWithAValidSignatureAndIssuer() {
        configure("jwt");
        String token = Jwts.builder().subject("user-1").issuer("test-issuer")
                .audience().add("another-service").and()
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(KEY)).compact();
        assertThatThrownBy(() -> new JwtUtil().verifyTokenClaims(token))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void verifiedIdentityOverridesAllClientScopeFieldsAndSurvivesRefresh() {
        configure("jwt");
        JwtUtil jwt = new JwtUtil();
        String token = jwt.generateToken("user-1", "tenant-1", "reader");
        var claims = jwt.verifyTokenClaims(token);
        var refreshed = jwt.verifyTokenClaims(jwt.refreshToken(claims));
        assertThat(jwt.principal(refreshed)).isEqualTo(new RequestPrincipal("user-1", "tenant-1", "reader",
                RequestPrincipal.AuthenticationType.JWT));
        Context context = mock(Context.class);
        when(context.header("Authorization")).thenReturn("Bearer " + token);
        var principal = new RequestPrincipalResolver(EnvConfig.get()).resolve(context);
        var bound = AgentContextRequestMapper.bind(AgentContext.of(Map.of(
                "userId", "other-user", "tenantId", "other-tenant", "identity", "administrator")), principal);
        assertThat(bound.userId()).isEqualTo("user-1");
        assertThat(bound.tenantId()).isEqualTo("tenant-1");
        assertThat(bound.data().get("identity")).isEqualTo("reader");
        assertThat(bound.principal()).isSameAs(principal);
        assertThat(bound.withClearedCredentials().principal()).isSameAs(principal);
    }

    @Test
    void serviceTokenHasOnlyItsConfiguredTenantAndIdentity() {
        configure("token");
        EnvConfig.get().set("HARNESS_AUTH_TOKEN_TENANT_ID", "service-tenant");
        EnvConfig.get().set("HARNESS_AUTH_TOKEN_IDENTITY", "SERVICE");
        Context context = mock(Context.class);
        when(context.header("Authorization")).thenReturn("Bearer service-test-token");
        var principal = new RequestPrincipalResolver(EnvConfig.get()).resolve(context);
        assertThat(principal.tenantId()).isEqualTo("service-tenant");
        assertThat(principal.identity()).isEqualTo("SERVICE");
        assertThatThrownBy(principal::requireUserId).isInstanceOf(SecurityException.class);
    }

    @Test
    void mediaCookieAuthenticatesOnlyExplicitMediaGetsAndCannotAuthorizeWrites() {
        configure("jwt");
        String token = new JwtUtil().generateToken("user-1", "tenant-1", "reader");
        Context media = mock(Context.class);
        when(media.cookie(RequestPrincipalResolver.MEDIA_COOKIE)).thenReturn(token);
        when(media.attribute(RequestPrincipalResolver.MEDIA_AUTH_ATTRIBUTE)).thenReturn(true);
        assertThat(new RequestPrincipalResolver(EnvConfig.get()).resolve(media).userId()).isEqualTo("user-1");
        Context write = mock(Context.class);
        when(write.cookie(RequestPrincipalResolver.MEDIA_COOKIE)).thenReturn(token);
        assertThatThrownBy(() -> new RequestPrincipalResolver(EnvConfig.get()).resolve(write))
                .isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
    }

    @Test
    void serviceUserScopeComesOnlyFromTheInjectedTrustedAdapter() {
        configure("token");
        Context context = mock(Context.class);
        when(context.header("Authorization")).thenReturn("Bearer service-test-token");
        when(context.header("X-User-Id")).thenReturn("attacker");
        var principal = new RequestPrincipalResolver(EnvConfig.get(), request -> "verified-user").resolve(context);
        assertThat(principal.userId()).isEqualTo("verified-user");
        assertThat(principal.tenantId()).isEqualTo("000000");
        var bound = AgentContextRequestMapper.bind(AgentContext.of(AgentContextRequestMapper.sanitize(
                Map.of("userId", "attacker", "tenantId", "foreign", "principal", Map.of("userId", "attacker")))), principal);
        assertThat(bound.principal()).isSameAs(principal);
        assertThat(bound.data()).doesNotContainKey("principal");
        assertThat(bound.userId()).isEqualTo("verified-user");
        assertThat(bound.tenantId()).isEqualTo("000000");
    }

    @Test
    void anonymousDevelopmentRequiresTheExplicitAuthorizationSwitch() {
        configure("none");
        EnvConfig.get().set("HARNESS_INTERNAL_API_AUTHORIZATION_ENABLED", "false");
        assertThat(new RequestPrincipalResolver(EnvConfig.get()).resolve(mock(Context.class)).authenticationType())
                .isEqualTo(RequestPrincipal.AuthenticationType.ANONYMOUS);
    }

    private void configure(String mode) {
        EnvConfig.init(Map.of(
                "HARNESS_AUTH_MODE", mode,
                "HARNESS_AUTH_TOKEN", "service-test-token",
                "HARNESS_AUTH_JWT_SECRET", Base64.getEncoder().encodeToString(KEY),
                "HARNESS_AUTH_JWT_ISSUER", "test-issuer",
                "HARNESS_AUTH_JWT_AUDIENCE", "cyrene-test",
                "HARNESS_INTERNAL_API_AUTHORIZATION_ENABLED", "true"));
    }
}
