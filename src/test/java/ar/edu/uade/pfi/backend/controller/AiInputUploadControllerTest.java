package ar.edu.uade.pfi.backend.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiInputUploadControllerTest {
    private AiServiceOperations aiServiceClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        aiServiceClient = org.mockito.Mockito.mock(AiServiceOperations.class);
        AiBackendService service = new AiBackendService(aiServiceClient, org.mockito.Mockito.mock(ReviewStoreService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new AiBackendController(service))
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
}
