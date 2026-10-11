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
        if (password == null || password.isBlank()) return null;
        var account = accounts.find(identifier).orElse(null);
        if (account == null || !account.status().equals("active") || !passwords.matches(password, account.passwordHash())) return null;
        if (account.tenantId() == null || account.identity() == null) throw new SecurityException("Trusted tenant and identity mapping is required");
        return new RequestPrincipal(account.userId(), account.tenantId(), account.identity(), RequestPrincipal.AuthenticationType.JWT);
    }

    public void initializeAdministrator(String userId, String password, String tenantId) {
        validatePassword(password);
        new RequestPrincipal(userId, tenantId, AgentContext.DEFAULT_IDENTITY, RequestPrincipal.AuthenticationType.CONSOLE_ADMIN);
        if (userId.length() > 64) throw new IllegalArgumentException("Administrator userId must contain at most 64 characters");
        if (accounts.find(userId).isPresent()) return;
        try {
            accounts.create(new MysqlUserAccountStore.Account(userId, tenantId, AgentContext.DEFAULT_IDENTITY,
                    passwords.hash(password), "active"));
        } catch (MysqlUserAccountStore.AccountConflictException e) {
            if (accounts.find(userId).isEmpty()) throw e;
        }
    }

    public MysqlUserAccountStore.Account administrator(String userId) {
        var account = accounts.find(userId).orElseThrow(() -> new SecurityException("Administrator account is unavailable"));
        if (!userId.equals(account.userId()) || !"active".equals(account.status())
                || account.tenantId() == null || account.identity() == null) throw new SecurityException("Administrator account is unavailable");
        return account;
    }

    public MysqlUserAccountStore.Account authenticateAdministrator(String userId, String password) {
        validatePassword(password);
        var account = administrator(userId);
        if (!matchesPassword(account, password)) {
            throw new SecurityException("Invalid administrator credentials");
        }
        return account;
    }

    public boolean matchesPassword(MysqlUserAccountStore.Account account, String password) {
        return passwords.matches(password, account.passwordHash());
    }

    public MysqlUserAccountStore.Account changePassword(String userId, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        validatePassword(currentPassword);
        var account = administrator(userId);
        if (!matchesPassword(account, currentPassword)) {
            throw new SecurityException("Current password is incorrect");
        }
        String newHash = passwords.hash(newPassword);
        accounts.replacePassword(userId, account.passwordHash(), newHash);
        return new MysqlUserAccountStore.Account(account.userId(), account.tenantId(), account.identity(), newHash, account.status());
    }

    private static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() > 1024) {
            throw new IllegalArgumentException("Password must contain 1 to 1024 characters");
        }
    }
}
