package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.domain.Study;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresPatientRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pfi_patient_foundation")
          .withUsername("pfi")
          .withPassword("pfi");

  @Test
  void createsAndFindsPatient() {
    PostgresPatientRepository repository = patientRepository();
    Instant now = Instant.parse("2026-08-12T12:00:00Z");
    Patient patient = new Patient(UUID.randomUUID().toString(), "PAC-001", now, now);

    repository.save(patient);

    assertEquals(patient, repository.findById(patient.id()).orElseThrow());
  }

  @Test
  void normalizedUniqueIndexRejectsCaseInsensitiveDuplicate() throws Exception {
    patientRepository();
    insertPatientDirectly(UUID.randomUUID(), "PAC-CASE-001");

    SQLException duplicate =
        assertThrows(
            SQLException.class, () -> insertPatientDirectly(UUID.randomUUID(), "pac-case-001"));

    assertEquals("23505", duplicate.getSQLState());
  }

  @Test
  void normalizedUniqueIndexRejectsDuplicateWithSurroundingSpaces() throws Exception {
    patientRepository();
    insertPatientDirectly(UUID.randomUUID(), "PAC-SPACES-001");

    SQLException duplicate =
        assertThrows(
            SQLException.class,
            () -> insertPatientDirectly(UUID.randomUUID(), "  pac-spaces-001  "));

    assertEquals("23505", duplicate.getSQLState());
  }

  @Test
  void studyAcceptsValidPatientForeignKey() {
    PostgresPatientRepository patients = patientRepository();
    PostgresStudyRepository studies = studyRepository();
    Instant now = Instant.parse("2026-08-12T13:00:00Z");
    Patient patient =
        patients.save(new Patient(UUID.randomUUID().toString(), "PAC-FK-VALID", now, now));
    Study study =
        studies.saveStudy(
            new Study(
                UUID.randomUUID().toString(),
                "CASE-PATIENT-FK-VALID",
                "ready",
                patient.id(),
                "LEGACY-SUBJECT-VALID",
                null,
                "MRI",
                "Lumbar",
                "medium",
                now,
                now));

    Study recovered = studies.findStudyByCaseId(study.caseId()).orElseThrow();

    assertEquals(patient.id(), recovered.patientId());
    assertEquals("LEGACY-SUBJECT-VALID", recovered.subjectRef());
  }

  @Test
  void studyRejectsUnknownPatientForeignKey() {
    PostgresPatientRepository patients = patientRepository();
    PostgresStudyRepository studies = studyRepository();
    Instant now = Instant.parse("2026-08-12T14:00:00Z");
    String missingPatientId = UUID.randomUUID().toString();

    IllegalStateException rejected =
        assertThrows(
            IllegalStateException.class,
            () ->
                studies.saveStudy(
                    new Study(
                        UUID.randomUUID().toString(),
                        "CASE-PATIENT-FK-MISSING",
                        "ready",
                        missingPatientId,
                        null,
                        null,
                        null,
                        null,
                        "medium",
                        now,
                        now)));

    assertEquals("23503", sqlCause(rejected).getSQLState());
    assertEquals(0, patients.findById(missingPatientId).stream().count());
  }

  @Test
  void studyAllowsNullPatientId() {
    patientRepository();
    PostgresStudyRepository studies = studyRepository();
    Instant now = Instant.parse("2026-08-12T15:00:00Z");

    Study saved =
        studies.saveStudy(
            new Study(UUID.randomUUID().toString(), "CASE-PATIENT-NULL", "ready", now, now));

    assertNull(studies.findStudyByCaseId(saved.caseId()).orElseThrow().patientId());
  }

  @Test
  void deletingPatientReferencedByStudyIsRejected() throws Exception {
    PostgresPatientRepository patients = patientRepository();
    PostgresStudyRepository studies = studyRepository();
    Instant now = Instant.parse("2026-08-12T16:00:00Z");
    Patient patient =
        patients.save(new Patient(UUID.randomUUID().toString(), "PAC-DELETE-RESTRICT", now, now));
    studies.saveStudy(
        new Study(
            UUID.randomUUID().toString(),
            "CASE-PATIENT-DELETE-RESTRICT",
            "ready",
            patient.id(),
            null,
            null,
            null,
            null,
            "medium",
            now,
            now));

    SQLException rejected =
        assertThrows(
            SQLException.class,
            () -> {
              try (var connection = connection();
                  var statement =
                      connection.prepareStatement("DELETE FROM domain_patients WHERE id = ?")) {
                statement.setObject(1, UUID.fromString(patient.id()));
                statement.executeUpdate();
              }
            });

    assertEquals("23503", rejected.getSQLState());
  }

  private PostgresPatientRepository patientRepository() {
    return new PostgresPatientRepository(jdbcUrl(), true);
  }

  private PostgresStudyRepository studyRepository() {
    return new PostgresStudyRepository(new ObjectMapper(), jdbcUrl(), true);
  }

  private void insertPatientDirectly(UUID id, String patientReference) throws Exception {
    try (var connection = connection();
        var statement =
            connection.prepareStatement(
                """
                INSERT INTO domain_patients(id, patient_reference)
                VALUES (?, ?)
                """)) {
      statement.setObject(1, id);
      statement.setString(2, patientReference);
      statement.executeUpdate();
    }
  }

  private SQLException sqlCause(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException) return sqlException;
      current = current.getCause();
    }
    throw new AssertionError("Expected SQLException cause", failure);
  }

  private java.sql.Connection connection() throws Exception {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private String jdbcUrl() {
    return postgres.getJdbcUrl()
        + "&user="
        + postgres.getUsername()
        + "&password="
        + postgres.getPassword();
  }
}
