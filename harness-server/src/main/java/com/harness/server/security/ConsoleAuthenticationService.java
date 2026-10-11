package com.harness.server.security;

import com.harness.core.env.EnvConfig;
import com.harness.core.env.EnvKey;
import com.harness.core.security.RequestPrincipal;
import com.harness.server.ApiRequestAuthenticator.RequestAuthenticationException;
import com.harness.server.UserAccountService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Console credentials are independent of business API authentication. */
public final class ConsoleAuthenticationService {
    private static final String PREFIX = "console.";
    private static final String ISSUER = "cyrene-console";
    private final UserAccountService accounts;
    private final String userId;
    private final String initialPassword;
    private final String tenantId;
    private final SecretKey signingKey;
    private final Clock clock;
    private final Duration tokenLifetime;

    public ConsoleAuthenticationService(UserAccountService accounts, EnvConfig config, SecureRandom random, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.clock = Objects.requireNonNull(clock);
        userId = config.getString(EnvKey.CONSOLE_USER, "root");
        initialPassword = config.getString(EnvKey.CONSOLE_PASSWORD, "root");
        tenantId = config.getString(EnvKey.INTERNAL_API_ADMIN_TENANT_ID, com.harness.core.model.AgentContext.DEFAULT_TENANT_ID);
        int days = config.getInt(EnvKey.CONSOLE_TOKEN_TTL_DAYS, 30);
        if (days < 1 || days > 365) throw new IllegalArgumentException(EnvKey.CONSOLE_TOKEN_TTL_DAYS + " must be between 1 and 365");
        tokenLifetime = Duration.ofDays(days);
        signingKey = loadSigningKey(Path.of(config.getString(EnvKey.CONSOLE_VISA_DIR, "./data/visa")), random);
        accounts.initializeAdministrator(userId, initialPassword, tenantId);
    }

    public String userId() { return userId; }

    public Map<String, Object> login(String identifier, String password) {
        if (!userId.equals(identifier)) throw new SecurityException("Invalid administrator credentials");
        return issue(accounts.authenticateAdministrator(identifier, password));
    }

    public Map<String, Object> session(RequestPrincipal principal) {
        requireAdministrator(principal);
        return Map.of("userId", userId, "mustChangePassword", accounts.matchesPassword(accounts.administrator(userId), initialPassword));
    }

    public Map<String, Object> changePassword(RequestPrincipal principal, String currentPassword, String newPassword) {
        requireAdministrator(principal);
        if (Objects.equals(currentPassword, newPassword)) throw new IllegalArgumentException("New password must differ from the current password");
        return issue(accounts.changePassword(userId, currentPassword, newPassword));
    }

    public RequestPrincipal authenticate(String token) {
        if (token == null || !token.startsWith(PREFIX)) return null;
        try {
            var claims = Jwts.parser().verifyWith(signingKey).requireIssuer(ISSUER)
                    .requireAudience(ISSUER).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token.substring(PREFIX.length())).getPayload();
            if (!userId.equals(claims.getSubject()) || claims.getExpiration() == null) {
                throw new RequestAuthenticationException("Invalid console credential");
            }
            var account = accounts.administrator(userId);
            String version = claims.get("passwordVersion", String.class);
            if (version == null || !MessageDigest.isEqual(version.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    fingerprint(account.passwordHash()).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw new RequestAuthenticationException("Console credential expired; sign in again");
            }
            if (!tenantId.equals(account.tenantId())) throw new RequestAuthenticationException("Invalid administrator tenant");
            return new RequestPrincipal(userId, tenantId, account.identity(), RequestPrincipal.AuthenticationType.CONSOLE_ADMIN);
        } catch (JwtException | IllegalArgumentException | SecurityException e) {
            throw new RequestAuthenticationException("Invalid or expired console credential", e);
        } catch (IllegalStateException e) {
            throw new AccountStoreException("Console account store is unavailable", e);
        }
    }

    private Map<String, Object> issue(com.harness.server.MysqlUserAccountStore.Account account) {
        if (!tenantId.equals(account.tenantId())) throw new SecurityException("Invalid administrator tenant");
        var expiresAt = clock.instant().plus(tokenLifetime);
        String token = PREFIX + Jwts.builder().subject(userId).issuer(ISSUER).audience().add(ISSUER).and()
                .claim("passwordVersion", fingerprint(account.passwordHash()))
                .issuedAt(Date.from(clock.instant())).expiration(Date.from(expiresAt)).signWith(signingKey).compact();
        return Map.of("token", token, "userId", userId, "mustChangePassword",
                accounts.matchesPassword(account, initialPassword), "expiresAt", expiresAt.toString());
    }

    private void requireAdministrator(RequestPrincipal principal) {
        if (principal == null || principal.authenticationType() != RequestPrincipal.AuthenticationType.CONSOLE_ADMIN
                || !userId.equals(principal.userId()) || !tenantId.equals(principal.tenantId())) {
            throw new SecurityException("Console administrator authentication is required");
        }
    }

    private static String fingerprint(String hash) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Credential fingerprinting is unavailable", e); }
    }

    private static synchronized SecretKey loadSigningKey(Path directory, SecureRandom random) {
        try {
            Files.createDirectories(directory);
            boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
            if (posix) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            Path file = directory.resolve("signing.key");
            if (Files.isSymbolicLink(file)) throw new IllegalStateException("Console signing key cannot be a symbolic link");
            Set<java.nio.file.OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            var attributes = posix ? new java.nio.file.attribute.FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
                    : new java.nio.file.attribute.FileAttribute<?>[0];
            try (var channel = FileChannel.open(file, options, attributes); var ignored = channel.lock()) {
                if (posix) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                if (channel.size() == 0) {
                    byte[] key = new byte[32];
                    random.nextBytes(key);
                    ByteBuffer encoded = ByteBuffer.wrap(Base64.getEncoder().encode(key));
                    while (encoded.hasRemaining()) channel.write(encoded);
                    channel.force(true);
                    return Keys.hmacShaKeyFor(key);
                }
                if (channel.size() > 128) throw new IllegalStateException("Invalid console signing key file");
                ByteBuffer encoded = ByteBuffer.allocate(Math.toIntExact(channel.size()));
                while (encoded.hasRemaining()) {
                    if (channel.read(encoded) < 0) throw new IllegalStateException("Incomplete console signing key");
                }
                return Keys.hmacShaKeyFor(Base64.getDecoder().decode(encoded.array()));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to load console signing key from " + directory, e);
        }
    }

    public static final class AccountStoreException extends RuntimeException {
        public AccountStoreException(String message, Throwable cause) { super(message, cause); }
    }
}
