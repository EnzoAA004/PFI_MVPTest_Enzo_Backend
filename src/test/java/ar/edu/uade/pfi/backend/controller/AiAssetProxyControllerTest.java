package ar.edu.uade.pfi.backend.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class AiAssetProxyControllerTest {
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
    void getAssetProxiesPngWithContentType() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        when(aiServiceClient.getAsset("run-123", "sagittal", "overlay.png"))
            .thenReturn(ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                .body(png));

        mockMvc.perform(get("/api/ai/assets/run-123/sagittal/overlay.png"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
            .andExpect(content().bytes(png));
    }

    @Test
    void getAssetRejectsTraversalBeforeCallingAiModule() throws Exception {
        mockMvc.perform(get("/api/ai/assets/run-123/sagittal/..overlay.png"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void getAssetRejectsAssetOutsideAllowlistBeforeCallingAiModule() throws Exception {
        mockMvc.perform(get("/api/ai/assets/run-123/sagittal/notes.txt"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void getAssetRejectsRawNpyBeforeCallingAiModule() throws Exception {
        mockMvc.perform(get("/api/ai/assets/run-123/sagittal/mask.npy"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void getAssetRejectsModelWeightsBeforeCallingAiModule() throws Exception {
        mockMvc.perform(get("/api/ai/assets/run-123/sagittal/model.pt"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(aiServiceClient);
    }

    @Test
    void getAssetPropagatesNotFoundFromAiModule() throws Exception {
        when(aiServiceClient.getAsset("missing-run", "axial", "input.png"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset no encontrado."));

        mockMvc.perform(get("/api/ai/assets/missing-run/axial/input.png"))
            .andExpect(status().isNotFound());
    }
}
