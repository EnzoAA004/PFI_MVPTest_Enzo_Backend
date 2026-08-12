package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PatientDomainMigrationCompatibilityTest {
  private static final String PATIENT_MIGRATION = "V20260812_016_patient_domain_foundation.sql";

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pfi_patient_migration")
          .withUsername("pfi")
          .withPassword("pfi");

  @Test
  void migrationPreservesExistingStudiesAndSubjectRef(@TempDir Path tempDirectory)
      throws Exception {
    Path legacyMigrations = Files.createDirectory(tempDirectory.resolve("legacy"));
    Path patientMigration = Files.createDirectory(tempDirectory.resolve("patient"));
    copyMigrationsBeforePatientFoundation(legacyMigrations, patientMigration);

    try (var connection = connection()) {
      new SqlMigrationRunner(legacyMigrations).apply(connection);
    }

    UUID legacyStudyId = UUID.randomUUID();
    UUID secondLegacyStudyId = UUID.randomUUID();
    String legacySubjectRef = "SUBJECT-LEGACY-016";
    try (var connection = connection();
        var statement =
            connection.prepareStatement(
                """
                INSERT INTO domain_studies(id, case_id, status, subject_ref)
                VALUES (?, ?, ?, ?)
                """)) {
      statement.setObject(1, legacyStudyId);
      statement.setString(2, "CASE-LEGACY-PATIENT-016");
      statement.setString(3, "ready");
      statement.setString(4, legacySubjectRef);
      statement.executeUpdate();
      statement.setObject(1, secondLegacyStudyId);
      statement.setString(2, "CASE-LEGACY-PATIENT-016-B");
      statement.setString(3, "completed");
      statement.setString(4, null);
      statement.executeUpdate();
    }

    assertEquals(2, studyCount());

    try (var connection = connection()) {
      assertEquals(
          List.of(PATIENT_MIGRATION), new SqlMigrationRunner(patientMigration).apply(connection));
    }

    assertEquals(2, studyCount());
    try (var connection = connection();
        var statement =
            connection.prepareStatement(
                """
                SELECT subject_ref, patient_id
                FROM domain_studies
                WHERE id = ?
                """)) {
      statement.setObject(1, legacyStudyId);
      try (var rs = statement.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(legacySubjectRef, rs.getString("subject_ref"));
        assertNull(rs.getObject("patient_id"));
      }
    }
    assertEquals(2, nullPatientStudyCount());
  }

  private void copyMigrationsBeforePatientFoundation(Path legacy, Path patient) throws Exception {
    try (var migrations = Files.list(SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY)) {
      for (Path migration : migrations.filter(path -> path.toString().endsWith(".sql")).toList()) {
        Path destination =
            migration.getFileName().toString().equals(PATIENT_MIGRATION) ? patient : legacy;
        Files.copy(migration, destination.resolve(migration.getFileName()));
      }
    }
  }

  private int studyCount() throws Exception {
    return count("SELECT COUNT(*) FROM domain_studies");
  }

  private int nullPatientStudyCount() throws Exception {
    return count("SELECT COUNT(*) FROM domain_studies WHERE patient_id IS NULL");
  }

  private int count(String sql) throws Exception {
    try (var connection = connection();
        var statement = connection.prepareStatement(sql);
        var rs = statement.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private java.sql.Connection connection() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }
}
