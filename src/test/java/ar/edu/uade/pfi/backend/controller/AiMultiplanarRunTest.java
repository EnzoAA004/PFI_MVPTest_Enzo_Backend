package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.MultiplanarRunPersistenceService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class AiMultiplanarRunTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void runUsesInputIdsAndReturnsFrozenResponseFields() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(multiplanarResponse());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-1",
                      "studyMetadata": {
                        "subjectRef": "SPIDER-101",
                        "studyDate": "2026-07-26",
                        "modality": "MRI",
                        "description": "RM lumbar sagital T2",
                        "reviewPriority": "medium"
                      },
                      "sagittalInputId": "input-sag-1",
                      "axialInputId": "input-ax-1",
                      "sagittalModelKey": "sagittal_spider",
                      "axialModelKey": "axial_t2_alkafri",
                      "allowContractFallback": true,
                      "metadata": {
                        "inferenceMode": "real_baseline"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value("multi-123"))
            .andExpect(jsonPath("$.traceId").value("trace-123"))
            .andExpect(jsonPath("$.effectiveInferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.sagittal.runId").value("run-sag-123"))
            .andExpect(jsonPath("$.planes.sagittal.effectiveInferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.sagittal.inferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.sagittal.assets['overlay.png']").value("/api/ai/assets/run-sag-123/sagittal/overlay.png"))
            .andExpect(jsonPath("$.planes.axial.runId").value("run-ax-123"))
            .andExpect(jsonPath("$.planes.axial.effectiveInferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.axial.assets['mask-preview.png']").value("/api/ai/assets/run-ax-123/axial/mask-preview.png"))
            .andExpect(jsonPath("$.assets.workspace").value("workspace.json"));

        ArgumentCaptor<MultiplanarRunRequestDto> request = ArgumentCaptor.forClass(MultiplanarRunRequestDto.class);
        verify(ai).runMultiplanar(request.capture());
        assertEquals("input-sag-1", request.getValue().sagittalInputId());
        assertEquals("input-ax-1", request.getValue().axialInputId());
        assertEquals("sagittal_spider", request.getValue().sagittalModelKey());
        assertEquals("axial_t2_alkafri", request.getValue().axialModelKey());
        assertEquals(true, request.getValue().allowContractFallback());
        assertEquals(true, request.getValue().metadata().get("allowContractFallback"));
        assertEquals("real_baseline", request.getValue().metadata().get("inferenceMode"));
        String serializedTechnicalRequest = objectMapper.writeValueAsString(request.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(serializedTechnicalRequest.contains("studyMetadata"));
        org.junit.jupiter.api.Assertions.assertFalse(serializedTechnicalRequest.contains("subjectRef"));
        org.junit.jupiter.api.Assertions.assertFalse(serializedTechnicalRequest.contains("SPIDER-101"));
    }

    @Test
    void runWithFallbackDisabledPropagatesSemanticError() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "axial plane requires real_baseline; fallback disabled"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-1",
                      "sagittalInputId": "input-sag-1",
                      "axialInputId": "input-ax-1",
                      "allowContractFallback": false,
                      "metadata": {
                        "inferenceMode": "real_baseline"
                      }
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("axial plane requires real_baseline; fallback disabled"));
    }

    @Test
    void invalidSubjectRefIsRejectedBeforeCallingAiModule() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWithPersistence(ai, new InMemoryStudyRepository()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-INVALID-SUBJECT",
                      "studyMetadata": { "subjectRef": "SPIDER 101" },
                      "sagittalInputId": "input-sag-1",
                      "metadata": { "inferenceMode": "real_baseline" }
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SUBJECT_REFERENCE"));

        verifyNoInteractions(ai);
    }

    @Test
    void subjectRefConflictIsRejectedBeforeCallingAiModule() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        new StudyRunService(repository).upsertStudyMetadata("CASE-CONFLICT-RUN", "ready", new StudyMetadataDto("SPIDER-101", null, null, null, null));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWithPersistence(ai, repository))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-CONFLICT-RUN",
                      "studyMetadata": { "subjectRef": "SPIDER-999" },
                      "sagittalInputId": "input-sag-1",
                      "metadata": { "inferenceMode": "real_baseline" }
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SUBJECT_REFERENCE_CONFLICT"));

        verifyNoInteractions(ai);
    }

    @Test
    void databaseUnavailableDuringMetadataPreflightDoesNotCallAiModule() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWithPersistence(ai, new BrokenStudyRepository()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-DB-DOWN",
                      "studyMetadata": { "subjectRef": "SPIDER-101" },
                      "sagittalInputId": "input-sag-1",
                      "metadata": { "inferenceMode": "real_baseline" }
                    }
                    """))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));

        verifyNoInteractions(ai);
    }

    @Test
    void validMetadataCallsAiModuleOnceAndSendsTechnicalDtoOnly() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(multiplanarResponse());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWithPersistence(ai, new InMemoryStudyRepository()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": " CASE-VALID-META ",
                      "studyMetadata": { "subjectRef": " SPIDER-101 ", "reviewPriority": "alta" },
                      "sagittalInputId": "input-sag-1",
                      "metadata": { "inferenceMode": "real_baseline" }
                    }
                    """))
            .andExpect(status().isOk());

        ArgumentCaptor<MultiplanarRunRequestDto> request = ArgumentCaptor.forClass(MultiplanarRunRequestDto.class);
        verify(ai, times(1)).runMultiplanar(request.capture());
        assertEquals("CASE-VALID-META", request.getValue().caseId());
        String serializedTechnicalRequest = objectMapper.writeValueAsString(request.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(serializedTechnicalRequest.contains("studyMetadata"));
        org.junit.jupiter.api.Assertions.assertFalse(serializedTechnicalRequest.contains("subjectRef"));
        org.junit.jupiter.api.Assertions.assertFalse(serializedTechnicalRequest.contains("SPIDER-101"));
    }

    @Test
    void strictRealBaselineNormalizesRequestAndReturnsPresentedContract() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(contractFixture());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-001",
                      "sagittalInputId": "inp_sagittal_001",
                      "axialInputId": "inp_axial_001",
                      "sagittalModelKey": "demo_model",
                      "axialModelKey": "demo_axial",
                      "allowContractFallback": false,
                      "metadata": {
                        "inferenceMode": "real_baseline",
                        "traceId": "trace-client-001"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.planes.sagittal.effectiveInferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.sagittal.inferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.sagittal.assets['overlay.png']").value("/api/ai/assets/run-sag-001/sagittal/overlay.png"))
            .andExpect(jsonPath("$.planes.sagittal.assets['mask.npy']").doesNotExist())
            .andExpect(jsonPath("$.planes.sagittal.metadata.sourcePath").doesNotExist());

        ArgumentCaptor<MultiplanarRunRequestDto> request = ArgumentCaptor.forClass(MultiplanarRunRequestDto.class);
        verify(ai).runMultiplanar(request.capture());
        assertEquals("sagittal_spider", request.getValue().sagittalModelKey());
        assertEquals("axial_t2_alkafri", request.getValue().axialModelKey());
        assertEquals(false, request.getValue().allowContractFallback());
        assertEquals(false, request.getValue().metadata().get("allowContractFallback"));
        assertEquals("real_baseline", request.getValue().metadata().get("requestedInferenceMode"));
        assertEquals("trace-client-001", request.getValue().metadata().get("traceId"));
    }

    @Test
    void strictRealBaselineSagittalOnlyNormalizesAndCallsAiModule() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(sagittalOnlyFixture());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-001",
                      "sagittalInputId": "inp_sagittal_001",
                      "sagittalModelKey": "sagittal_spider",
                      "allowContractFallback": false,
                      "metadata": {
                        "inferenceMode": "real_baseline",
                        "axialMode": "optional_not_provided"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.effectiveInferenceMode").value("mixed"))
            .andExpect(jsonPath("$.planes.sagittal.effectiveInferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.sagittal.modelKey").value("sagittal_spider"))
            .andExpect(jsonPath("$.planes.axial").doesNotExist())
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true));

        ArgumentCaptor<MultiplanarRunRequestDto> request = ArgumentCaptor.forClass(MultiplanarRunRequestDto.class);
        verify(ai).runMultiplanar(request.capture());
        assertEquals("inp_sagittal_001", request.getValue().sagittalInputId());
        assertEquals(null, request.getValue().axialInputId());
        assertEquals("sagittal_spider", request.getValue().sagittalModelKey());
        assertEquals("axial_t2_alkafri", request.getValue().axialModelKey());
        assertEquals(false, request.getValue().allowContractFallback());
        assertEquals(false, request.getValue().metadata().get("allowContractFallback"));
        assertEquals("optional_not_provided", request.getValue().metadata().get("axialMode"));
    }

    @Test
    void strictRealBaselineRejectsMissingSagittalAmbiguousInputsAndDemoPaths() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(org.mockito.Mockito.mock(AiServiceOperations.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("", "", null, null)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("inp_sagittal_001", "", "some/path", null)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("inp_sagittal_001", "inp_axial_001", null, "some/axial/path")))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("demo/sag", "", null, null)))
            .andExpect(status().isBadRequest());
    }

    private MultiplanarRunResponseDto multiplanarResponse() {
        return new MultiplanarRunResponseDto(
            "multi-123",
            "trace-123",
            "real_baseline",
            new MultiplanarRunResponseDto.PlanesDto(
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-sag-123",
                    "sagital",
                    "sagittal_spider",
                    "completed",
                    "real_baseline",
                    Map.of("artifactHash", "sha256:sag-checkpoint"),
                    List.of(Map.of("label", "canal_lumbar")),
                    List.of(Map.of("name", "L4_left_pedicle", "x", 124.2, "y", 210.5)),
                    Map.of("canalAreaMm2", 82.4),
                    Map.of("sliceIndex", 42),
                    Map.of("overlay", "overlay.png", "maskPreview", "mask-preview.png")
                ),
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-ax-123",
                    "axial",
                    "axial_t2_alkafri",
                    "completed",
                    "real_baseline",
                    Map.of("artifactHash", "sha256:ax-checkpoint"),
                    List.of(Map.of("label", "estenosis")),
                    List.of(Map.of("name", "canal_center", "x", 93.3, "y", 118.8)),
                    Map.of("leftForamenMm", 3.1),
                    Map.of("sliceIndex", 18),
                    Map.of("overlay", "overlay.png", "maskPreview", "mask-preview.png")
                )
            ),
            Map.of("workspace", "workspace.json"),
            Map.of("status", "pendiente")
        );
    }

    private MultiplanarRunResponseDto contractFixture() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/contracts/ai-module-multiplanar-real-baseline.json"));
        return objectMapper.readValue(json, MultiplanarRunResponseDto.class);
    }

    @SuppressWarnings("unchecked")
    private MultiplanarRunResponseDto sagittalOnlyFixture() throws Exception {
        Map<String, Object> response = objectMapper.readValue(
            Files.readString(Path.of("src/test/resources/contracts/ai-module-multiplanar-real-baseline.json")),
            Map.class
        );
        response.put("effectiveInferenceMode", "mixed");
        Map<String, Object> planes = (Map<String, Object>) response.get("planes");
        planes.remove("axial");
        response.put("quality", Map.of(
            "sagittalRunReady", true,
            "axialRunReady", false,
            "dualRunReady", false
        ));
        return objectMapper.convertValue(response, MultiplanarRunResponseDto.class);
    }

    private String strictBody(String sagittalInputId, String axialInputId, String sagittalInputPath, String axialInputPath) {
        return """
            {
              "caseId": "CASE-001",
              "sagittalInputId": "%s",
              "axialInputId": "%s",
              "sagittalInputPath": %s,
              "axialInputPath": %s,
              "allowContractFallback": false,
              "metadata": {
                "inferenceMode": "real_baseline"
              }
            }
            """.formatted(
            sagittalInputId,
            axialInputId,
            sagittalInputPath == null ? "null" : "\"" + sagittalInputPath + "\"",
            axialInputPath == null ? "null" : "\"" + axialInputPath + "\""
        );
    }

    private AiMultiplanarController controllerWithPersistence(AiServiceOperations ai, StudyRepository repository) {
        return new AiMultiplanarController(
            ai,
            new MultiplanarRunPersistenceService(new StudyRunService(repository))
        );
    }

    private static class BrokenStudyRepository implements StudyRepository {
        @Override public List<Study> findAllStudies() { throw new UnsupportedOperationException(); }
        @Override public Study saveStudy(Study study) { throw new UnsupportedOperationException(); }
        @Override public InputResource saveInput(InputResource input) { throw new UnsupportedOperationException(); }
        @Override public StudyRun saveRun(StudyRun run) { throw new UnsupportedOperationException(); }
        @Override public Optional<Study> findStudyByCaseId(String caseId) { throw new IllegalStateException("postgres down"); }
        @Override public List<Study> findStudiesBySubjectRef(String subjectRef) { throw new UnsupportedOperationException(); }
        @Override public List<InputResource> findInputsByStudyId(String studyId) { throw new UnsupportedOperationException(); }
        @Override public List<StudyRun> findRunsByStudyId(String studyId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StudyRun> findLatestRunByStudyId(String studyId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StudyRun> findRunByTraceId(String traceId) { throw new UnsupportedOperationException(); }
        @Override public List<RunArtifact> findArtifactsByRunId(String studyRunId) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunArtifact> findArtifactByRunPlaneAndName(String runId, String plane, String assetName) { throw new UnsupportedOperationException(); }
        @Override public RunArtifact updateArtifactStorage(String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256) { throw new UnsupportedOperationException(); }
        @Override public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections) { throw new UnsupportedOperationException(); }
        @Override public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections, DomainAuditEvent auditEvent) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId) { throw new UnsupportedOperationException(); }
        @Override public List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId) { throw new UnsupportedOperationException(); }
        @Override public DomainAuditEvent saveAuditEvent(DomainAuditEvent event) { throw new UnsupportedOperationException(); }
        @Override public List<DomainAuditEvent> findAuditEventsByTraceId(String traceId) { throw new UnsupportedOperationException(); }
        @Override public List<DomainAuditEvent> findAuditEventsByEntityId(String entityId) { throw new UnsupportedOperationException(); }
        @Override public List<DomainAuditEvent> findAuditEventsByStudyId(String studyId) { throw new UnsupportedOperationException(); }
    }
}
