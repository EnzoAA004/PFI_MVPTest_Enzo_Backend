package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

class AiBackendServiceInputValidationTest {
    @Test
    void uploadInputRejectsOversizedFileBeforeCallingAiModule() {
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("case-001.png");
        when(file.getSize()).thenReturn(AiBackendService.MAX_INPUT_UPLOAD_BYTES + 1);
        AiBackendService service = new AiBackendService(
            org.mockito.Mockito.mock(AiServiceOperations.class),
            org.mockito.Mockito.mock(ReviewStoreService.class)
        );

        ResponseStatusException ex = assertThrows(
            ResponseStatusException.class,
            () -> service.uploadInput(file, "CASE-001", "sagittal")
        );

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("El archivo supera el tamano maximo permitido por el backend.", ex.getReason());
    }
}
