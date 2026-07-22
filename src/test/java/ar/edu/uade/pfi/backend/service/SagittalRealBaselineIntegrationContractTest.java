package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.config.SagittalRealBaselineProperties;
import ar.edu.uade.pfi.backend.controller.AiBackendController;
import ar.edu.uade.pfi.backend.controller.AiModelSyncController;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class SagittalRealBaselineIntegrationContractTest {
    private static final String INPUT_ID = "inp_test_sagittal_001";
    private final SagittalRealBaselineProperties expected = new SagittalRealBaselineProperties(
        "sagittal_spider",
        "sagittal-spider-final-v1",
        "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944",
        "sagittal_spider_final_v1",
        "7420ad4271fe634c970b2a543d1ef8fb1437888c99ca8bd5733a06e5f63e3e7e",
        "d36d0c4fe183ba9a98f0a3471486be5dee1cf1fa820dc32b3a50177ce322be21"
    );
    private final PipelineRunRequestNormalizer normalizer = new PipelineRunRequestNormalizer(expected);
    private final SagittalRealBaselineContractValidator validator = new SagittalRealBaselineContractValidator(expected);
    private final AiPipelineResponsePresenter presenter = new AiPipelineResponsePresenter();
    private final AiModelReadinessResolver readinessResolver = new AiModelReadinessResolver(expected);

    @Test
    void realBaselineMissingFallbackBecomesStrictFalseAndKeepsTraceId() {
        PipelineRunRequestDto normalized = normalizer.normalizePipelineRequest(new PipelineRunRequestDto(
            "case-001",
            "sagittal",
            "baseline",
            "input-123",
            null,
            Map.of("inferenceMode", "real_baseline", "traceId", "trace-001")
        ));

        assertTrue(normalizer.isStrictRealBaseline(normalized));
        assertEquals(false, normalized.metadata().get("allowContractFallback"));
        assertEquals("trace-001", normalized.metadata().get("traceId"));
        assertEquals("sagittal_spider", normalized.modelKey());
    }

    @Test
    void explicitFallbackTrueIsPreservedAndNotStrict() {
        PipelineRunRequestDto normalized = normalizer.normalizePipelineRequest(new PipelineRunRequestDto(
            "case-001",
            "sagittal",
            "baseline",
            "input-123",
            null,
            Map.of("inferenceMode", "real_baseline", "allowContractFallback", true)
        ));

        assertFalse(normalizer.isStrictRealBaseline(normalized));
        assertEquals(true, normalized.metadata().get("allowContractFallback"));
    }

    @Test
    void strictRealBaselineWithoutInputPathReturnsBadRequest() throws Exception {
        MockMvc mockMvc = strictMockMvc(mock(AiServiceOperations.class), mockReviewStore());

        mockMvc.perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "case-001",
                      "plane": "sagittal",
                      "modelKey": "baseline",
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void strictRealBaselineAcceptsInputIdAndForwardsItTopLevel() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.runPipeline(any())).thenReturn(validPipelineResponseForInputId());

        strictMockMvc(ai, mockReviewStore()).perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(inputIdRequestJson()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inputId").value(INPUT_ID))
            .andExpect(jsonPath("$.inputPath").doesNotExist())
            .andExpect(jsonPath("$.metadata.sourcePath").doesNotExist());

        ArgumentCaptor<PipelineRunRequestDto> captor = ArgumentCaptor.forClass(PipelineRunRequestDto.class);
        verify(ai).runPipeline(captor.capture());
        assertEquals(INPUT_ID, captor.getValue().inputId());
        assertTrue(captor.getValue().inputPath() == null || captor.getValue().inputPath().isBlank());
        assertEquals("sagittal_spider", captor.getValue().modelKey());
        assertEquals(false, captor.getValue().metadata().get("allowContractFallback"));
    }

    @Test
    void strictRealBaselineRejectsAmbiguousInputIdAndInputPath() throws Exception {
        strictMockMvc(mock(AiServiceOperations.class), mockReviewStore()).perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "case-001",
                      "plane": "sagittal",
                      "modelKey": "baseline",
                      "inputPath": "input-sag-001",
                      "inputId": "inp_test_sagittal_001",
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Enviar solamente inputId o inputPath, no ambos."));
    }

    @Test
    void strictRealBaselineRejectsDemoInputPathAndDemoInputId() throws Exception {
        MockMvc mockMvc = strictMockMvc(mock(AiServiceOperations.class), mockReviewStore());

        mockMvc.perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "case-001",
                      "plane": "sagittal",
                      "modelKey": "baseline",
                      "inputPath": "demo/case-001",
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "case-001",
                      "plane": "sagittal",
                      "modelKey": "baseline",
                      "inputId": "demo/case-001",
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void strictValidResponseIsSanitizedAndKeepsClinicalSafetyFlags() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        ReviewStoreService reviews = mockReviewStore();
        when(ai.runPipeline(any())).thenReturn(validPipelineResponse());

        strictMockMvc(ai, reviews).perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "case-001",
                      "plane": "sagittal",
                      "modelKey": "sagittal-spider-final-v1",
                      "inputPath": "input-sag-001",
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value("run-001"))
            .andExpect(jsonPath("$.modelVersion").value(expected.modelVersion()))
            .andExpect(jsonPath("$.artifactHash").value(expected.modelSha256()))
            .andExpect(jsonPath("$.inferenceMode").value("real_baseline"))
            .andExpect(jsonPath("$.degradedMode").value(false))
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true))
            .andExpect(jsonPath("$.assets['input.png']").value("/api/ai/assets/run-001/sagittal/input.png"))
            .andExpect(jsonPath("$.assets['overlay.png']").value("/api/ai/assets/run-001/sagittal/overlay.png"))
            .andExpect(jsonPath("$.assets['mask-preview.png']").value("/api/ai/assets/run-001/sagittal/mask-preview.png"))
            .andExpect(jsonPath("$.assets['mask.npy']").doesNotExist())
            .andExpect(jsonPath("$.assets['confidence.npy']").doesNotExist())
            .andExpect(jsonPath("$.metadata.sourcePath").doesNotExist())
            .andExpect(jsonPath("$.metadata.outputFiles").doesNotExist())
            .andExpect(jsonPath("$.overlayPath").value("/api/ai/assets/run-001/sagittal/overlay.png"))
            .andExpect(jsonPath("$.series[0].imageUrl").value("/api/ai/assets/run-001/sagittal/input.png"))
            .andExpect(jsonPath("$.series[0].overlayUrl").value("/api/ai/assets/run-001/sagittal/overlay.png"))
            .andExpect(jsonPath("$.review.status").value("pendiente"));

        ArgumentCaptor<PipelineRunRequestDto> captor = ArgumentCaptor.forClass(PipelineRunRequestDto.class);
        verify(ai).runPipeline(captor.capture());
        assertEquals(false, captor.getValue().metadata().get("allowContractFallback"));
        assertEquals("real_baseline", captor.getValue().metadata().get("inferenceMode"));
    }

    @Test
    void strictUpstreamErrorsAreNotConvertedToDegradedSuccess() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.runPipeline(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Module is not available"));

        strictMockMvc(ai, mockReviewStore()).perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictRequestJson()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.runId").doesNotExist())
            .andExpect(jsonPath("$.measurements").doesNotExist());
    }

    @Test
    void strictTimeoutReturnsGatewayTimeout() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.runPipeline(any())).thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI Module request timed out"));

        strictMockMvc(ai, mockReviewStore()).perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictRequestJson()))
            .andExpect(status().isGatewayTimeout());
    }

    @Test
    void invalidStrictContractReturnsAiContractViolation() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        Map<String, Object> response = validPipelineResponse();
        response.remove("modelVersion");
        when(ai.runPipeline(any())).thenReturn(response);

        strictMockMvc(ai, mockReviewStore()).perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictRequestJson()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("AI_CONTRACT_VIOLATION"));
    }

    @Test
    void validatorRejectsWrongArtifactFallbackAndSpiderShape() {
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(
            strictRequest(), mutated("artifactHash", "wrong")
        ));
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(
            strictRequest(), mutated("allowContractFallback", true)
        ));
        Map<String, Object> response = validPipelineResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) response.get("metadata");
        metadata.put("inputShapeCanonical", List.of(17, 512, 512));
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(strictRequest(), response));
    }

    @Test
    void demoRequestKeepsExistingDegradedFallback() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.runPipeline(any())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI unavailable"));

        strictMockMvc(ai, mockReviewStore()).perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "case-demo",
                      "plane": "axial",
                      "modelKey": "axial-v1",
                      "metadata": {"source": "demo"}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("pipeline_degraded_fallback"))
            .andExpect(jsonPath("$.degradedMode").value(true));
    }

    @Test
    void modelSyncAcceptsVerifiedStatusesAndRejectsBadHashes() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.syncModels(false)).thenReturn(syncResponse("models_sync_completed", "synced_verified", expected.modelSha256()));

        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AiModelSyncController(ai, null, validator))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/models/sync"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sagittalReadyForRealInference").value(true));

        Map<String, Object> existing = syncResponse("models_sync_completed", "existing_release_verified", expected.modelSha256());
        validator.validateSagittalSync(existing);
        assertThrows(AiContractViolationException.class, () -> validator.validateSagittalSync(syncResponse("models_sync_completed", "synced_verified", "wrong")));
        assertThrows(AiContractViolationException.class, () -> validator.validateSagittalSync(syncResponse("models_sync_completed", "sync_failed", expected.modelSha256())));
        verify(ai).syncModels(false);
    }

    @Test
    void modelSyncRejectsInvalidRootMissingItemStatusAbsentItemAndAxialOnlySuccess() {
        assertThrows(AiContractViolationException.class, () ->
            validator.validateSagittalSync(syncResponse("synced_verified", "synced_verified", expected.modelSha256())));
        Map<String, Object> noItemStatus = syncResponse("models_sync_completed", "", expected.modelSha256());
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) noItemStatus.get("items")).get(0);
        item.remove("status");
        assertThrows(AiContractViolationException.class, () -> validator.validateSagittalSync(noItemStatus));
        assertThrows(AiContractViolationException.class, () -> validator.validateSagittalSync(new LinkedHashMap<>(Map.of(
            "status", "models_sync_completed",
            "items", List.of(Map.of("modelKey", "axial_t2_alkafri", "status", "synced_verified"))
        ))));
        assertThrows(AiContractViolationException.class, () -> validator.validateSagittalSync(new LinkedHashMap<>(Map.of(
            "status", "models_sync_completed",
            "items", List.of(
                Map.of("modelKey", "axial_t2_alkafri", "status", "synced_verified"),
                syncItem("sync_failed", expected.modelSha256())
            )
        ))));
    }

    @Test
    void presenterDoesNotExposeInternalPathsOrRawAssets() {
        Map<String, Object> presented = presenter.present(validPipelineResponse());

        assertEquals("/api/ai/assets/run-001/sagittal/overlay.png", presented.get("overlayPath"));
        assertFalse(presented.toString().contains("/tmp"));
        assertFalse(presented.toString().contains("/content"));
        assertFalse(presented.toString().contains("C:/"));
        assertFalse(presented.toString().contains("models/final"));
        assertFalse(presented.toString().contains("mask.npy"));
        assertFalse(presented.toString().contains("confidence.npy"));
    }

    @Test
    void inputIdResponseMustEchoWhenPresentAndMustNotExposeInputPath() {
        validator.validatePipelineResponse(inputIdStrictRequest(), validPipelineResponseForInputId());

        Map<String, Object> mismatch = validPipelineResponseForInputId();
        mismatch.put("inputId", "inp_other");
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(inputIdStrictRequest(), mismatch));

        Map<String, Object> exposedPath = validPipelineResponseForInputId();
        exposedPath.put("inputPath", "/tmp/private/input.mha");
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(inputIdStrictRequest(), exposedPath));

        Map<String, Object> exposedSourcePath = validPipelineResponseForInputId();
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) exposedSourcePath.get("metadata");
        metadata.put("sourcePath", "/content/private/input.mha");
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(inputIdStrictRequest(), exposedSourcePath));
    }

    @Test
    void presenterPreservesOpaqueInputIdAndRemovesInternalPaths() {
        Map<String, Object> response = validPipelineResponseForInputId();
        response.put("inputPath", "/tmp/private/input.mha");
        Map<String, Object> presented = presenter.present(response);

        assertEquals(INPUT_ID, presented.get("inputId"));
        assertFalse(presented.containsKey("inputPath"));
        assertFalse(presented.toString().contains("/tmp"));
        assertFalse(presented.toString().contains("sourcePath"));
    }

    @Test
    void uploadResponseSerializesInputIdWithoutInputPath() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new AiInputResponseDto(INPUT_ID, "case-001", "sagittal", "mha", 123));

        assertTrue(json.contains("\"inputId\""));
        assertFalse(json.contains("inputPath"));
    }

    @Test
    void e2eScriptUsesInputIdAndDoesNotAssignUploadInputIdToInputPath() throws Exception {
        String script = Files.readString(Path.of("scripts/run_sagittal_real_backend_e2e.ps1"));

        assertTrue(script.contains("inputId = $upload.inputId"));
        assertFalse(script.contains("inputPath = $pipelineInput"));
        assertFalse(script.contains("$pipelineInput = if ($upload.inputPath)"));
    }

    @Test
    void upstreamRawAssetsAreValidButNeverPresentedOrProxied() {
        Map<String, Object> response = validPipelineResponse();

        validator.validatePipelineResponse(strictRequest(), response);
        Map<String, Object> presented = presenter.present(response);

        assertFalse(presented.toString().contains("mask.npy"));
        assertFalse(presented.toString().contains("confidence.npy"));
        @SuppressWarnings("unchecked")
        Map<String, Object> assets = (Map<String, Object>) presented.get("assets");
        assertEquals(3, assets.size());
        assertTrue(assets.containsKey("input.png"));
        assertTrue(assets.containsKey("overlay.png"));
        assertTrue(assets.containsKey("mask-preview.png"));
    }

    @Test
    void upstreamAssetsRejectUnknownMismatchedNameRunIdOrPlane() {
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(
            strictRequest(), responseWithAsset("debug.txt", Map.of("runId", "run-001", "plane", "sagittal", "assetName", "debug.txt"))
        ));
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(
            strictRequest(), responseWithAsset("overlay.png", Map.of("runId", "other-run", "plane", "sagittal", "assetName", "overlay.png"))
        ));
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(
            strictRequest(), responseWithAsset("overlay.png", Map.of("runId", "run-001", "plane", "axial", "assetName", "overlay.png"))
        ));
        assertThrows(AiContractViolationException.class, () -> validator.validatePipelineResponse(
            strictRequest(), responseWithAsset("overlay.png", Map.of("runId", "run-001", "plane", "sagittal", "assetName", "input.png"))
        ));
    }

    @Test
    void backendAssetProxyStillForbidsRawAssets() throws Exception {
        strictMockMvc(mock(AiServiceOperations.class), mockReviewStore())
            .perform(get("/api/ai/assets/run-001/sagittal/mask.npy"))
            .andExpect(status().isForbidden());

        strictMockMvc(mock(AiServiceOperations.class), mockReviewStore())
            .perform(get("/api/ai/assets/run-001/sagittal/confidence.npy"))
            .andExpect(status().isForbidden());
    }

    @Test
    void readinessDerivesSagittalAndAxialFromVerifyModels() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.readiness()).thenReturn(Map.of("status", "ready"));
        when(ai.verifyModels()).thenReturn(verifyModels(List.of(sagittalVerified(), axialVerified()), List.of(), List.of(), List.of()));

        strictMockMvc(ai, mockReviewStore()).perform(get("/api/ai/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backendReady").value(true))
            .andExpect(jsonPath("$.aiModuleAvailable").value(true))
            .andExpect(jsonPath("$.degradedMode").value(false))
            .andExpect(jsonPath("$.sagittalReadyForRealInference").value(true))
            .andExpect(jsonPath("$.axialReadyForRealInference").value(true))
            .andExpect(jsonPath("$.sha256").doesNotExist())
            .andExpect(jsonPath("$.version").doesNotExist());

        verify(ai, times(1)).verifyModels();
    }

    @Test
    void readinessKeepsSagittalIndependentFromAxialAndRejectsBadSagittalEvidence() {
        assertEquals(new AiModelReadinessResolver.ModelReadiness(true, false),
            readinessResolver.resolve(verifyModels(List.of(sagittalVerified()), List.of(), List.of(), List.of())));
        assertFalse(readinessResolver.resolve(verifyModels(List.of(mutate(sagittalVerified(), "sha256", "wrong")), List.of(), List.of(), List.of()))
            .sagittalReadyForRealInference());
        assertFalse(readinessResolver.resolve(verifyModels(List.of(mutate(sagittalVerified(), "baselineReady", false)), List.of(), List.of(), List.of()))
            .sagittalReadyForRealInference());
        assertFalse(readinessResolver.resolve(verifyModels(List.of(sagittalVerified()), List.of(), List.of(Map.of("modelKey", "sagittal_spider")), List.of()))
            .sagittalReadyForRealInference());
    }

    @Test
    void readinessVerifyModelsFailureKeepsAiAvailableButDegraded() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.readiness()).thenReturn(Map.of("status", "ready"));
        when(ai.verifyModels()).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "verify unavailable"));

        strictMockMvc(ai, mockReviewStore()).perform(get("/api/ai/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiModuleAvailable").value(true))
            .andExpect(jsonPath("$.degradedMode").value(true))
            .andExpect(jsonPath("$.sagittalReadyForRealInference").value(false))
            .andExpect(jsonPath("$.axialReadyForRealInference").value(false))
            .andExpect(jsonPath("$.modelVerificationStatus").value("model_artifact_verification_unavailable"));
    }

    @Test
    void readinessAiModuleDownKeepsBothModelFlagsFalse() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.readiness()).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "down"));

        strictMockMvc(ai, mockReviewStore()).perform(get("/api/ai/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.aiModuleAvailable").value(false))
            .andExpect(jsonPath("$.degradedMode").value(true))
            .andExpect(jsonPath("$.sagittalReadyForRealInference").value(false))
            .andExpect(jsonPath("$.axialReadyForRealInference").value(false));
    }

    private MockMvc strictMockMvc(AiServiceOperations ai, ReviewStoreService reviews) {
        AiBackendService service = new AiBackendService(ai, reviews, null, normalizer, validator, presenter, readinessResolver);
        return MockMvcBuilders
            .standaloneSetup(new AiBackendController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private ReviewStoreService mockReviewStore() {
        ReviewStoreService reviews = mock(ReviewStoreService.class);
        when(reviews.findOrDefault("run-001")).thenReturn(new ReviewStatusDto("run-001", "pendiente", "", "", Instant.parse("2026-07-22T00:00:00Z")));
        when(reviews.findOrDefault(org.mockito.ArgumentMatchers.startsWith("degraded-")))
            .thenAnswer(invocation -> new ReviewStatusDto(invocation.getArgument(0), "pendiente", "", "", Instant.parse("2026-07-22T00:00:00Z")));
        return reviews;
    }

    private PipelineRunRequestDto strictRequest() {
        return new PipelineRunRequestDto("case-001", "sagittal", "sagittal_spider", "input-sag-001", null, Map.of(
            "inferenceMode", "real_baseline",
            "allowContractFallback", false
        ));
    }

    private PipelineRunRequestDto inputIdStrictRequest() {
        return new PipelineRunRequestDto("case-001", "sagittal", "sagittal_spider", null, INPUT_ID, Map.of(
            "inferenceMode", "real_baseline",
            "allowContractFallback", false
        ));
    }

    private String strictRequestJson() {
        return """
            {
              "caseId": "case-001",
              "plane": "sagittal",
              "modelKey": "sagittal_spider",
              "inputPath": "input-sag-001",
              "metadata": {"inferenceMode": "real_baseline", "allowContractFallback": false}
            }
            """;
    }

    private String inputIdRequestJson() {
        return """
            {
              "caseId": "case-001",
              "plane": "sagittal",
              "modelKey": "sagittal-spider-final-v1",
              "inputId": "inp_test_sagittal_001",
              "metadata": {"inferenceMode": "real_baseline"}
            }
            """;
    }

    private Map<String, Object> mutated(String key, Object value) {
        Map<String, Object> response = validPipelineResponse();
        response.put(key, value);
        return response;
    }

    private Map<String, Object> validPipelineResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", "run-001");
        response.put("traceId", "trace-001");
        response.put("caseId", "case-001");
        response.put("plane", "sagittal");
        response.put("modelKey", expected.modelKey());
        response.put("modelVersion", expected.modelVersion());
        response.put("artifactHash", expected.modelSha256());
        response.put("inferenceMode", "real_baseline");
        response.put("requestedInferenceMode", "real_baseline");
        response.put("allowContractFallback", false);
        response.put("humanReviewRequired", true);
        response.put("notClinicalDiagnosis", true);
        response.put("status", "real_baseline_ready");
        response.put("aiOutput", Map.of(
            "inferenceMode", "real_baseline",
            "realInferenceAvailable", true,
            "artifactHash", expected.modelSha256(),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        ));
        response.put("metadata", new LinkedHashMap<>(Map.ofEntries(
            Map.entry("inferenceMode", "real_baseline"),
            Map.entry("requestedInferenceMode", "real_baseline"),
            Map.entry("artifactHash", expected.modelSha256()),
            Map.entry("selectedSlice", 9),
            Map.entry("selectedAxis", 2),
            Map.entry("sliceCount", 17),
            Map.entry("inputShapeNative", List.of(17, 512, 512)),
            Map.entry("inputShapeCanonical", List.of(512, 512, 17)),
            Map.entry("inputOrientationTransform", "move_axis_0_to_last"),
            Map.entry("inPlaneSpacing", List.of(0.7, 0.7)),
            Map.entry("inPlaneSpacingUnit", "mm"),
            Map.entry("sourcePath", "/content/private/input.mha"),
            Map.entry("outputFiles", List.of("/tmp/run-001/mask.npy"))
        )));
        response.put("series", List.of(new LinkedHashMap<>(Map.of(
            "sliceCount", 17,
            "selectedSlice", 9,
            "status", "real_baseline_ready",
            "imagePath", "C:/tmp/input.png",
            "overlayPath", "/tmp/run-001/overlay.png"
        ))));
        response.put("assets", new LinkedHashMap<>(Map.of(
            "input.png", Map.of("runId", "run-001", "plane", "sagittal", "assetName", "input.png"),
            "overlay.png", Map.of("runId", "run-001", "plane", "sagittal", "assetName", "overlay.png"),
            "mask-preview.png", Map.of("runId", "run-001", "plane", "sagittal", "assetName", "mask-preview.png"),
            "mask.npy", Map.of("runId", "run-001", "plane", "sagittal", "assetName", "mask.npy"),
            "confidence.npy", Map.of("runId", "run-001", "plane", "sagittal", "assetName", "confidence.npy")
        )));
        response.put("measurements", Map.of(
            "status", "real_baseline_ready",
            "values", List.of(Map.of("name", "canal_area", "value", 82.4, "source", "AI", "reviewable", true))
        ));
        response.put("landmarks", List.of(Map.of("name", "L4", "source", "AI")));
        response.put("quality", Map.of("foregroundPresent", false));
        response.put("overlayPath", "/tmp/run-001/overlay.png");
        response.put("modelPath", "/models/final/model.pt");
        return response;
    }

    private Map<String, Object> validPipelineResponseForInputId() {
        Map<String, Object> response = validPipelineResponse();
        response.put("inputId", INPUT_ID);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) response.get("metadata");
        metadata.remove("sourcePath");
        metadata.remove("outputFiles");
        return response;
    }

    private Map<String, Object> responseWithAsset(String key, Object value) {
        Map<String, Object> response = validPipelineResponse();
        @SuppressWarnings("unchecked")
        Map<String, Object> assets = (Map<String, Object>) response.get("assets");
        assets.put(key, value);
        return response;
    }

    private Map<String, Object> syncResponse(String rootStatus, String itemStatus, String modelSha256) {
        return new LinkedHashMap<>(Map.of(
            "status", rootStatus,
            "items", List.of(syncItem(itemStatus, modelSha256), Map.of("modelKey", "axial_t2_alkafri"))
        ));
    }

    private Map<String, Object> syncItem(String itemStatus, String modelSha256) {
        Map<String, Object> item = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("modelKey", expected.modelKey()),
            Map.entry("source", "gcs_verified_release"),
            Map.entry("releaseId", expected.releaseId()),
            Map.entry("releaseContentSha256", expected.releaseContentSha256()),
            Map.entry("releaseManifestSha256", expected.releaseManifestSha256()),
            Map.entry("modelSha256", modelSha256),
            Map.entry("artifactSynced", true),
            Map.entry("manifestSynced", true),
            Map.entry("modelCardSynced", true),
            Map.entry("releaseMetadataVerified", true),
            Map.entry("gcsReadOnly", true),
            Map.entry("filesReplaced", 0),
            Map.entry("releaseMetadataReplaced", 0)
        ));
        if (!itemStatus.isBlank()) {
            item.put("status", itemStatus);
        }
        return item;
    }

    private Map<String, Object> verifyModels(List<Object> verifiedModels, List<Object> missingArtifacts, List<Object> missingManifest, List<Object> unverifiedArtifacts) {
        return Map.of(
            "verifiedModels", verifiedModels,
            "missingArtifacts", missingArtifacts,
            "missingManifestOrBaselineEvidence", missingManifest,
            "unverifiedArtifacts", unverifiedArtifacts
        );
    }

    private Map<String, Object> sagittalVerified() {
        return new LinkedHashMap<>(Map.of(
            "modelKey", expected.modelKey(),
            "availableForRealInference", true,
            "baselineReady", true,
            "verified", true,
            "sha256", expected.modelSha256(),
            "version", expected.modelVersion()
        ));
    }

    private Map<String, Object> axialVerified() {
        return new LinkedHashMap<>(Map.of(
            "modelKey", "axial_t2_alkafri",
            "availableForRealInference", true,
            "baselineReady", true,
            "verified", true,
            "sha256", "axial-sha-does-not-matter",
            "version", "axial-version"
        ));
    }

    private Map<String, Object> mutate(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }
}
