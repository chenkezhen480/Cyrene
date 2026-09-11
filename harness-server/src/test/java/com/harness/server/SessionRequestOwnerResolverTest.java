package com.harness.server;

import com.harness.input.auth.Authenticator;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRequestOwnerResolverTest {

    @Test
    void authDisabled_requiresExplicitUserAndPreservesTenantScope() {
        SessionRequestOwnerResolver resolver = new SessionRequestOwnerResolver(
                "none", mock(Authenticator.class));
        Context context = mock(Context.class);

        assertThat(resolver.resolve(context, " user-1 ", " tenant-1 "))
                .isEqualTo(new SessionRequestOwnerResolver.Owner("user-1", "tenant-1"));
        assertThatThrownBy(() -> resolver.resolve(context, " ", null))
                .isInstanceOf(SessionRequestOwnerResolver.OwnerResolutionException.class)
                .hasMessageContaining("userId is required");
    }

    @Test
    void authenticatedRequestCannotImpersonateUserOrSupplyUntrustedTenant() {
        Authenticator authenticator = mock(Authenticator.class);
        Context context = mock(Context.class);
        when(context.header("Authorization")).thenReturn("Bearer token-1");
        when(authenticator.authenticate("token-1")).thenReturn("user-1");
        SessionRequestOwnerResolver resolver = new SessionRequestOwnerResolver(
                "jwt", authenticator);

        assertThat(resolver.resolve(context, "user-1", null))
                .isEqualTo(new SessionRequestOwnerResolver.Owner("user-1", null));
        assertThatThrownBy(() -> resolver.resolve(context, "user-2", null))
                .isInstanceOf(SessionRequestOwnerResolver.OwnerResolutionException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> resolver.resolve(context, "user-1", "tenant-1"))
                .isInstanceOf(SessionRequestOwnerResolver.OwnerResolutionException.class)
                .hasMessageContaining("trusted server identity");
    }
}
