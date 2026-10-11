package com.harness.server;

import com.harness.core.model.AgentContext;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserAccountServiceTest {
    @Test void initializationPersistsHashAndNeverResetsAnExistingPassword() {
        var store = mock(MysqlUserAccountStore.class);
        var passwords = new PasswordHasher(new SecureRandom());
        var service = new UserAccountService(store, passwords);
        when(store.find("root")).thenReturn(Optional.empty());
        service.initializeAdministrator("root", "root", AgentContext.DEFAULT_TENANT_ID);
        var captured = ArgumentCaptor.forClass(MysqlUserAccountStore.Account.class);
        verify(store).create(captured.capture());
        var account = captured.getValue();
        assertThat(account.passwordHash()).startsWith("pbkdf2-sha256$");
        assertThat(account.tenantId()).isEqualTo(AgentContext.DEFAULT_TENANT_ID);
        assertThat(account.identity()).isEqualTo(AgentContext.DEFAULT_IDENTITY);
        when(store.find("root")).thenReturn(Optional.of(account));
        service.initializeAdministrator("root", "another environment password", AgentContext.DEFAULT_TENANT_ID);
        verify(store, times(1)).create(any());
        assertThat(service.verify("root", "root").authenticationType()).isEqualTo(RequestPrincipal.AuthenticationType.JWT);
        assertThat(service.verify("root", "wrong")).isNull();
    }

    @Test void disabledAndUnmappedAccountsCannotSignIn() {
        var store = mock(MysqlUserAccountStore.class);
        var passwords = new PasswordHasher(new SecureRandom());
        var service = new UserAccountService(store, passwords);
        String hash = passwords.hash("original password");
        when(store.find("disabled")).thenReturn(Optional.of(new MysqlUserAccountStore.Account("disabled", "tenant", "reader", hash, "disabled")));
        assertThat(service.verify("disabled", "original password")).isNull();
        assertThatThrownBy(() -> service.administrator("disabled")).isInstanceOf(SecurityException.class);
        when(store.find("unmapped")).thenReturn(Optional.of(new MysqlUserAccountStore.Account("unmapped", null, null, hash, "active")));
        assertThatThrownBy(() -> service.verify("unmapped", "original password")).isInstanceOf(SecurityException.class);
    }

    @Test void insertsCommitAndUniqueConflictsRollbackWithoutOverwriting() throws Exception {
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

    @Test void passwordChangesCommitAndConcurrentChangesRollback() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1, 0).thenThrow(new SQLException("offline", "08000"));
        var store = new MysqlUserAccountStore(() -> connection);
        store.replacePassword("root", "original", "new");
        verify(statement).setString(1, "new");
        verify(statement).setString(3, "original");
        verify(connection).commit();
        assertThatThrownBy(() -> store.replacePassword("root", "original", "new")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.replacePassword("root", "original", "new")).isInstanceOf(IllegalStateException.class);
        verify(connection, times(2)).rollback();
        verify(connection, times(1)).commit();
    }

    @Test void invalidPasswordOrAdministratorIdDoesNotWrite() {
        var store = mock(MysqlUserAccountStore.class);
        var service = new UserAccountService(store, new PasswordHasher(new SecureRandom()));
        assertThatThrownBy(() -> service.initializeAdministrator("root", " ", "000000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.initializeAdministrator("x".repeat(65), "root", "000000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.initializeAdministrator(" root ", "root", "000000")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    @Test void wrongCurrentPasswordDoesNotMutateAccount() {
        var store = mock(MysqlUserAccountStore.class);
        var passwords = new PasswordHasher(new SecureRandom());
        when(store.find("root")).thenReturn(Optional.of(new MysqlUserAccountStore.Account("root", "000000", "DEFAULT", passwords.hash("root"), "active")));
        var service = new UserAccountService(store, passwords);
        assertThatThrownBy(() -> service.changePassword("root", "wrong", "new")).isInstanceOf(SecurityException.class);
        verify(store, never()).replacePassword(any(), any(), any());
    }
}
