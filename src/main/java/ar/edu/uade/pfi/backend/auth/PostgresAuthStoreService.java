package ar.edu.uade.pfi.backend.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PostgresAuthStoreService {
    private final String jdbcUrl;
    private final boolean enabled;

    public PostgresAuthStoreService(
        @Value("${pfi.persistence.mode:memory}") String persistenceMode,
        @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl
    ) {
        this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
        this.enabled = "postgres".equalsIgnoreCase(persistenceMode) && !this.jdbcUrl.isBlank();
        if (enabled) migrate();
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<DoctorAccount> findByEmail(String email) {
        if (!enabled) return Optional.empty();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified
            FROM doctor_accounts
            WHERE email = ?
            """)) {
            statement.setString(1, email);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readAccount(rs));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public DoctorAccount saveAccount(DoctorAccount account) {
        if (!enabled) return account;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO doctor_accounts(id, full_name, email, password_hash, license_number, specialty, institution, roles, created_at, verified, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (email) DO UPDATE SET
              full_name = EXCLUDED.full_name,
              password_hash = EXCLUDED.password_hash,
              license_number = EXCLUDED.license_number,
              specialty = EXCLUDED.specialty,
              institution = EXCLUDED.institution,
              roles = EXCLUDED.roles,
              verified = EXCLUDED.verified,
              updated_at = now()
            """)) {
            statement.setString(1, account.id());
            statement.setString(2, account.fullName());
            statement.setString(3, account.email());
            statement.setString(4, account.passwordHash());
            statement.setString(5, account.licenseNumber());
            statement.setString(6, account.specialty());
            statement.setString(7, account.institution());
            statement.setString(8, String.join(",", account.roles()));
            statement.setTimestamp(9, Timestamp.from(account.createdAt()));
            statement.setBoolean(10, account.verified());
            statement.executeUpdate();
        } catch (Exception ignored) {
            // Auth remains available through in-memory fallback.
        }
        return account;
    }

    public void markVerified(String email) {
        if (!enabled) return;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            UPDATE doctor_accounts SET verified = TRUE, updated_at = now() WHERE email = ?
            """)) {
            statement.setString(1, email);
            statement.executeUpdate();
        } catch (Exception ignored) {
            // In-memory account is still verified by AuthService.
        }
    }

    public void saveRefreshToken(String refreshToken, String email, Instant expiresAt) {
        if (!enabled) return;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO auth_refresh_tokens(token_hash, email, issued_at, expires_at, revoked_at)
            VALUES (?, ?, now(), ?, NULL)
            ON CONFLICT (token_hash) DO UPDATE SET
              email = EXCLUDED.email,
              expires_at = EXCLUDED.expires_at,
              revoked_at = NULL
            """)) {
            statement.setString(1, tokenHash(refreshToken));
            statement.setString(2, email);
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.executeUpdate();
        } catch (Exception ignored) {
            // In-memory refresh token fallback remains active.
        }
    }

    public Optional<String> findEmailByRefreshToken(String refreshToken) {
        if (!enabled) return Optional.empty();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT email FROM auth_refresh_tokens
            WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()
            """)) {
            statement.setString(1, tokenHash(refreshToken));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.ofNullable(rs.getString("email"));
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void revokeRefreshToken(String refreshToken) {
        if (!enabled) return;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            UPDATE auth_refresh_tokens SET revoked_at = now() WHERE token_hash = ?
            """)) {
            statement.setString(1, tokenHash(refreshToken));
            statement.executeUpdate();
        } catch (Exception ignored) {
            // Logout is best-effort for persistence fallback.
        }
    }

    private DoctorAccount readAccount(ResultSet rs) throws Exception {
        List<String> roles = Arrays.stream(rs.getString("roles").split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
        return new DoctorAccount(
            rs.getString("id"),
            rs.getString("full_name"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("license_number"),
            rs.getString("specialty"),
            rs.getString("institution"),
            roles.isEmpty() ? List.of("DOCTOR", "REVIEWER") : roles,
            rs.getTimestamp("created_at").toInstant(),
            rs.getBoolean("verified")
        );
    }

    private void migrate() {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS doctor_accounts (
                    id TEXT PRIMARY KEY,
                    full_name TEXT NOT NULL,
                    email TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    license_number TEXT NOT NULL DEFAULT '',
                    specialty TEXT NOT NULL DEFAULT '',
                    institution TEXT NOT NULL DEFAULT '',
                    roles TEXT NOT NULL DEFAULT 'DOCTOR,REVIEWER',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    verified BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
                    token_hash TEXT PRIMARY KEY,
                    email TEXT NOT NULL REFERENCES doctor_accounts(email) ON DELETE CASCADE,
                    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    expires_at TIMESTAMPTZ NOT NULL,
                    revoked_at TIMESTAMPTZ NULL
                )
                """);
            connection.createStatement().execute("""
                CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_email ON auth_refresh_tokens(email)
                """);
        } catch (Exception ignored) {
            // Service stays available with in-memory fallback.
        }
    }

    private String tokenHash(String refreshToken) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : digest) builder.append(String.format("%02x", b));
        return builder.toString();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String toJdbcUrl(String databaseUrl) {
        if (databaseUrl.isBlank()) return "";
        if (databaseUrl.startsWith("jdbc:postgresql://")) return databaseUrl;
        if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://")) return databaseUrl;
        try {
            URI uri = URI.create(databaseUrl);
            String userInfo = uri.getUserInfo();
            String user = "";
            String password = "";
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                user = parts.length > 0 ? parts[0] : "";
                password = parts.length > 1 ? parts[1] : "";
            }
            String query = "sslmode=require";
            if (!user.isBlank()) query += "&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8);
            if (!password.isBlank()) query += "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query;
        } catch (Exception ex) {
            return databaseUrl;
        }
    }
}
