package com.harness.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harness.core.model.PageResponse;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.api.ApiResponses;
import com.harness.server.api.ApiErrorCode;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;
import static com.harness.core.security.RequestPrincipal.AuthenticationType.JWT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InternalApiPermissionHandlerTest {
    @Test
    void httpCheckboxContractDisablesAndEnablesWithoutBypassingScopeOrHidingFailures() throws Exception {
        var disabled = new AtomicReference<Set<String>>(Set.of());
        var store = mock(MysqlInternalApiPermissionStore.class);
        when(store.isDisabled(eq("t1"), eq("reader"), anyString()))
                .thenAnswer(call -> disabled.get().contains(call.getArgument(2)));
        doAnswer(call -> { disabled.set(Set.copyOf(call.getArgument(2))); return null; })
                .when(store).replace(eq("t1"), eq("reader"), anySet());
        when(store.page("t1", "reader", 0, 50)).thenAnswer(call -> PageResponse.fromFetched(
                disabled.get().isEmpty() ? List.<MysqlInternalApiPermissionStore.PermissionView>of()
                        : List.of(new MysqlInternalApiPermissionStore.PermissionView(1, "t1", "reader", "trace.read")),
                50, row -> Long.toString(row.id())));
        var routes = new InternalApiRouteRegistry();
        var permissions = new InternalApiPermissionService(store, routes, true);
        var principals = mock(RequestPrincipalResolver.class);
        // This fixture supplies verified principals; credential verification has its own tests.
        when(principals.resolve(any())).thenAnswer(call -> new RequestPrincipal("u1", "t1",
                "reader".equals(((io.javalin.http.Context) call.getArgument(0)).header("X-Test-Identity"))
                        ? "reader" : "manager", JWT));
        var handler = new InternalApiPermissionHandler(permissions, store, routes, principals);
        var app = Javalin.create();
        routes.route(app, "GET", "/api/traces/{id}", "trace.read", "trace", TRACE, ctx -> ctx.result("own trace"));
        routes.route(app, "GET", "/api/internal-api-permissions", "internalApiPermission.read", "permissions", BOOTSTRAP, handler::list);
        routes.route(app, "PUT", "/api/internal-api-permissions", "internalApiPermission.update", "permissions", BOOTSTRAP, handler::replace);
        app.beforeMatched(ctx -> routes.check(ctx, principals, permissions::authorize));
        app.exception(SecurityException.class, (error, ctx) -> ApiResponses.error(ctx, 403, ApiErrorCode.FORBIDDEN, error.getMessage()));
        app.exception(IllegalArgumentException.class, (error, ctx) -> ApiResponses.error(ctx, 400, ApiErrorCode.INVALID_REQUEST, error.getMessage()));
        app.exception(MysqlInternalApiPermissionStore.PermissionStoreException.class,
                (error, ctx) -> ApiResponses.error(ctx, 503, ApiErrorCode.INTERNAL_ERROR, error.getMessage()));
        try {
            app.start("127.0.0.1", 0);
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + app.port();
            assertThat(send(client, base + "/api/traces/own", "GET", "reader", null).statusCode()).isEqualTo(200);
            String deny = "{\"tenantId\":\"t1\",\"identity\":\"reader\",\"disabledEndpointKeys\":[\"trace.read\"]}";
            var response = send(client, base + "/api/internal-api-permissions", "PUT", "manager", deny);
            assertThat(response.statusCode()).isEqualTo(200);
            var json = new ObjectMapper().readTree(response.body());
            assertThat(json.path("disabledEndpointKeys").get(0).asText()).isEqualTo("trace.read");
            assertThat(json.has("allowedEndpointKeys")).isFalse();
            assertThat(send(client, base + "/api/traces/own", "GET", "reader", null).statusCode()).isEqualTo(403);
            var page = send(client, base + "/api/internal-api-permissions?tenantId=t1&identity=reader", "GET", "manager", null);
            assertThat(new ObjectMapper().readTree(page.body()).path("items").get(0).path("endpointKey").asText()).isEqualTo("trace.read");
            String enable = "{\"tenantId\":\"t1\",\"identity\":\"reader\",\"disabledEndpointKeys\":[]}";
            assertThat(send(client, base + "/api/internal-api-permissions", "PUT", "manager", enable).statusCode()).isEqualTo(200);
            assertThat(send(client, base + "/api/traces/own", "GET", "reader", null).statusCode()).isEqualTo(200);
            assertThat(send(client, base + "/api/internal-api-permissions", "PUT", "manager",
                    enable.replace("reader", "manager")).statusCode()).isEqualTo(403);
            assertThat(send(client, base + "/api/internal-api-permissions", "PUT", "manager",
                    enable.replace("t1", "t2")).statusCode()).isEqualTo(403);
            var oldContract = send(client, base + "/api/internal-api-permissions", "PUT", "manager",
                    enable.replace("disabledEndpointKeys", "allowedEndpointKeys"));
            assertThat(oldContract.statusCode()).isEqualTo(400);
            assertThat(new ObjectMapper().readTree(oldContract.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(send(client, base + "/api/internal-api-permissions", "PUT", "manager", "{").statusCode()).isEqualTo(400);
            when(store.isDisabled("t1", "reader", "trace.read"))
                    .thenThrow(new MysqlInternalApiPermissionStore.PermissionStoreException("unavailable", null));
            assertThat(send(client, base + "/api/traces/own", "GET", "reader", null).statusCode()).isEqualTo(503);
        } finally {
            app.stop();
        }
    }

    private static HttpResponse<String> send(HttpClient client, String url, String method, String identity, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("X-Test-Identity", identity)
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
