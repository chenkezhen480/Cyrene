package com.harness.server.security;

import com.harness.core.security.ApiEndpointDescriptor;
import com.harness.core.security.RequestPrincipal;
import org.junit.jupiter.api.Test;
import com.harness.core.env.EnvConfig;
import com.harness.server.ApiRequestAuthenticator;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.net.http.WebSocket;
import java.util.concurrent.TimeUnit;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;
import static org.assertj.core.api.Assertions.*;

class InternalApiRouteRegistryTest {
    @Test
    void websocketUpgradeChecksTheVerifiedSessionPrincipalAndEndpointPermission() throws Exception {
        EnvConfig.init(Map.of("HARNESS_AUTH_MODE", "none", "HARNESS_INTERNAL_API_AUTHORIZATION_ENABLED", "true"));
        var registry = new InternalApiRouteRegistry();
        var resolver = new RequestPrincipalResolver(EnvConfig.get());
        registry.register(endpoint("realtime.connect", "GET", "/api/realtime/{sessionId}", USER));
        var app = Javalin.create();
        app.beforeMatched(ctx -> registry.check(ctx, resolver, (principal, endpoint) -> {}));
        app.wsBeforeUpgrade("/api/realtime/{sessionId}", ctx -> {
            if (!"verified-session-token".equals(ctx.queryParam("token"))) throw new SecurityException("Invalid session token");
            ctx.attribute(RequestPrincipalResolver.PRINCIPAL_ATTRIBUTE,
                    new RequestPrincipal("user-1", "tenant-1", "reader", RequestPrincipal.AuthenticationType.JWT));
            registry.check(ctx, resolver, (principal, endpoint) -> {
                if (!"realtime.connect".equals(endpoint.endpointKey())) throw new SecurityException("Permission denied");
            });
        });
        app.ws("/api/realtime/{sessionId}", ws -> ws.onConnect(ctx -> {}));
        app.exception(SecurityException.class, (e, ctx) -> ctx.status(403));
        app.exception(ApiRequestAuthenticator.RequestAuthenticationException.class, (e, ctx) -> ctx.status(401));
        try {
            app.start("127.0.0.1", 0);
            var client = HttpClient.newHttpClient();
            String url = "ws://127.0.0.1:" + app.port() + "/api/realtime/session-1?token=";
            var connection = client.newWebSocketBuilder().buildAsync(URI.create(url + "verified-session-token"),
                    new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS);
            connection.abort();
            assertThatThrownBy(() -> client.newWebSocketBuilder().buildAsync(URI.create(url + "wrong"),
                    new WebSocket.Listener() {}).get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(java.net.http.WebSocketHandshakeException.class);
        } finally {
            app.stop();
            EnvConfig.init(Map.of());
        }
    }
    @Test
    void liveRoutingRejectsUndocumentedEndpointsAndKeepsOnlyExplicitPublicFilesOpen() throws Exception {
        EnvConfig.init(Map.of("HARNESS_AUTH_MODE", "none", "HARNESS_INTERNAL_API_AUTHORIZATION_ENABLED", "true"));
        var registry = new InternalApiRouteRegistry();
        var resolver = new RequestPrincipalResolver(EnvConfig.get());
        var app = Javalin.create(config -> config.staticFiles.add("/public"));
        registry.register(endpoint("ui", "GET", "/index.html", PUBLIC));
        registry.route(app, "GET", "/api/public", "health.read", "system", PUBLIC, ctx -> ctx.result("ok"));
        registry.route(app, "GET", "/api/protected", "modelConfig.read", "model", TENANT, ctx -> ctx.result("secret"));
        app.get("/api/undocumented", ctx -> ctx.result("secret"));
        app.beforeMatched(ctx -> registry.check(ctx, resolver, (principal, endpoint) -> {}));
        app.exception(SecurityException.class, (e, ctx) -> ctx.status(403));
        app.exception(ApiRequestAuthenticator.RequestAuthenticationException.class, (e, ctx) -> ctx.status(401));
        try {
            app.start("127.0.0.1", 0);
            var client = HttpClient.newHttpClient();
            for (var testCase : Map.of("/api/public", 200, "/api/protected", 401,
                    "/api/undocumented", 403, "/index.html", 200).entrySet()) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port()
                        + testCase.getKey())).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(testCase.getKey()).isEqualTo(testCase.getValue());
            }
        } finally {
            app.stop();
            EnvConfig.init(Map.of());
        }
    }
    @Test
    void resolvesOnlyTheExactMethodAndRegisteredTemplate() {
        var registry = new InternalApiRouteRegistry();
        registry.register(endpoint("modelConfig.read", "GET", "/api/model-config", TENANT));
        registry.register(endpoint("modelConfig.update", "PUT", "/api/model-config", TENANT));
        assertThat(registry.resolve("PUT", "/api/model-config").endpointKey()).isEqualTo("modelConfig.update");
        assertThatThrownBy(() -> registry.resolve("DELETE", "/api/model-config")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> registry.resolve("GET", "/api/model-config/other")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> registry.resolve("GET", "/api/undocumented")).isInstanceOf(SecurityException.class);
    }

    @Test
    void bootstrapCannotBecomeAGeneralAdminCredential() {
        var bootstrap = new RequestPrincipal(null, "000000", "ADMIN_BOOTSTRAP",
                RequestPrincipal.AuthenticationType.ADMIN_BOOTSTRAP);
        assertThatCode(() -> InternalApiRouteRegistry.requireAuthenticated(bootstrap,
                endpoint("internalApiPermission.update", "PUT", "/api/internal-api-permissions", BOOTSTRAP)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> InternalApiRouteRegistry.requireAuthenticated(bootstrap,
                endpoint("modelConfig.update", "PUT", "/api/model-config", TENANT)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void serviceTokensWithoutUserScopeCannotAccessConversationResources() {
        var service = new RequestPrincipal(null, "tenant-1", "SERVICE",
                RequestPrincipal.AuthenticationType.SERVICE_TOKEN);
        assertThatThrownBy(() -> InternalApiRouteRegistry.requireAuthenticated(service,
                endpoint("chat.create", "POST", "/api/chat", USER))).isInstanceOf(SecurityException.class);
    }

    @Test
    void catalogPaginationHasNoDuplicateRowsAndOmitsPublicFiles() {
        var registry = new InternalApiRouteRegistry();
        registry.register(endpoint("b", "GET", "/b", TENANT));
        registry.register(endpoint("a", "GET", "/a", TENANT));
        registry.register(endpoint("public", "GET", "/", PUBLIC));
        var first = registry.page(null, 1);
        assertThat(first.items()).extracting(ApiEndpointDescriptor::endpointKey).containsExactly("a");
        assertThat(first.pageInfo().hasMore()).isTrue();
        assertThat(registry.page(first.pageInfo().nextCursor(), 1).items())
                .extracting(ApiEndpointDescriptor::endpointKey).containsExactly("b");
    }

    private static ApiEndpointDescriptor endpoint(String key, String method, String path,
                                                   ApiEndpointDescriptor.ResourcePolicy policy) {
        return new ApiEndpointDescriptor(key, key, method, path, "test", policy);
    }
}
