package ar.edu.uade.pfi.backend.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class AiInputUploadControllerTest {
    private AiServiceOperations aiServiceClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        aiServiceClient = org.mockito.Mockito.mock(AiServiceOperations.class);
        AiBackendService service = new AiBackendService(aiServiceClient, org.mockito.Mockito.mock(ReviewStoreService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new AiBackendController(service, org.mockito.Mockito.mock(ar.edu.uade.pfi.backend.auth.RoleAuthorizationService.class)))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void uploadInputReturnsInputIdWithoutInternalPaths() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "case-001-sagittal.npy",
            "application/octet-stream",
            new byte[] {1, 2, 3}
        );
        when(aiServiceClient.uploadInput(any(), eq("CASE-001"), eq("sagittal")))
            .thenReturn(new AiInputResponseDto("input-123", "CASE-001", "sagittal", "npy", 3));

        mockMvc.perform(multipart("/api/ai/inputs")
                .file(file)
                .param("caseId", "CASE-001")
                .param("plane", "sagittal"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inputId").value("input-123"))
            .andExpect(jsonPath("$.caseId").value("CASE-001"))
            .andExpect(jsonPath("$.plane").value("sagittal"))
            .andExpect(jsonPath("$.format").value("npy"))
            .andExpect(jsonPath("$.size").value(3))
            .andExpect(jsonPath("$.path").doesNotExist())
            .andExpect(content().string(not(containsString("C:\\"))))
            .andExpect(content().string(not(containsString("/tmp/"))));
    }

    @Test
    void uploadInputAcceptsMhaLargerThanOneMbAndBelowTwoHundredMb() throws Exception {
        byte[] payload = new byte[1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "case-001-sagittal.mha",
            "application/octet-stream",
            payload
        );
        when(aiServiceClient.uploadInput(any(), eq("CASE-001"), eq("sagittal")))
            .thenReturn(new AiInputResponseDto("input-mha-123", "CASE-001", "sagittal", "mha", payload.length));

        mockMvc.perform(multipart("/api/ai/inputs")
                .file(file)
                .param("caseId", "CASE-001")
                .param("plane", "sagittal"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inputId").value("input-mha-123"))
            .andExpect(jsonPath("$.format").value("mha"))
            .andExpect(jsonPath("$.size").value(payload.length));
    }

    @Test
    void uploadInputRejectsUnsupportedExtensionWithSemanticError() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "case-001.exe",
            MediaType.APPLICATION_OCTET_STREAM_VALUE,
            new byte[] {1}
        );

        mockMvc.perform(multipart("/api/ai/inputs")
                .file(file)
                .param("caseId", "CASE-001")
                .param("plane", "sagittal"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value(containsString("Formato de input invalido")));
    }

    @Test
    void uploadStudyReturnsTypedContractWithoutInternalIdentifiers() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "study.zip",
            "application/zip",
            new byte[] {'P', 'K', 3, 4, 1}
        );
        when(aiServiceClient.uploadStudy(any(), eq("CASE-001"))).thenReturn(java.util.Map.of(
            "caseId", "CASE-001",
            "studyId", "study-123",
            "seriesFound", List.of(java.util.Map.of(
                "plane", "sagittal",
                "description", "Sag T2",
                "weighting", "T2",
                "sliceCount", 24,
                "seriesInstanceUid", "1.2.840"
            )),
            "sagittal", java.util.Map.of(
                "inputId", "input-sag",
                "plane", "sagittal",
                "format", "dcm",
                "size", 42,
                "description", "Sag T2",
                "weighting", "T2",
                "sliceCount", 24,
                "path", "C:\\secret\\study"
            )
        ));

        mockMvc.perform(multipart("/api/ai/studies")
                .file(file)
                .param("caseId", "CASE-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseId").value("CASE-001"))
            .andExpect(jsonPath("$.studyId").value("study-123"))
            .andExpect(jsonPath("$.sagittal.inputId").value("input-sag"))
            .andExpect(jsonPath("$.seriesFound[0].plane").value("sagittal"))
            .andExpect(jsonPath("$.seriesFound[0].seriesInstanceUid").doesNotExist())
            .andExpect(jsonPath("$.sagittal.path").doesNotExist())
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true))
            .andExpect(content().string(not(containsString("C:\\"))))
            .andExpect(content().string(not(containsString("1.2.840"))));
    }

    @Test
    void maxUploadSizeExceededReturnsInputTooLargeWithoutInternalError() throws Exception {
        MockMvc oversizedMvc = MockMvcBuilders.standaloneSetup(new OversizedUploadController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        oversizedMvc.perform(post("/api/ai/inputs")
                .header("X-Trace-Id", "trace-upload-too-large"))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("INPUT_TOO_LARGE"))
            .andExpect(jsonPath("$.message").value(containsString("supera el tamano maximo")))
            .andExpect(jsonPath("$.traceId").value("trace-upload-too-large"))
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true))
            .andExpect(content().string(not(containsString("INTERNAL_ERROR"))));
    }

    @Controller
    private static class OversizedUploadController {
        @PostMapping("/api/ai/inputs")
        void upload() {
            throw new MaxUploadSizeExceededException(200L * 1024L * 1024L);
        }
    }
}
