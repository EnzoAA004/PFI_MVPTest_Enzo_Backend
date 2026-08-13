package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.Patient;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "postgres")
public class PostgresPatientRepository implements PatientRepository {
  private final String jdbcUrl;

  @Autowired
  public PostgresPatientRepository(
      @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl) {
    this(databaseUrl, true);
  }

  public PostgresPatientRepository(String databaseUrl, boolean applyMigrations) {
    this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
    if (applyMigrations) {
      try (Connection connection = connection()) {
        new SqlMigrationRunner(SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY).apply(connection);
      } catch (Exception ex) {
        throw new IllegalStateException("Could not initialize PostgreSQL patient repository", ex);
      }
    }
  }

  @Override
  public Patient save(Patient patient) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                INSERT INTO domain_patients(id, patient_reference, created_at, updated_at)
                VALUES (?::uuid, ?, ?, ?)
                """)) {
      statement.setString(1, patient.id());
      statement.setString(2, patient.patientReference());
      statement.setTimestamp(3, Timestamp.from(patient.createdAt()));
      statement.setTimestamp(4, Timestamp.from(patient.updatedAt()));
      statement.executeUpdate();
      return patient;
    } catch (Exception ex) {
      if (hasSqlState(ex, "23505")) throw new DuplicatePatientReferenceException(ex);
      throw new IllegalStateException("Could not save patient", ex);
    }
  }

  @Override
  public Optional<Patient> findById(String patientId) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT id, patient_reference, created_at, updated_at
                FROM domain_patients
                WHERE id = ?::uuid
                """)) {
      statement.setString(1, patientId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(readPatient(rs));
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find patient", ex);
    }
  }

  @Override
  public List<Patient> searchByReferencePrefix(String query, int limit) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT id, patient_reference, created_at, updated_at
                FROM domain_patients
                WHERE lower(btrim(patient_reference)) LIKE lower(?) ESCAPE '\\'
                ORDER BY lower(btrim(patient_reference)), id
                LIMIT ?
                """)) {
      statement.setString(1, escapeLike(query.trim()) + "%");
      statement.setInt(2, limit);
      try (ResultSet rs = statement.executeQuery()) {
        List<Patient> patients = new ArrayList<>();
        while (rs.next()) patients.add(readPatient(rs));
        return patients;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not search patients", ex);
    }
  }

  @Override
  public Optional<Patient> updateReference(
      String patientId, String patientReference, Instant updatedAt) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                UPDATE domain_patients
                SET patient_reference = ?, updated_at = ?
                WHERE id = ?::uuid
                RETURNING id, patient_reference, created_at, updated_at
                """)) {
      statement.setString(1, patientReference);
      statement.setTimestamp(2, Timestamp.from(updatedAt));
      statement.setString(3, patientId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(readPatient(rs));
      }
    } catch (Exception ex) {
      if (hasSqlState(ex, "23505")) throw new DuplicatePatientReferenceException(ex);
      throw new IllegalStateException("Could not update patient", ex);
    }
  }

  private Patient readPatient(ResultSet rs) throws Exception {
    return new Patient(
        rs.getObject("id", UUID.class).toString(),
        rs.getString("patient_reference"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private boolean hasSqlState(Throwable failure, String sqlState) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof java.sql.SQLException sqlException
          && sqlState.equals(sqlException.getSQLState())) return true;
      current = current.getCause();
    }
    return false;
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
      String query = "sslmode=require";
      if (!user.isBlank()) query += "&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8);
      if (!password.isBlank())
        query += "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
      int port = uri.getPort() > 0 ? uri.getPort() : 5432;
      return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query;
    } catch (Exception ex) {
      return databaseUrl;
    }
  }
}
