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
    /**
     * Ruta por defecto de los .sql, relativa al working directory. El Dockerfile copia
     * {@code docs/migrations} al WORKDIR justamente para que resuelva.
     */
    public static final Path DEFAULT_MIGRATIONS_DIRECTORY = Path.of("docs", "migrations");

    /**
     * Clave del advisory lock de Postgres que serializa la aplicacion de migraciones.
     *
     * <p>Es un numero arbitrario pero fijo: lo unico que importa es que todos los que
     * migran este esquema usen el mismo. Ver {@link #apply(Connection)}.
     */
    private static final long MIGRATION_LOCK_KEY = 8_921_364_501_774_233L;

    private final Path migrationsDirectory;

    public SqlMigrationRunner(Path migrationsDirectory) {
        this.migrationsDirectory = migrationsDirectory;
    }

    /**
     * Aplica las migraciones pendientes, en orden alfabetico de nombre de archivo.
     *
     * <p>Toma un advisory lock de Postgres para todo el recorrido. Dos componentes
     * distintos instancian este runner contra la misma base —el repositorio de estudios y
     * el almacenamiento de assets—, y sin lock pueden entrar a la vez: los dos leen
     * {@code schema_migrations}, los dos ven la misma migracion pendiente, y los dos la
     * ejecutan. El lock se libera solo al cerrar la conexion.
     */
    public List<String> apply(Connection connection) {
        try {
            List<Path> migrations = migrations();
            lock(connection);
            ensureMigrationTable(connection);
            List<String> applied = new ArrayList<>();
            for (Path migration : migrations) {
                String version = migration.getFileName().toString();
                if (alreadyApplied(connection, version)) continue;
                try (Statement statement = connection.createStatement()) {
                    statement.execute(Files.readString(migration, StandardCharsets.UTF_8));
                }
                recordApplied(connection, version);
                applied.add(version);
            }
            return applied;
        } catch (MigrationsUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not apply SQL migrations", ex);
        }
    }

    private void lock(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, MIGRATION_LOCK_KEY);
            statement.execute();
        }
    }

    /**
     * Los .sql a aplicar, o una excepcion.
     *
     * <p><b>Antes devolvia una lista vacia cuando el directorio no existia.</b> La ruta es
     * relativa al working directory, asi que cualquier arranque desde otra carpeta —un
     * {@code java -jar} fuera del WORKDIR del contenedor— no aplicaba ninguna migracion y
     * no avisaba: el servicio levantaba contra un esquema vacio y recien fallaba en la
     * primera consulta, con un error que no menciona migraciones. Fallar acá deja el
     * problema donde esta.
     */
    private List<Path> migrations() throws IOException {
        if (!Files.isDirectory(migrationsDirectory)) {
            throw new MigrationsUnavailableException(
                "No existe el directorio de migraciones: " + migrationsDirectory.toAbsolutePath()
                    + " (working directory: " + Path.of("").toAbsolutePath() + ")"
            );
        }
        try (var stream = Files.list(migrationsDirectory)) {
            List<Path> found = stream
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted()
                .toList();
            if (found.isEmpty()) {
                throw new MigrationsUnavailableException(
                    "El directorio de migraciones no tiene ningun .sql: " + migrationsDirectory.toAbsolutePath()
                );
            }
            return found;
        }
    }

    /** El esquema no se puede construir, y arrancar igual solo pospone el error. */
    public static class MigrationsUnavailableException extends IllegalStateException {
        public MigrationsUnavailableException(String message) {
            super(message);
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
