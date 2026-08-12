package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.InMemoryPatientRepository;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.PatientService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StudyPatientControllerTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private InMemoryPatientRepository patients;
  private InMemoryStudyRepository studies;
  private AuditService audit;
  private PatientService service;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    patients = new InMemoryPatientRepository();
    studies = new InMemoryStudyRepository();
    audit = new AuditService(studies);
    service = new PatientService(patients, studies, audit);
    RoleAuthorizationService authorization = new RoleAuthorizationService(audit);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new StudyPatientController(service, authorization),
                new PatientController(service, authorization))
            .setControllerAdvice(new ApiExceptionHandler(audit))
            .build();
  }

  @Test
  void assignsNullStudyAndListsItOnlyByPatientId() throws Exception {
    Patient patient = patient("PAC-ASSIGN-A");
    Study study = study("CASE-ASSIGN", null, "LEGACY-ASSIGN");

    mockMvc
        .perform(assign(study.caseId(), patient.id(), null, "INITIAL_ASSIGNMENT"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(true))
        .andExpect(jsonPath("$.patientId").value(patient.id()));
    mockMvc
        .perform(get("/api/patients/{id}/studies", patient.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].caseId").value(study.caseId()))
        .andExpect(jsonPath("$[0].reviewPriority").value("high"));
    assertEquals(
        "LEGACY-ASSIGN", studies.findStudyByCaseId(study.caseId()).orElseThrow().subjectRef());
  }

  @Test
  void rejectsMissingEntitiesInvalidTargetAndNullTarget() throws Exception {
    Patient patient = patient("PAC-ERRORS");
    Study study = study("CASE-ERRORS", null, null);

    mockMvc
        .perform(assign(study.caseId(), UUID.randomUUID().toString(), null, "INITIAL_ASSIGNMENT"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
    mockMvc
        .perform(assign("CASE-MISSING", patient.id(), null, "INITIAL_ASSIGNMENT"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STUDY_NOT_FOUND"));
    mockMvc
        .perform(assign(study.caseId(), "invalid", null, "INITIAL_ASSIGNMENT"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATIENT_ID"));
    mockMvc
        .perform(
            put("/api/studies/{caseId}/patient", study.caseId())
                .with(professional())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"patientId\":null,\"expectedPatientId\":null,\"reason\":\"INITIAL_ASSIGNMENT\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATIENT_ID"));
  }

  @Test
  void samePatientIsIdempotentWithoutFalseAuditEvent() throws Exception {
    Patient patient = patient("PAC-IDEMPOTENT");
    Study study = study("CASE-IDEMPOTENT-PATIENT", patient.id(), null);

    mockMvc
        .perform(assign(study.caseId(), patient.id(), patient.id(), "CORRECTION"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false));

    assertTrue(audit.findByEntityId(study.id()).isEmpty());
  }

  @Test
  void reassignsWithExpectedPatientAndRejectsStaleExpected() throws Exception {
    Patient patientA = patient("PAC-REASSIGN-A");
    Patient patientB = patient("PAC-REASSIGN-B");
    Patient patientC = patient("PAC-REASSIGN-C");
    Study study = study("CASE-REASSIGN", patientA.id(), null);

    mockMvc
        .perform(assign(study.caseId(), patientB.id(), patientA.id(), "CORRECTION"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.previousPatientId").value(patientA.id()))
        .andExpect(jsonPath("$.patientId").value(patientB.id()));
    studies.updatePatientIfExpected(study.caseId(), patientC.id(), patientB.id());
    mockMvc
        .perform(assign(study.caseId(), patientB.id(), patientA.id(), "CORRECTION"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PATIENT_ASSIGNMENT_CONFLICT"));
  }

  @Test
  void reassignmentPreservesRunMetricsReviewSubjectAndCaseId() throws Exception {
    Patient patientA = patient("PAC-PRESERVE-A");
    Patient patientB = patient("PAC-PRESERVE-B");
    Study study = study("CASE-PRESERVE", patientA.id(), "LEGACY-PRESERVE");
    StudyRun run = run(study.id());
    studies.saveRun(run);

    mockMvc
        .perform(assign(study.caseId(), patientB.id(), patientA.id(), "CORRECTION"))
        .andExpect(status().isOk());

    Study recovered = studies.findStudyByCaseId(study.caseId()).orElseThrow();
    assertEquals(study.caseId(), recovered.caseId());
    assertEquals("LEGACY-PRESERVE", recovered.subjectRef());
    assertEquals(1, studies.findRunsByStudyId(study.id()).size());
    assertEquals(run, studies.findRunsByStudyId(study.id()).get(0));
    assertEquals(Map.of("disc", Map.of("L4-L5", "finding")), run.metricsSnapshot());
    assertEquals("accepted", run.reviewStatus());
  }

  @Test
  void auditEventsContainTechnicalIdsAndNoPatientReferenceOrPhi() throws Exception {
    String createdBody =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/patients")
                    .with(professional())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"patientReference\":\"PAC-AUDIT-A\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String patientAId = objectMapper.readTree(createdBody).get("id").asText();
    Patient patientB = patient("PAC-AUDIT-B");
    Study study = study("CASE-AUDIT", null, null);

    mockMvc
        .perform(assign(study.caseId(), patientAId, null, "INITIAL_ASSIGNMENT"))
        .andExpect(status().isOk());
    mockMvc
        .perform(assign(study.caseId(), patientB.id(), patientAId, "CORRECTION"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/patients/{id}", patientAId)
                .with(professional())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientReference\":\"PAC-AUDIT-CORRECTED\"}"))
        .andExpect(status().isOk());

    var patientEvents = audit.findByEntityId(patientAId);
    assertTrue(patientEvents.stream().anyMatch(event -> event.action().equals("PATIENT_CREATED")));
    assertTrue(patientEvents.stream().anyMatch(event -> event.action().equals("PATIENT_UPDATED")));
    var studyEvents = audit.findByEntityId(study.id());
    assertTrue(
        studyEvents.stream().anyMatch(event -> event.action().equals("STUDY_PATIENT_ASSIGNED")));
    assertTrue(
        studyEvents.stream().anyMatch(event -> event.action().equals("STUDY_PATIENT_REASSIGNED")));
    String metadata =
        java.util.stream.Stream.concat(patientEvents.stream(), studyEvents.stream())
            .map(event -> event.metadata().toString())
            .reduce("", String::concat);
    assertTrue(metadata.contains(patientAId));
    assertTrue(metadata.contains(study.id()));
    assertFalse(metadata.contains("PAC-AUDIT"));
    assertFalse(metadata.contains("firstName"));
  }

  private Patient patient(String reference) {
    Instant now = Instant.now();
    return patients.save(new Patient(UUID.randomUUID().toString(), reference, now, now));
  }

  private Study study(String caseId, String patientId, String subjectRef) {
    Instant now = Instant.now();
    return studies.saveStudy(
        new Study(
            UUID.randomUUID().toString(),
            caseId,
            "ready",
            patientId,
            subjectRef,
            LocalDate.parse("2026-08-12"),
            "MRI",
            "Lumbar",
            "high",
            now,
            now));
  }

  private StudyRun run(String studyId) {
    Instant now = Instant.now();
    return new StudyRun(
        UUID.randomUUID().toString(),
        studyId,
        "multi-preserved",
        "trace-preserved",
        "real",
        "real",
        "sagittal",
        "axial",
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

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder assign(
      String caseId, String targetPatientId, String expectedPatientId, String reason)
      throws Exception {
    java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("patientId", targetPatientId);
    body.put("expectedPatientId", expectedPatientId);
    body.put("reason", reason);
    return put("/api/studies/{caseId}/patient", caseId)
        .with(professional())
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(body));
  }

  private static RequestPostProcessor professional() {
    return request -> {
      request.setAttribute(
          AuthFilter.AUTH_CLAIMS_ATTRIBUTE,
          new TokenService.Claims("reviewer-id", "", "", List.of("REVIEWER")));
      return request;
    };
  }
}
