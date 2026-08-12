package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.domain.Patient;
import ar.edu.uade.pfi.backend.repository.InMemoryPatientRepository;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.PatientService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PatientControllerTest {
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
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
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PatientController(service, new RoleAuthorizationService(audit)))
            .setControllerAdvice(new ApiExceptionHandler(audit))
            .build();
  }

  @Test
  void createReturns201AndBackendGeneratedUuid() throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/patients")
                    .with(professional())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"patientReference\":\" PAC-001 \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.patientReference").value("PAC-001"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String id = objectMapper.readTree(body).get("id").asText();
    assertEquals(id, UUID.fromString(id).toString());
    assertEquals("PAC-001", patients.findById(id).orElseThrow().patientReference());
  }

  @Test
  void createRejectsBlankReference() throws Exception {
    mockMvc
        .perform(
            post("/api/patients")
                .with(professional())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientReference\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATIENT_REFERENCE"));
  }

  @Test
  void createRejectsExactCaseInsensitiveAndTrimEquivalentDuplicates() throws Exception {
    create("PAC-DUPLICATE");

    for (String duplicate : List.of("PAC-DUPLICATE", "pac-duplicate", " PAC-DUPLICATE ")) {
      mockMvc
          .perform(
              post("/api/patients")
                  .with(professional())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsString(
                          java.util.Map.of("patientReference", duplicate))))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("DUPLICATE_PATIENT_REFERENCE"));
    }
  }

  @Test
  void getReturnsPatientAndDistinguishesNotFoundFromInvalidUuid() throws Exception {
    Patient patient = create("PAC-GET");

    mockMvc
        .perform(get("/api/patients/{id}", patient.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(patient.id()))
        .andExpect(jsonPath("$.patientReference").value("PAC-GET"));
    mockMvc
        .perform(get("/api/patients/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
    mockMvc
        .perform(get("/api/patients/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATIENT_ID"));
    mockMvc
        .perform(get("/api/patients/1-1-1-1-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATIENT_ID"));
  }

  @Test
  void searchIsCaseInsensitivePrefixLimitedAndDeterministic() throws Exception {
    create("PAC-003");
    create("PAC-001");
    create("OTHER-001");
    create("pac-002");

    mockMvc
        .perform(get("/api/patients?query=PaC&limit=2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].patientReference").value("PAC-001"))
        .andExpect(jsonPath("$[1].patientReference").value("pac-002"));
  }

  @Test
  void updateChangesReferenceAndTimestamp() throws Exception {
    Patient patient = create("PAC-BEFORE");
    Instant before = patient.updatedAt();

    String body =
        mockMvc
            .perform(
                patch("/api/patients/{id}", patient.id())
                    .with(professional())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"patientReference\":\"PAC-AFTER\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.patientReference").value("PAC-AFTER"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode updated = objectMapper.readTree(body);
    assertEquals("PAC-AFTER", updated.get("patientReference").asText());
    Patient persisted = patients.findById(patient.id()).orElseThrow();
    assertEquals(patient.createdAt(), persisted.createdAt());
    assertNotEquals(before, persisted.updatedAt());
  }

  @Test
  void updateRejectsDuplicateAndMissingPatient() throws Exception {
    Patient first = create("PAC-UPDATE-A");
    create("PAC-UPDATE-B");

    mockMvc
        .perform(
            patch("/api/patients/{id}", first.id())
                .with(professional())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientReference\":\"pac-update-b\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_PATIENT_REFERENCE"));
    mockMvc
        .perform(
            patch("/api/patients/{id}", UUID.randomUUID())
                .with(professional())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientReference\":\"PAC-MISSING\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PATIENT_NOT_FOUND"));
  }

  private Patient create(String reference) {
    Instant now = Instant.now();
    return patients.save(new Patient(UUID.randomUUID().toString(), reference, now, now));
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
