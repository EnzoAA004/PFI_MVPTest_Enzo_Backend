package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.CreatePatientRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyPatientAssignmentRequestDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.PatientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

  @Test
  void patientApiPersistenceAssignsAndReassignsStudyWithoutChangingRuns() {
    PostgresPatientRepository patients = patientRepository();
    PostgresStudyRepository studies = studyRepository();
    AuditService audit = new AuditService(studies);
    PatientService service = new PatientService(patients, studies, audit);
    String patientAId =
        service.create(new CreatePatientRequestDto("PAC-QA-A"), "reviewer-id", "trace-qa-a").id();
    String patientBId =
        service.create(new CreatePatientRequestDto("PAC-QA-B"), "reviewer-id", "trace-qa-b").id();
    Instant now = Instant.parse("2026-08-12T17:00:00Z");
    Study study =
        studies.saveStudy(
            new Study(
                UUID.randomUUID().toString(),
                "CASE-PATIENT-QA",
                "ready",
                null,
                "LEGACY-QA",
                LocalDate.parse("2026-08-12"),
                "MRI",
                "Lumbar QA",
                "high",
                now,
                now));
    StudyRun run = qaRun(study.id(), now);
    studies.saveRun(run);

    service.assignStudy(
        study.caseId(),
        new StudyPatientAssignmentRequestDto(patientAId, null, "INITIAL_ASSIGNMENT"),
        "reviewer-id",
        "trace-assign");

    assertEquals(1, service.studies(patientAId).size());
    assertEquals(study.caseId(), service.studies(patientAId).get(0).caseId());

    service.assignStudy(
        study.caseId(),
        new StudyPatientAssignmentRequestDto(patientBId, patientAId, "CORRECTION"),
        "reviewer-id",
        "trace-reassign");

    assertTrue(service.studies(patientAId).isEmpty());
    assertEquals(1, service.studies(patientBId).size());
    assertEquals(study.caseId(), service.studies(patientBId).get(0).caseId());
    Study reassigned = studies.findStudyByCaseId(study.caseId()).orElseThrow();
    assertEquals(patientBId, reassigned.patientId());
    assertEquals("LEGACY-QA", reassigned.subjectRef());
    assertEquals(study.caseId(), reassigned.caseId());
    assertEquals(run, studies.findRunsByStudyId(study.id()).get(0));
    var auditEvents = audit.findByEntityId(study.id());
    assertTrue(
        auditEvents.stream().anyMatch(event -> event.action().equals("STUDY_PATIENT_ASSIGNED")));
    assertTrue(
        auditEvents.stream().anyMatch(event -> event.action().equals("STUDY_PATIENT_REASSIGNED")));
    String metadata =
        auditEvents.stream().map(event -> event.metadata().toString()).reduce("", String::concat);
    assertTrue(metadata.contains(patientAId));
    assertTrue(metadata.contains(patientBId));
    assertFalse(metadata.contains("PAC-QA"));
  }

  @Test
  void repositorySearchesByNormalizedPrefixAndMapsDuplicateConstraint() {
    PostgresPatientRepository repository = patientRepository();
    Instant now = Instant.parse("2026-08-12T18:00:00Z");
    repository.save(new Patient(UUID.randomUUID().toString(), "PAC-SEARCH-002", now, now));
    repository.save(new Patient(UUID.randomUUID().toString(), "pac-search-001", now, now));

    List<Patient> result = repository.searchByReferencePrefix("PaC-SeArCh", 1);

    assertEquals(1, result.size());
    assertEquals("pac-search-001", result.get(0).patientReference());
    assertThrows(
        DuplicatePatientReferenceException.class,
        () ->
            repository.save(
                new Patient(UUID.randomUUID().toString(), " PAC-SEARCH-001 ", now, now)));
  }

  private StudyRun qaRun(String studyId, Instant now) {
    return new StudyRun(
        UUID.randomUUID().toString(),
        studyId,
        "multi-patient-qa",
        "trace-patient-qa",
        "real",
        "real",
        "sagittal_spider",
        "axial_t2_alkafri",
        "sag-hash",
        "ax-hash",
        "sag-run",
        "ax-run",
        Map.of("workspace", "workspace.json"),
        Map.of("disc", Map.of("L4-L5", "finding")),
        List.of(),
        "completed",
        "accepted",
        "reviewer-id",
        now,
        "reviewed",
        now,
        now);
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
