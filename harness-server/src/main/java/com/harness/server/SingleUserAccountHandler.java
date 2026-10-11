package com.harness.server;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.server.api.ApiErrorCode;
import com.harness.server.api.ApiResponses;
import io.javalin.http.Context;
import java.util.Map;
import java.util.Objects;

public final class SingleUserAccountHandler {
    private final UserAccountService accounts;
    private final boolean enabled;

    public SingleUserAccountHandler(UserAccountService accounts, EnvConfig config) {
        this.accounts = Objects.requireNonNull(accounts);
        enabled = "none".equals(config.getString(EnvKey.AUTH_MODE, "none"));
    }

    public void setup(Context context) {
        if (!enabled) {
            ApiResponses.error(context, 403, ApiErrorCode.FORBIDDEN, "Initial account setup requires authentication mode none");
            return;
        }
        try {
            var request = context.bodyAsClass(AccountRequest.class);
            if (request == null || request.userId() == null) throw new IllegalArgumentException("userId is required");
            String userId = accounts.setup(request.userId().trim(), request.password());
            context.json(Map.of("userId", userId, "status", "saved"));
        } catch (IllegalArgumentException e) {
            ApiResponses.error(context, 400, ApiErrorCode.INVALID_REQUEST, e.getMessage());
        } catch (SecurityException e) {
            ApiResponses.error(context, 401, ApiErrorCode.UNAUTHORIZED, e.getMessage());
        } catch (MysqlUserAccountStore.AccountConflictException e) {
            ApiResponses.error(context, 409, ApiErrorCode.CONFLICT, e.getMessage());
        } catch (IllegalStateException e) {
            ApiResponses.error(context, 503, ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    public record AccountRequest(String userId, String password) {}
}
