package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.client.AiServiceClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiModelRuntimeControllerTest {
    @Test
    void proxiesRuntimeStatus() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.getModelRuntime()).thenReturn(Map.of(
            "status", "pytorch_runtime_ready",
            "device", "cpu",
            "torchVersion", "test"
        ));
        AiModelRuntimeController controller = new AiModelRuntimeController(client);

        Map<String, Object> result = controller.runtime();

        assertEquals("pytorch_runtime_ready", result.get("status"));
        assertEquals("cpu", result.get("device"));
        assertEquals(true, result.get("proxiedByBackend"));
    }
}
