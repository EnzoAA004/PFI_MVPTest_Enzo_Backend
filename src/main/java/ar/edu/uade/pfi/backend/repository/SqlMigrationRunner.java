package ar.edu.uade.pfi.backend.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SqlMigrationRunner {
    private final Path migrationsDirectory;

    public SqlMigrationRunner(Path migrationsDirectory) {
        this.migrationsDirectory = migrationsDirectory;
    }

    public List<String> apply(Connection connection) {
        try {
            ensureMigrationTable(connection);
            List<String> applied = new ArrayList<>();
            for (Path migration : migrations()) {
                String version = migration.getFileName().toString();
                if (alreadyApplied(connection, version)) continue;
                try (Statement statement = connection.createStatement()) {
                    statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
                }
                recordApplied(connection, version);
                applied.add(version);
            }
            return applied;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not apply SQL migrations", ex);
        }
    }

    private List<Path> migrations() throws IOException {
        if (!Files.isDirectory(migrationsDirectory)) return List.of();
        try (var stream = Files.list(migrationsDirectory)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted()
                .toList();
        }
    }

    private void ensureMigrationTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version TEXT PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        }
    }

    private boolean alreadyApplied(Connection connection, String version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void recordApplied(Connection connection, String version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO schema_migrations(version) VALUES (?)")) {
            statement.setString(1, version);
            statement.executeUpdate();
        }
    }
}
