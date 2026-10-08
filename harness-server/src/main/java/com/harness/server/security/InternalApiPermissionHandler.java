package com.harness.server.security;

import com.harness.core.security.RequestPrincipal;
import io.javalin.http.Context;

import java.util.Map;
import java.util.Set;

public final class InternalApiPermissionHandler {
    private final InternalApiPermissionService permissions;
    private final MysqlInternalApiPermissionStore store;
    private final InternalApiRouteRegistry routes;
    private final RequestPrincipalResolver principals;

    public InternalApiPermissionHandler(InternalApiPermissionService permissions,
                                         MysqlInternalApiPermissionStore store,
                                         InternalApiRouteRegistry routes, RequestPrincipalResolver principals) {
        this.permissions = permissions;
        this.store = store;
        this.routes = routes;
        this.principals = principals;
    }

    public void endpoints(Context context) {
        context.json(routes.page(context.queryParam("cursor"), limit(context)));
    }

    public void list(Context context) {
        RequestPrincipal principal = principals.resolve(context);
        String tenantId = context.queryParam("tenantId");
        if (tenantId == null) tenantId = principal.tenantId();
        permissions.requireTenant(principal, tenantId);
        String cursor = context.queryParam("cursor");
        context.json(store.page(tenantId, context.queryParam("identity"),
                cursor == null || cursor.isBlank() ? 0 : Long.parseLong(cursor), limit(context)));
    }

    public void replace(Context context) {
        var request = context.bodyAsClass(PermissionRequest.class);
        if (request == null) throw new IllegalArgumentException("Permission request is required");
        permissions.replacePermissions(principals.resolve(context), request.tenantId(),
                request.identity(), request.allowedEndpointKeys());
        context.json(Map.of("tenantId", request.tenantId(), "identity", request.identity(),
                "allowedEndpointKeys", request.allowedEndpointKeys().stream().sorted().toList()));
    }

    private static int limit(Context context) {
        String value = context.queryParam("limit");
        return value == null ? 50 : Integer.parseInt(value);
    }

    public record PermissionRequest(String tenantId, String identity, Set<String> allowedEndpointKeys) {}
}
