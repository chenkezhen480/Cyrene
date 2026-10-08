package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.auth.JwtUtil;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthHandlerTest {
    @Test
    void loginSignsOnlyTheIdentityReturnedByTheCredentialVerifier() {
        EnvConfig.init(Map.of("HARNESS_AUTH_JWT_SECRET", "01234567890123456789012345678901234567890123456789",
                "HARNESS_AUTH_JWT_ISSUER", "cyrene-test", "HARNESS_AUTH_JWT_AUDIENCE", "test-client"));
        try {
            var jwt = new JwtUtil();
            var context = mock(Context.class);
            when(context.bodyAsClass(AuthHandler.AuthRequest.class)).thenReturn(
                    new AuthHandler.AuthRequest("login-alias", null, "test-password"));
            when(context.queryParam("identity")).thenReturn("administrator");
            when(context.header("X-Identity")).thenReturn("administrator");
            var verified = new RequestPrincipal("user-1", "tenant-1", "reader", RequestPrincipal.AuthenticationType.JWT);
            new AuthHandler(jwt, (identifier, password) -> {
                assertThat(identifier).isEqualTo("login-alias");
                assertThat(password).isEqualTo("test-password");
                return verified;
            }).handle(context);
            var response = ArgumentCaptor.forClass(Object.class);
            verify(context).json(response.capture());
            var token = (String) ((Map<?, ?>) response.getValue()).get("token");
            assertThat(jwt.principal(jwt.verifyTokenClaims(token))).isEqualTo(verified);
        } finally {
            EnvConfig.init(Map.of());
        }
    }
}
