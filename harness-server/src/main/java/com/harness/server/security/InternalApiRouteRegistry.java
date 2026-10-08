package com.harness.server.security;

import com.harness.core.model.PageResponse;
import com.harness.core.security.ApiEndpointDescriptor;
import com.harness.core.security.RequestPrincipal;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import static com.harness.core.security.ApiEndpointDescriptor.ResourcePolicy.*;

/** The registered HTTP method and route template are the only endpoint identity source. */
public final class InternalApiRouteRegistry {
    private final Map<RouteKey, ApiEndpointDescriptor> routes = new LinkedHashMap<>();

    public void register(ApiEndpointDescriptor endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        RouteKey key = new RouteKey(endpoint.method(), endpoint.pathTemplate());
        if (routes.containsKey(key) || routes.values().stream()
                .anyMatch(existing -> existing.endpointKey().equals(endpoint.endpointKey()))) {
            throw new IllegalArgumentException("Duplicate endpoint registration: " + endpoint.endpointKey());
        }
        routes.put(key, endpoint);
    }

    public void route(Javalin app, String method, String path, String key, String module,
                      ApiEndpointDescriptor.ResourcePolicy policy, Handler handler) {
        register(new ApiEndpointDescriptor(key, key, method, path, module, policy));
        app.addHttpHandler(HandlerType.valueOf(method), path, handler);
    }

    public ApiEndpointDescriptor resolve(String method, String pathTemplate) {
        ApiEndpointDescriptor descriptor = routes.get(new RouteKey(method, pathTemplate));
        if (descriptor == null) throw new SecurityException("No registered policy for this endpoint");
        return descriptor;
    }

    public PageResponse<ApiEndpointDescriptor> page(String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        var items = routes.values().stream().filter(row -> row.resourcePolicy() != PUBLIC)
                .filter(row -> cursor == null || row.endpointKey().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(ApiEndpointDescriptor::endpointKey))
                .limit(limit + 1L).toList();
        return PageResponse.fromFetched(items, limit, ApiEndpointDescriptor::endpointKey);
    }

    public void check(Context context, RequestPrincipalResolver resolver,
                      BiConsumer<RequestPrincipal, ApiEndpointDescriptor> authorizer) {
        ApiEndpointDescriptor publicFile = routes.get(new RouteKey(context.method().name(), context.path()));
        if (publicFile != null && publicFile.resourcePolicy() == PUBLIC) return;
        String path = context.endpointHandlerPath();
        ApiEndpointDescriptor endpoint = resolve(context.method().name(), path == null ? context.path() : path);
        if (endpoint.resourcePolicy() == PUBLIC) return;
        if ("GET".equals(endpoint.method()) && (endpoint.resourcePolicy() == ARTIFACT || endpoint.endpointKey().equals("files.read"))) {
            context.attribute(RequestPrincipalResolver.MEDIA_AUTH_ATTRIBUTE, true);
        }
        RequestPrincipal principal = resolver.resolve(context);
        requireAuthenticated(principal, endpoint);
        authorizer.accept(principal, endpoint);
    }

    public static void requireAuthenticated(RequestPrincipal principal, ApiEndpointDescriptor endpoint) {
        if (principal.authenticationType() == RequestPrincipal.AuthenticationType.ADMIN_BOOTSTRAP) {
            if (endpoint.resourcePolicy() != BOOTSTRAP) {
                throw new SecurityException("Bootstrap credentials are restricted to API permission initialization");
            }
            return;
        }
        if (principal.authenticationType() == RequestPrincipal.AuthenticationType.ANONYMOUS) return;
        if (java.util.Set.of(USER, SESSION, TRACE, ARTIFACT).contains(endpoint.resourcePolicy())) {
            principal.requireUserId();
        }
    }

    private record RouteKey(String method, String pathTemplate) {}
}
