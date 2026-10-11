package com.harness.server;

import com.harness.core.model.AgentContext;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.security.PasswordHasher;
import java.util.Objects;

public final class UserAccountService {
    private final MysqlUserAccountStore accounts;
    private final PasswordHasher passwords;

    public UserAccountService(MysqlUserAccountStore accounts, PasswordHasher passwords) {
        this.accounts = Objects.requireNonNull(accounts);
        this.passwords = Objects.requireNonNull(passwords);
    }

    public RequestPrincipal verify(String identifier, String password) {
        var account = accounts.find(identifier).orElse(null);
        if (account == null || !account.status().equals("active") || !passwords.matches(password, account.passwordHash())) return null;
        if (account.tenantId() == null || account.identity() == null) {
            throw new SecurityException("Trusted tenant and identity mapping is required");
        }
        return new RequestPrincipal(account.userId(), account.tenantId(), account.identity(), RequestPrincipal.AuthenticationType.JWT);
    }

    public String setup(String userId, String password) {
        if (userId == null || userId.isBlank() || userId.length() > 64) {
            throw new IllegalArgumentException("userId must contain 1 to 64 characters");
        }
        if (password == null || password.isBlank() || password.length() > 1024) {
            throw new IllegalArgumentException("password must contain 1 to 1024 characters");
        }
        if (accounts.find(userId).isPresent()) {
            var principal = verify(userId, password);
            if (principal == null || !principal.userId().equals(userId)) throw new SecurityException("Invalid credentials; the existing password was not changed");
            return principal.userId();
        }
        accounts.create(new MysqlUserAccountStore.Account(userId, AgentContext.DEFAULT_TENANT_ID,
                AgentContext.DEFAULT_IDENTITY, passwords.hash(password), "active"));
        return userId;
    }
}
