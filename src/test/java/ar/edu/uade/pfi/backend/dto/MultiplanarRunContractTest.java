package ar.edu.uade.pfi.backend.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class MultiplanarRunContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesMultiplanarRunResponseCriticalFields() throws Exception {
        try (InputStream json = getClass().getResourceAsStream("/multiplanar_run_response.mock.json")) {
            assertNotNull(json);

            MultiplanarRunResponseDto response = objectMapper.readValue(json, MultiplanarRunResponseDto.class);

            assertNotNull(response.runId());
            assertFalse(response.runId().isBlank());
            assertNotNull(response.traceId());
            assertFalse(response.traceId().isBlank());
            assertNotNull(response.effectiveInferenceMode());
            assertFalse(response.effectiveInferenceMode().isBlank());
            assertNotNull(response.planes());
            assertNotNull(response.planes().sagital());
            assertNotNull(response.planes().axial());
            assertNotNull(response.planes().sagital().runId());
            assertNotNull(response.planes().sagital().effectiveInferenceMode());
            assertNotNull(response.planes().sagital().assets());
            assertFalse(response.planes().sagital().assets().isEmpty());
            assertNotNull(response.planes().sagital().landmarks());
            assertFalse(response.planes().sagital().landmarks().isEmpty());
            assertNotNull(response.planes().sagital().measurements());
            assertFalse(response.planes().sagital().measurements().isEmpty());
            assertNotNull(response.planes().axial().runId());
            assertNotNull(response.planes().axial().effectiveInferenceMode());
            assertNotNull(response.planes().axial().assets());
            assertFalse(response.planes().axial().assets().isEmpty());
            assertNotNull(response.planes().axial().landmarks());
            assertFalse(response.planes().axial().landmarks().isEmpty());
            assertNotNull(response.planes().axial().measurements());
            assertFalse(response.planes().axial().measurements().isEmpty());
            assertNotNull(response.assets());
            assertFalse(response.assets().isEmpty());
            assertNotNull(response.review());
            assertFalse(response.review().isEmpty());
        }
    }
}
