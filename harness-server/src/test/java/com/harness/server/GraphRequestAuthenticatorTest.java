package com.harness.server;

import com.harness.input.auth.Authenticator;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphRequestAuthenticatorTest {

    @Test
    void authenticatesRequestWithoutBindingIdentityToGraphSpace() {
        Authenticator authenticator = mock(Authenticator.class);
        Context context = mock(Context.class);
        when(context.header("Authorization")).thenReturn("Bearer signed-token");
        when(authenticator.authenticate("signed-token")).thenReturn("user-1");
        GraphRequestAuthenticator requestAuthenticator =
                new GraphRequestAuthenticator(authenticator);

        requestAuthenticator.authenticate(context);

        verify(authenticator).authenticate("signed-token");
    }

    @Test
    void delegatesAnonymousModeToExistingAuthenticator() {
        Authenticator authenticator = mock(Authenticator.class);
        Context context = mock(Context.class);
        when(authenticator.authenticate(null)).thenReturn("anonymous");
        GraphRequestAuthenticator requestAuthenticator =
                new GraphRequestAuthenticator(authenticator);

        requestAuthenticator.authenticate(context);

        verify(authenticator).authenticate(null);
    }
}
