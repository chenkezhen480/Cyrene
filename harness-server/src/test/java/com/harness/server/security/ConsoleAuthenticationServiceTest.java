package com.harness.server.security;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.*;
import com.harness.server.api.ApiResponses;
import com.harness.server.api.ApiErrorCode;
import io.javalin.Javalin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import java.net.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleAuthenticationServiceTest {
    @TempDir Path directory;
    private final AtomicReference<MysqlUserAccountStore.Account> account = new AtomicReference<>();
    private final MysqlUserAccountStore store = mock(MysqlUserAccountStore.class);
    private UserAccountService accounts;
    private final Clock clock = Clock.systemUTC();

    @BeforeEach void setup() {
        EnvConfig.init(Map.of(EnvKey.AUTH_MODE, "none", EnvKey.INTERNAL_API_AUTHORIZATION_ENABLED, "true",
                EnvKey.CONSOLE_USER, "root", EnvKey.CONSOLE_PASSWORD, "root",
                EnvKey.CONSOLE_VISA_DIR, directory.resolve("visa").toString(),
                EnvKey.CONSOLE_TOKEN_TTL_DAYS, "30", EnvKey.INTERNAL_API_ADMIN_TENANT_ID, "000000"));
        when(store.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(account.get()));
        doAnswer(invocation -> { account.set(invocation.getArgument(0)); return null; }).when(store).create(any());
        doAnswer(invocation -> {
            var current = account.get();
            if (!current.passwordHash().equals(invocation.getArgument(1))) throw new SecurityException("Account changed");
            account.set(new MysqlUserAccountStore.Account(current.userId(), current.tenantId(), current.identity(), invocation.getArgument(2), current.status()));
            return null;
        }).when(store).replacePassword(any(), any(), any());
        accounts = new UserAccountService(store, new PasswordHasher(new SecureRandom()));
    }

    @AfterEach void reset() { EnvConfig.init(Map.of()); }

    private ConsoleAuthenticationService service(Clock time) {
        return new ConsoleAuthenticationService(accounts, EnvConfig.get(), new SecureRandom(), time);
    }

    @Test void keyAndCredentialSurviveRestartAndPasswordChangeRevokesOldTokens() throws Exception {
        var service = service(clock);
        var result = service.login("root", "root");
        String token = (String) result.get("token");
        assertThat(result.get("mustChangePassword")).isEqualTo(true);
        var principal = service.authenticate(token);
        assertThat(principal.authenticationType()).isEqualTo(RequestPrincipal.AuthenticationType.CONSOLE_ADMIN);
        byte[] persisted = Files.readAllBytes(directory.resolve("visa/signing.key"));
        assertThat(persisted).hasSize(44);
        assertThat(service(clock).authenticate(token)).isEqualTo(principal);
        assertThat(Files.readAllBytes(directory.resolve("visa/signing.key"))).containsExactly(persisted);
        assertThatThrownBy(() -> service.changePassword(principal, "wrong", "new-password")).isInstanceOf(SecurityException.class);
        var changed = service.changePassword(principal, "root", "new-password");
        assertThat(changed.get("mustChangePassword")).isEqualTo(false);
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
        var restarted = service(clock);
        assertThatThrownBy(() -> restarted.login("root", "root")).isInstanceOf(SecurityException.class);
        assertThat(restarted.authenticate((String) changed.get("token"))).isEqualTo(principal);
        assertThat(restarted.login("root", "new-password").get("mustChangePassword")).isEqualTo(false);
        verify(store, times(1)).create(any());
    }

    @Test void rejectsForgeryExpiryDisabledAccountAndBusinessTokenAdministratorAccess() {
        var service = service(clock);
        String token = (String) service.login("root", "root").get("token");
        assertThatThrownBy(() -> service.login("other", "root")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.login("root", "wrong")).isInstanceOf(SecurityException.class);
        assertThat(service.authenticate("business-token")).isNull();
        assertThatThrownBy(() -> service.authenticate(token.substring(0, 20) + "A" + token.substring(21)))
                .isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
        assertThatThrownBy(() -> service(Clock.offset(clock, Duration.ofDays(31))).authenticate(token))
                .isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
        assertThatThrownBy(() -> service.changePassword(new RequestPrincipal("root", "000000", "DEFAULT",
                RequestPrincipal.AuthenticationType.JWT), "root", "changed")).isInstanceOf(SecurityException.class);
        var current = account.get();
        account.set(new MysqlUserAccountStore.Account(current.userId(), current.tenantId(), current.identity(), current.passwordHash(), "disabled"));
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
    }

    @Test void customEnvironmentAccountWorksAndCorruptKeyFailsExplicitly() throws Exception {
        EnvConfig.get().set(EnvKey.CONSOLE_USER, "operator");
        EnvConfig.get().set(EnvKey.CONSOLE_PASSWORD, "custom-initial");
        var service = service(clock);
        assertThat(service.login("operator", "custom-initial").get("userId")).isEqualTo("operator");
        Files.writeString(directory.resolve("visa/signing.key"), "not-a-key");
        assertThatThrownBy(() -> service(clock)).isInstanceOf(IllegalStateException.class);
    }

    @Test void loginCannotIssueANewPasswordCredentialFromAnEarlierPasswordSnapshot() {
        var service = service(clock);
        var original = account.get();
        var changed = new MysqlUserAccountStore.Account("root", "000000", "DEFAULT",
                new PasswordHasher(new SecureRandom()).hash("changed"), "active");
        when(store.find("root")).thenReturn(Optional.of(original), Optional.of(changed));
        String token = (String) service.login("root", "root").get("token");
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(ApiRequestAuthenticator.RequestAuthenticationException.class);
    }

    @Test void unavailableAccountStoreReturnsServiceError() {
        var service = service(clock);
        String token = (String) service.login("root", "root").get("token");
        when(store.find("root")).thenThrow(new IllegalStateException("database offline"));
        assertThatThrownBy(() -> service.authenticate(token)).isInstanceOf(ConsoleAuthenticationService.AccountStoreException.class);
    }

    @Test void realHttpLoginWorksWithNoneModeAndPermissionGuardEnabled() throws Exception {
        var service = service(clock);
        var resolver = new RequestPrincipalResolver(EnvConfig.get(), context -> null, service::authenticate);
        var routes = new InternalApiRouteRegistry();
        var permissions = new InternalApiPermissionService(mock(MysqlInternalApiPermissionStore.class), routes, true);
        var handler = new ConsoleAuthenticationHandler(service, resolver);
        var json = new ObjectMapper();
        var app = Javalin.create(config -> config.jsonMapper(new io.javalin.json.JavalinJackson(json, false)));
        try {
            app.beforeMatched(context -> routes.check(context, resolver, permissions::authorize));
            app.exception(ApiRequestAuthenticator.RequestAuthenticationException.class,
                    (error, context) -> ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, error.getMessage()));
            app.exception(SecurityException.class,
                    (error, context) -> ApiResponses.error(context, 403, ApiErrorCode.FORBIDDEN, error.getMessage()));
            routes.route(app, "POST", "/api/console/auth/login", "consoleAuth.login", "auth", PUBLIC, handler::login);
            routes.route(app, "GET", "/api/console/auth/session", "consoleAuth.session", "auth", USER, handler::session);
            routes.route(app, "PUT", "/api/console/auth/password", "consoleAuth.password", "auth", USER, handler::changePassword);
            routes.route(app, "GET", "/files/input/test", "files.read", "files", USER, context -> context.result("media"));
            app.start(0);
            String base = "http://127.0.0.1:" + app.port();
            var client = HttpClient.newHttpClient();
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/console/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"userId\":\"root\",\"password\":\"root\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.headers().firstValue("Cache-Control")).contains("no-store");
            String token = json.readTree(login.body()).get("token").asText();
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertThat(login.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "SameSite=Strict");
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/console/auth/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/console/auth/session"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/files/input/test"))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            var request = HttpRequest.newBuilder(URI.create(base + "/api/console/auth/password"))
                    .header("Content-Type", "application/json").header("Cookie", cookie)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"currentPassword\":\"root\",\"newPassword\":\"changed\"}"));
            assertThat(client.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            var changed = client.send(request.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(changed.statusCode()).isEqualTo(200);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/console/auth/session"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            String newToken = json.readTree(changed.body()).get("token").asText();
            assertThat(client.send(HttpRequest.newBuilder(URI.create(base + "/api/console/auth/session"))
                    .header("Authorization", "Bearer " + newToken).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        } finally { app.stop(); }
    }
}
