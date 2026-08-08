package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunAssetContent;
import ar.edu.uade.pfi.backend.domain.RunAssetStorageDiagnostics;
import ar.edu.uade.pfi.backend.repository.SqlMigrationRunner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "postgres")
public class PostgresRunAssetContentStorage implements RunAssetContentStorage {
  static final String STORAGE_KIND = "postgres_bytea";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final String jdbcUrl;
  private final long maxBytes;

  @Autowired
  public PostgresRunAssetContentStorage(
      @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl,
      @Value("${pfi.asset-storage.max-bytes:5242880}") long maxBytes) {
    this(databaseUrl, maxBytes, true);
  }

  public PostgresRunAssetContentStorage(
      String databaseUrl, long maxBytes, boolean applyMigrations) {
    this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
    this.maxBytes = maxBytes;
    if (applyMigrations) {
      try (Connection connection = connection()) {
        new SqlMigrationRunner(SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY).apply(connection);
      } catch (Exception ex) {
        throw new IllegalStateException(
            "Could not initialize PostgreSQL run asset content storage", ex);
      }
    }
  }

  @Override
  public RunAssetContent store(RunArtifact artifact, byte[] content, String sha256) {
    return deleteOrReplace(artifact, content, sha256);
  }

  @Override
  public Optional<RunAssetContent> find(String artifactId) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT artifact_id, content, sha256, size_bytes, storage_kind, stored_at
            FROM domain_run_asset_payloads
            WHERE artifact_id = ?::uuid
            """)) {
      statement.setString(1, artifactId);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(readContent(rs)) : Optional.empty();
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find run asset payload", ex);
    }
  }

  @Override
  public boolean exists(String artifactId) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT 1 FROM domain_run_asset_payloads WHERE artifact_id = ?::uuid
            """)) {
      statement.setString(1, artifactId);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next();
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not check run asset payload", ex);
    }
  }

  @Override
  public RunAssetContent deleteOrReplace(RunArtifact artifact, byte[] content, String sha256) {
    validate(artifact, content, sha256);
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM domain_run_asset_payloads WHERE artifact_id = ?::uuid")) {
          delete.setString(1, artifact.id());
          delete.executeUpdate();
        }
        Instant storedAt = Instant.now();
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO domain_run_asset_payloads(artifact_id, content, sha256, size_bytes, storage_kind, stored_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?)
                    """)) {
          insert.setString(1, artifact.id());
          insert.setBytes(2, content);
          insert.setString(3, sha256);
          insert.setLong(4, content.length);
          insert.setString(5, STORAGE_KIND);
          insert.setTimestamp(6, Timestamp.from(storedAt));
          insert.executeUpdate();
        }
        connection.commit();
        return new RunAssetContent(
            artifact.id(), content, sha256, content.length, STORAGE_KIND, storedAt);
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not store run asset payload", ex);
    }
  }

  @Override
  public RunAssetStorageDiagnostics diagnostics() {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT
                COALESCE((SELECT count(*) FROM domain_run_asset_payloads), 0) AS stored_count,
                COALESCE((SELECT sum(size_bytes) FROM domain_run_asset_payloads), 0) AS stored_bytes,
                COALESCE((
                    SELECT count(*)
                    FROM domain_run_artifacts a
                    LEFT JOIN domain_run_asset_payloads p ON p.artifact_id = a.id
                    WHERE a.storage_status = 'stored' AND p.artifact_id IS NULL
                ), 0) AS missing_payloads
            """)) {
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return new RunAssetStorageDiagnostics(STORAGE_KIND, true, 0, 0, 0);
        return new RunAssetStorageDiagnostics(
            STORAGE_KIND,
            true,
            rs.getLong("stored_count"),
            rs.getLong("stored_bytes"),
            rs.getLong("missing_payloads"));
      }
    } catch (Exception ex) {
      return new RunAssetStorageDiagnostics(STORAGE_KIND, false, 0, 0, 0);
    }
  }

  private void validate(RunArtifact artifact, byte[] content, String sha256) {
    if (artifact == null) throw new IllegalArgumentException("Artifact is required");
    UUID.fromString(artifact.id());
    if (!isAllowedContentType(artifact))
      throw new IllegalArgumentException("Only public derived assets can be stored");
    if (content == null || content.length == 0)
      throw new IllegalArgumentException("Asset payload cannot be empty");
    if (content.length > maxBytes)
      throw new IllegalArgumentException("Asset payload exceeds max size");
    validatePayloadContract(artifact, content);
    if (sha256 == null || sha256.isBlank())
      throw new IllegalArgumentException("Asset sha256 is required");
  }

  private boolean isAllowedContentType(RunArtifact artifact) {
    if ("lumbar-3d-mesh.json".equals(artifact.assetName())) {
      return "workspace".equals(artifact.plane())
          && "application/json".equalsIgnoreCase(artifact.contentType());
    }
    return "image/png".equalsIgnoreCase(artifact.contentType());
  }

  private void validatePayloadContract(RunArtifact artifact, byte[] content) {
    if ("lumbar-3d-mesh.json".equals(artifact.assetName())) {
      validateMeshJson(content);
      return;
    }
    if (content.length < 4
        || (content[0] & 0xff) != 0x89
        || content[1] != 0x50
        || content[2] != 0x4e
        || content[3] != 0x47) {
      throw new IllegalArgumentException("PNG asset payload is invalid");
    }
  }

  private void validateMeshJson(byte[] content) {
    try {
      Map<String, Object> mesh = OBJECT_MAPPER.readValue(content, new TypeReference<>() {});
      requireText(mesh, "schemaVersion", "pfi.lumbar-geometric-proxy.v1");
      requireText(mesh, "kind", "experimental_geometric_proxy");
      requireText(mesh, "method", "dual_plane_bbox_proxy");
      requireBoolean(mesh, "anatomicalReconstruction", false);
      requireBoolean(mesh, "volumetricReconstruction", false);
      requireText(mesh, "coordinateSystem", "local_proxy_space");
      requireText(mesh, "units", "normalized");
    } catch (Exception ex) {
      throw new IllegalArgumentException("Mesh JSON asset payload is invalid", ex);
    }
  }

  private void requireText(Map<String, Object> mesh, String key, String expected) {
    if (!expected.equals(mesh.get(key))) {
      throw new IllegalArgumentException("Mesh JSON contract mismatch");
    }
  }

  private void requireBoolean(Map<String, Object> mesh, String key, boolean expected) {
    if (!Boolean.valueOf(expected).equals(mesh.get(key))) {
      throw new IllegalArgumentException("Mesh JSON contract mismatch");
    }
  }

  private RunAssetContent readContent(ResultSet rs) throws Exception {
    return new RunAssetContent(
        rs.getObject("artifact_id", UUID.class).toString(),
        rs.getBytes("content"),
        rs.getString("sha256"),
        rs.getLong("size_bytes"),
        rs.getString("storage_kind"),
        rs.getTimestamp("stored_at").toInstant());
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(jdbcUrl);
  }

  private String toJdbcUrl(String databaseUrl) {
    if (databaseUrl.isBlank()) return "";
    if (databaseUrl.startsWith("jdbc:postgresql://")) return databaseUrl;
    if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://"))
      return databaseUrl;
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
      String query = uri.getRawQuery();
      StringBuilder jdbc =
          new StringBuilder("jdbc:postgresql://")
              .append(uri.getHost())
              .append(uri.getPort() < 0 ? "" : ":" + uri.getPort())
              .append(uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath());
      String separator = "?";
      if (query != null && !query.isBlank()) {
        jdbc.append("?").append(query);
        separator = "&";
      }
      if (!user.isBlank()) {
        jdbc.append(separator)
            .append("user=")
            .append(URLEncoder.encode(user, StandardCharsets.UTF_8))
            .append("&password=")
            .append(URLEncoder.encode(password, StandardCharsets.UTF_8));
      }
      return jdbc.toString();
    } catch (RuntimeException ex) {
      return databaseUrl;
    }
  }
}
