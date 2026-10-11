package com.harness.server;

import com.harness.core.persistence.SqlConnectionProvider;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class MysqlUserAccountStore {
    private final SqlConnectionProvider connections;

    public MysqlUserAccountStore(SqlConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    public Optional<Account> find(String identifier) {
        String sql = "SELECT user_id, tenant_id, identity, password_hash, status FROM users "
                + "WHERE user_id = ? OR username = ? ORDER BY id LIMIT 2";
        try (var connection = connections.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, identifier);
            statement.setString(2, identifier);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var account = new Account(rows.getString("user_id"), rows.getString("tenant_id"),
                        rows.getString("identity"), rows.getString("password_hash"), rows.getString("status"));
                if (rows.next()) throw new SecurityException("Account identifier is ambiguous");
                return Optional.of(account);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Authentication identity store is unavailable", e);
        }
    }

    public void create(Account account) {
        String sql = "INSERT INTO users (user_id, username, password_hash, tenant_id, identity, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (var connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, account.userId());
                statement.setString(2, account.userId());
                statement.setString(3, account.passwordHash());
                statement.setString(4, account.tenantId());
                statement.setString(5, account.identity());
                statement.setString(6, account.status());
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                if ("23000".equals(e.getSQLState())) throw new AccountConflictException();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Authentication identity store is unavailable", e);
        }
    }

    public void replacePassword(String userId, String currentHash, String newHash) {
        String sql = "UPDATE users SET password_hash = ? WHERE user_id = ? AND password_hash = ? AND status = 'active'";
        try (var connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, newHash);
                statement.setString(2, userId);
                statement.setString(3, currentHash);
                if (statement.executeUpdate() != 1) throw new SecurityException("Account changed; sign in again");
                connection.commit();
            } catch (SQLException | SecurityException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Authentication identity store is unavailable", e);
        }
    }

    public record Account(String userId, String tenantId, String identity, String passwordHash, String status) {}
    public static final class AccountConflictException extends RuntimeException {
        public AccountConflictException() { super("Account already exists; its password was not changed"); }
    }
}
