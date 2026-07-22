package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    void strictRealBaselineRejectsMissingInputIdsAmbiguousInputsAndDemoPaths() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AiMultiplanarController(org.mockito.Mockito.mock(AiServiceOperations.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("", "inp_axial_001", null, null)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("inp_sagittal_001", "", null, null)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("inp_sagittal_001", "inp_axial_001", "some/path", null)))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(strictBody("demo/sag", "inp_axial_001", null, null)))
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
}
