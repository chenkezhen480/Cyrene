package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.model.AgentContext;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.PasswordHasher;
import io.javalin.http.Context;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAccountServiceTest {
    @Test void singleUserPasswordsProduceTheSameTrustedJwtIdentityLater() {
        var store = mock(MysqlUserAccountStore.class);
        var passwords = new PasswordHasher(new SecureRandom());
        var service = new UserAccountService(store, passwords);
        when(store.find("fresh-user")).thenReturn(Optional.empty());
        assertThat(service.setup("fresh-user", "my password")).isEqualTo("fresh-user");
        var captured = ArgumentCaptor.forClass(MysqlUserAccountStore.Account.class);
        verify(store).create(captured.capture());
        var account = captured.getValue();
        assertThat(account.passwordHash()).startsWith("pbkdf2-sha256$");
        assertThat(account.tenantId()).isEqualTo(AgentContext.DEFAULT_TENANT_ID);
        assertThat(account.identity()).isEqualTo(AgentContext.DEFAULT_IDENTITY);
        when(store.find("fresh-user")).thenReturn(Optional.of(account));
        assertThat(service.verify("fresh-user", "my password")).isEqualTo(new RequestPrincipal(
                "fresh-user", account.tenantId(), account.identity(), RequestPrincipal.AuthenticationType.JWT));
        assertThat(service.verify("fresh-user", "wrong")).isNull();
        assertThat(service.setup("fresh-user", "my password")).isEqualTo("fresh-user");
        assertThatThrownBy(() -> service.setup("fresh-user", "overwrite attempt")).isInstanceOf(SecurityException.class);
        verify(store, times(1)).create(any());
    }

    @Test void disabledAndUnmappedAccountsCannotSignIn() {
        var store = mock(MysqlUserAccountStore.class);
        var passwords = new PasswordHasher(new SecureRandom());
        var service = new UserAccountService(store, passwords);
        String hash = passwords.hash("original password");
        when(store.find("disabled")).thenReturn(Optional.of(new MysqlUserAccountStore.Account("disabled", "tenant", "reader", hash, "disabled")));
        assertThat(service.verify("disabled", "original password")).isNull();
        assertThatThrownBy(() -> service.setup("disabled", "original password")).isInstanceOf(SecurityException.class);
        when(store.find("unmapped")).thenReturn(Optional.of(new MysqlUserAccountStore.Account("unmapped", null, null, hash, "active")));
        assertThatThrownBy(() -> service.verify("unmapped", "original password")).isInstanceOf(SecurityException.class);
    }

    @Test void accountInsertCommitsAndUniqueConflictRollsBackInsteadOfOverwriting() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        var store = new MysqlUserAccountStore(() -> connection);
        var account = new MysqlUserAccountStore.Account("new-user", "tenant", "reader", "encoded", "active");
        store.create(account);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        when(statement.executeUpdate()).thenThrow(new SQLException("duplicate", "23000", 1062));
        assertThatThrownBy(() -> store.create(account)).isInstanceOf(MysqlUserAccountStore.AccountConflictException.class);
        verify(connection).rollback();
        verify(connection, times(1)).commit();
    }

    @Test void setupEndpointRejectsJwtAndTokenModes() {
        var service = mock(UserAccountService.class);
        for (var config : java.util.List.of(Map.of("HARNESS_AUTH_MODE", "jwt"), Map.of("HARNESS_AUTH_MODE", "token"))) {
            EnvConfig.init(config);
            try {
                var context = mock(Context.class);
                when(context.status(anyInt())).thenReturn(context);
                new SingleUserAccountHandler(service, EnvConfig.get()).setup(context);
                verify(context).status(403);
                verify(context, never()).bodyAsClass(any());
            } finally { EnvConfig.init(Map.of()); }
        }
        verifyNoInteractions(service);
    }

    @Test void setupValidationDoesNotWriteInvalidPasswords() {
        var store = mock(MysqlUserAccountStore.class);
        var service = new UserAccountService(store, new PasswordHasher(new SecureRandom()));
        assertThatThrownBy(() -> service.setup("user", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setup("x".repeat(65), "password")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    @Test void setupUsesExistingNoneModeEvenWhenApiAuthorizationIsEnabled() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none", EnvKey.INTERNAL_API_AUTHORIZATION_ENABLED, "true"));
        try {
            var service = mock(UserAccountService.class);
            var context = mock(Context.class);
            when(context.bodyAsClass(SingleUserAccountHandler.AccountRequest.class)).thenReturn(
                    new SingleUserAccountHandler.AccountRequest(" fresh-user ", "password"));
            when(service.setup("fresh-user", "password")).thenReturn("fresh-user");
            new SingleUserAccountHandler(service, EnvConfig.get()).setup(context);
            verify(context).json(Map.of("userId", "fresh-user", "status", "saved"));
            verify(service).setup("fresh-user", "password");
        } finally { EnvConfig.init(Map.of()); }
    }
}
