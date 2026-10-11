package com.harness.server;

import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import com.harness.server.security.ConsoleAuthenticationService;
import com.harness.server.security.RequestPrincipalResolver;
import io.javalin.http.Context;
import java.util.Objects;
import java.util.Map;

public final class ConsoleAuthenticationHandler {
    private final ConsoleAuthenticationService authentication;
    private final RequestPrincipalResolver principals;

    public ConsoleAuthenticationHandler(ConsoleAuthenticationService authentication, RequestPrincipalResolver principals) {
        this.authentication = Objects.requireNonNull(authentication);
        this.principals = Objects.requireNonNull(principals);
    }

    public void login(Context context) {
        respond(context, () -> {
            var request = context.bodyAsClass(LoginRequest.class);
            if (request == null || request.userId() == null || request.password() == null || request.password().isBlank()) {
                throw new IllegalArgumentException("Administrator userId and password are required");
            }
            return authentication.login(request.userId().trim(), request.password());
        });
    }

    public void session(Context context) {
        respond(context, () -> authentication.session(principals.resolve(context)));
    }

    public void changePassword(Context context) {
        respond(context, () -> {
            var request = context.bodyAsClass(PasswordRequest.class);
            if (request == null) throw new IllegalArgumentException("Password request is required");
            return authentication.changePassword(principals.resolve(context), request.currentPassword(), request.newPassword());
        });
    }

    private static void respond(Context context, java.util.function.Supplier<Map<String, Object>> action) {
        try {
            var response = action.get();
            if (response.containsKey("token")) RequestPrincipalResolver.setMediaCookie(context, (String) response.get("token"));
            context.header("Cache-Control", "no-store");
            context.json(response);
        } catch (IllegalArgumentException e) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (SecurityException e) {
            ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
        } catch (IllegalStateException e) {
            ApiResponses.error(context, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    public record LoginRequest(String userId, String password) {}
    public record PasswordRequest(String currentPassword, String newPassword) {}
}
