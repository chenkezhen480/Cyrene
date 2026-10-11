package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.model.AgentContext;
import com.harness.core.security.RequestPrincipal;
import com.harness.input.auth.JwtUtil;
import com.harness.server.security.PasswordHasher;
import io.javalin.http.Context;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "HARNESS_TEST_MYSQL_URL", matches = "jdbc:mysql:.*")
class MysqlUserAccountStoreIT {
    @Test
    void administratorPasswordPersistsAndAuthenticatesThroughJwtWithoutStartupOverwrite() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getenv("HARNESS_TEST_MYSQL_URL"),
                System.getenv("HARNESS_TEST_MYSQL_USER"), System.getenv("HARNESS_TEST_MYSQL_PASSWORD"))) {
            String schema = Files.readString(Path.of("../sql/schema-mysql.sql"));
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS `users`");
            assertThat(start).isGreaterThanOrEqualTo(0);
            // Temporary tables shadow the real account table only on this connection.
            try (var statement = connection.createStatement()) {
                statement.execute(schema.substring(start, schema.indexOf(';', start))
                        .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE"));
            }
            Connection fixture = spy(connection);
            doNothing().when(fixture).close();
            var store = new MysqlUserAccountStore(() -> fixture);
            var accounts = new UserAccountService(store, new PasswordHasher(new SecureRandom()));
            accounts.initializeAdministrator("fresh-user", "test password", AgentContext.DEFAULT_TENANT_ID);
            var saved = store.find("fresh-user").orElseThrow();
            assertThat(saved.tenantId()).isEqualTo(AgentContext.DEFAULT_TENANT_ID);
            assertThat(saved.identity()).isEqualTo(AgentContext.DEFAULT_IDENTITY);
            assertThat(saved.passwordHash()).startsWith("pbkdf2-sha256$").doesNotContain("test password");
            accounts.initializeAdministrator("fresh-user", "replace password", AgentContext.DEFAULT_TENANT_ID);
            assertThatThrownBy(() -> accounts.changePassword("fresh-user", "wrong", "changed password"))
                    .isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> store.create(saved)).isInstanceOf(MysqlUserAccountStore.AccountConflictException.class);
            assertThat(store.find("fresh-user").orElseThrow()).isEqualTo(saved);

            EnvConfig.init(Map.of("HARNESS_AUTH_MODE", "jwt",
                    "HARNESS_AUTH_JWT_SECRET", "01234567890123456789012345678901234567890123456789",
                    "HARNESS_AUTH_JWT_ISSUER", "cyrene-test", "HARNESS_AUTH_JWT_AUDIENCE", "test-client"));
            try {
                var jwt = new JwtUtil();
                var context = mock(Context.class);
                when(context.bodyAsClass(AuthHandler.AuthRequest.class)).thenReturn(
                        new AuthHandler.AuthRequest("fresh-user", null, "test password"));
                new AuthHandler(jwt, accounts::verify).handle(context);
                var response = ArgumentCaptor.forClass(Object.class);
                verify(context).json(response.capture());
                var token = (String) ((Map<?, ?>) response.getValue()).get("token");
                assertThat(jwt.principal(jwt.verifyTokenClaims(token))).isEqualTo(new RequestPrincipal(
                        "fresh-user", saved.tenantId(), saved.identity(), RequestPrincipal.AuthenticationType.JWT));
                var rejected = mock(Context.class);
                when(rejected.status(anyInt())).thenReturn(rejected);
                when(rejected.bodyAsClass(AuthHandler.AuthRequest.class)).thenReturn(
                        new AuthHandler.AuthRequest("fresh-user", null, "wrong password"));
                new AuthHandler(jwt, accounts::verify).handle(rejected);
                verify(rejected).status(401);
            } finally { EnvConfig.init(Map.of()); }
            accounts.changePassword("fresh-user", "test password", "changed password");
            accounts.initializeAdministrator("fresh-user", "test password", AgentContext.DEFAULT_TENANT_ID);
            assertThat(accounts.verify("fresh-user", "test password")).isNull();
            assertThat(accounts.verify("fresh-user", "changed password").userId()).isEqualTo("fresh-user");
            assertThatThrownBy(() -> store.replacePassword("fresh-user", saved.passwordHash(), "stale update"))
                    .isInstanceOf(SecurityException.class);
            assertThat(accounts.verify("fresh-user", "changed password")).isNotNull();
        }
    }
}
