package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiMultiplanarV1ResponseAdapter;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyDetailResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.MultiplanarRunPersistenceService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import ar.edu.uade.pfi.backend.service.StudyWorklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    void contractFallbackDoesNotExposeInternalExceptionMessage() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.getMultiplanarContract()).thenThrow(new RuntimeException("http://ai-module.internal:8000 refused C:\\secret\\stack"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(get("/api/ai/multiplanar/contract"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("AI Module no disponible."))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ai-module.internal"))))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("C:\\"))));
    }

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
            .andExpect(jsonPath("$.planes.axial.assets['mask-preview.png']").value("/api/ai/assets/run-ax-123/axial/mask-preview.png"));

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
    void strictValidationFailureNeverPersistsARun() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenThrow(new ar.edu.uade.pfi.backend.service.AiMultiplanarContractViolationException("synthetic root debe ser false"));
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWithPersistence(ai, repository))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-NO-PERSIST",
                      "sagittalInputId": "inp_sagittal_001",
                      "allowContractFallback": false,
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
            .andExpect(status().isBadGateway());

        Optional<Study> study = repository.findStudyByCaseId("CASE-NO-PERSIST");
        assertTrue(study.isEmpty() || repository.findRunsByStudyId(study.get().id()).isEmpty());
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
    @SuppressWarnings("unchecked")
    void liveRunResponseAndReopenedSnapshotShareNormalizedSliceResults() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(sliceResultFixture());
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllerWithPersistence(ai, repository))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        String payload = mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-SLICES-LIVE",
                      "sagittalInputId": "input-sag-slices",
                      "allowContractFallback": false,
                      "metadata": {
                        "inferenceMode": "real_baseline"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[1].resultStatus").value("degraded_inconsistent_result_references"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[1].measurementIds[0]").value("canalAreaMm2"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[1].landmarkIds[0]").value("lm-canal"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[1].duplicateMeasurementIds[0]").value("canalAreaMm2"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[1].invalidMeasurementIds[0]").value("missingMeasurement"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[1].invalidLandmarkIds[0]").value("missingLandmark"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[2].resultStatus").value("no_automatic_results"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[2].measurementIds").isEmpty())
            .andExpect(jsonPath("$.planes.sagittal.metadata.slices[2].overlayAsset").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        Map<String, Object> live = objectMapper.readValue(payload, Map.class);
        Map<String, Object> liveInput = planeMetadata(live, "sagittal");
        List<Map<String, Object>> liveSlices = mapList(liveInput.get("slices"));

        StudyDetailResponseDto reopened = new StudyWorklistService(repository, false).getStudy("CASE-SLICES-LIVE");
        Map<String, Object> reopenedInput = reopenedPlaneInput(reopened, "sagittal");
        List<Map<String, Object>> reopenedSlices = mapList(reopenedInput.get("slices"));

        assertEquals(liveSlices.size(), reopenedSlices.size());
        assertSameSliceResultSemantics(sliceByIndex(liveSlices, 1), sliceByIndex(reopenedSlices, 1));
        assertSameSliceResultSemantics(sliceByIndex(liveSlices, 2), sliceByIndex(reopenedSlices, 2));
        assertFalse(payload.contains("relativePath"));
        assertFalse(objectMapper.writeValueAsString(reopenedInput).contains("relativePath"));
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
    void strictRealBaselineExposesLegacyAiOutputAndMetadataWithoutLeakingInternalPaths() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(contractFixture());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        String payload = mockMvc.perform(post("/api/ai/multiplanar/run")
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
            .andExpect(jsonPath("$.planes.sagittal.aiOutput.realInferenceAvailable").value(true))
            .andExpect(jsonPath("$.planes.sagittal.aiOutput.inferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.planes.sagittal.aiOutput.artifactHash").value(
                "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.inputShapeNative[0]").value(17))
            .andExpect(jsonPath("$.planes.sagittal.metadata.inputShapeCanonical[2]").value(17))
            .andExpect(jsonPath("$.planes.sagittal.metadata.inputOrientationTransform").value("move_axis_0_to_last"))
            .andExpect(jsonPath("$.planes.sagittal.metadata.selectedSlice").value(9))
            .andExpect(jsonPath("$.planes.sagittal.metadata.sliceCount").value(17))
            .andExpect(jsonPath("$.planes.sagittal.metadata.selectedAxis").value(2))
            .andExpect(jsonPath("$.planes.sagittal.metadata.inPlaneSpacingUnit").value("mm"))
            .andExpect(jsonPath("$.planes.sagittal.allowContractFallback").value(false))
            .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(payload.contains("/tmp"));
        org.junit.jupiter.api.Assertions.assertFalse(payload.toLowerCase().contains("c:\\\\"));
        org.junit.jupiter.api.Assertions.assertFalse(payload.contains("mask.npy"));
        org.junit.jupiter.api.Assertions.assertFalse(payload.contains("confidence.npy"));
        org.junit.jupiter.api.Assertions.assertFalse(payload.contains("/content"));
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

    private CanonicalMultiplanarRun multiplanarResponse() {
        CanonicalPlaneRun sagittal = new CanonicalPlaneRun(
            "run-sag-123",
            "sagittal",
            "completed",
            "real_baseline",
            false,
            null,
            Map.of("modelKey", "sagittal_spider", "artifactHash", "sha256:sag-checkpoint"),
            Map.of("inputId", "input-sag-1"),
            Map.of(),
            List.of(Map.of("label", "canal_lumbar")),
            List.of(Map.of("assetName", "overlay.png"), Map.of("assetName", "mask-preview.png")),
            List.of(),
            List.of(Map.of("name", "L4_left_pedicle", "x", 124.2, "y", 210.5)),
            List.of(Map.of("id", "canalAreaMm2", "value", 82.4)),
            Map.of("sliceIndex", 42)
        );
        CanonicalPlaneRun axial = new CanonicalPlaneRun(
            "run-ax-123",
            "axial",
            "completed",
            "real_baseline",
            false,
            null,
            Map.of("modelKey", "axial_t2_alkafri", "artifactHash", "sha256:ax-checkpoint"),
            Map.of("inputId", "input-ax-1"),
            Map.of(),
            List.of(Map.of("label", "estenosis")),
            List.of(Map.of("assetName", "overlay.png"), Map.of("assetName", "mask-preview.png")),
            List.of(),
            List.of(Map.of("name", "canal_center", "x", 93.3, "y", 118.8)),
            List.of(Map.of("id", "leftForamenMm", "value", 3.1)),
            Map.of("sliceIndex", 18)
        );
        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);
        planes.put("axial", axial);
        return new CanonicalMultiplanarRun(
            "multiplanar_run_ready",
            "multiplanar-run-v1",
            "multi-123",
            "trace-123",
            "CASE-1",
            "dual_plane_with_3d_context",
            "real_baseline",
            "real_baseline",
            List.of("sagittal", "axial"),
            List.of("sagittal", "axial"),
            false,
            null,
            Map.of(),
            planes,
            Map.of(),
            Map.of(),
            Map.of("status", "pendiente"),
            new CanonicalMultiplanarRun.Governance(true, true, true, false)
        );
    }

    private CanonicalMultiplanarRun contractFixture() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/contracts/ai-module-multiplanar-real-baseline.json"));
        MultiplanarRunResponseDto dto = objectMapper.readValue(json, MultiplanarRunResponseDto.class);
        return new AiMultiplanarV1ResponseAdapter().toCanonical(dto);
    }

    @SuppressWarnings("unchecked")
    private CanonicalMultiplanarRun sagittalOnlyFixture() throws Exception {
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
        MultiplanarRunResponseDto dto = objectMapper.convertValue(response, MultiplanarRunResponseDto.class);
        return new AiMultiplanarV1ResponseAdapter().toCanonical(dto);
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

    private CanonicalMultiplanarRun sliceResultFixture() {
        CanonicalPlaneRun sagittal = new CanonicalPlaneRun(
            "run-sag-slices",
            "sagittal",
            "completed",
            "real_baseline",
            false,
            null,
            map("modelKey", "sagittal_spider", "artifactHash", "sha256:sag-slice"),
            sliceCatalogInput("input-sag-slices"),
            Map.of(),
            List.of(Map.of("seriesId", "series-sag")),
            List.of(Map.of("assetName", "overlay.png", "generated", true, "relativePath", "outputs/run-sag-slices/sagittal/overlay.png", "contentType", "image/png")),
            List.of(),
            List.of(map("id", "lm-canal", "name", "canal_center", "x", 93.3, "y", 118.8)),
            List.of(map("id", "canalAreaMm2", "labelKey", "canal.area", "value", 82.4, "unit", "mm2", "status", "automatic")),
            Map.of("sliceIndex", 1)
        );
        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);
        return new CanonicalMultiplanarRun(
            "multiplanar_run_ready",
            "multiplanar-run-v2",
            "multi-slices-001",
            "trace-slices-001",
            "CASE-SLICES-LIVE",
            "dual_plane_with_3d_context",
            "real_baseline",
            "mixed",
            List.of("sagittal"),
            List.of("sagittal"),
            false,
            null,
            Map.of(),
            planes,
            Map.of(),
            Map.of(),
            Map.of("status", "pending"),
            new CanonicalMultiplanarRun.Governance(true, true, true, false)
        );
    }

    private Map<String, Object> sliceCatalogInput(String inputId) {
        return map(
            "inputId", inputId,
            "sliceCount", 3,
            "selectedSliceIndex", 1,
            "selectedAxis", 2,
            "slices", List.of(
                map("index", 0, "hasResults", false, "previewAsset", sliceAsset("slice-000.png", "slice-preview")),
                map(
                    "index", 1,
                    "hasResults", true,
                    "previewAsset", sliceAsset("slice-001.png", "slice-preview"),
                    "overlayAsset", sliceAsset("slice-001-overlay.png", "slice-overlay"),
                    "measurementIds", List.of("canalAreaMm2", "missingMeasurement", "canalAreaMm2"),
                    "landmarkIds", List.of("lm-canal", "missingLandmark")
                ),
                map(
                    "index", 2,
                    "hasResults", true,
                    "previewAsset", sliceAsset("slice-002.png", "slice-preview"),
                    "measurementIds", List.of("ghostMeasurement"),
                    "landmarkIds", List.of("ghostLandmark")
                )
            )
        );
    }

    private Map<String, Object> sliceAsset(String assetName, String role) {
        return map("assetName", assetName, "role", role, "contentType", "image/png", "generated", true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planeMetadata(Map<String, Object> response, String plane) {
        Map<String, Object> planes = (Map<String, Object>) response.get("planes");
        return (Map<String, Object>) ((Map<String, Object>) planes.get(plane)).get("metadata");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reopenedPlaneInput(StudyDetailResponseDto reopened, String plane) {
        Map<String, Object> canonicalRun = reopened.runs().get(0).canonicalRun();
        Map<String, Object> planes = (Map<String, Object>) canonicalRun.get("planes");
        return (Map<String, Object>) ((Map<String, Object>) planes.get(plane)).get("input");
    }

    private void assertSameSliceResultSemantics(Map<String, Object> live, Map<String, Object> reopened) {
        assertEquals(live.get("hasResults"), reopened.get("hasResults"));
        assertEquals(live.get("resultStatus"), reopened.get("resultStatus"));
        assertEquals(live.get("measurementIds"), reopened.get("measurementIds"));
        assertEquals(live.get("landmarkIds"), reopened.get("landmarkIds"));
        assertEquals(live.get("invalidMeasurementIds"), reopened.get("invalidMeasurementIds"));
        assertEquals(live.get("duplicateMeasurementIds"), reopened.get("duplicateMeasurementIds"));
        assertEquals(live.get("invalidLandmarkIds"), reopened.get("invalidLandmarkIds"));
        assertEquals(assetName(live.get("overlayAsset")), assetName(reopened.get("overlayAsset")));
    }

    @SuppressWarnings("unchecked")
    private String assetName(Object asset) {
        return asset instanceof Map<?, ?> map ? String.valueOf(((Map<String, Object>) map).get("assetName")) : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private Map<String, Object> sliceByIndex(List<Map<String, Object>> slices, int index) {
        return slices.stream()
            .filter(slice -> Integer.valueOf(index).equals(slice.get("index")))
            .findFirst()
            .orElseThrow();
    }

    private Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
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
        @Override public StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot) { throw new UnsupportedOperationException(); }
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
