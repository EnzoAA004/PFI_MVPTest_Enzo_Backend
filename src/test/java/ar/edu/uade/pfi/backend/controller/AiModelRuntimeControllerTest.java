package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.client.AiServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiModelRuntimeControllerTest {
    @Test
    void proxiesRuntimeStatusForAnAuthorizedAdmin() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.getModelRuntime()).thenReturn(Map.of(
            "status", "pytorch_runtime_ready",
            "device", "cpu",
            "torchVersion", "test"
        ));
        RoleAuthorizationService authorizationService = mock(RoleAuthorizationService.class);
        AiModelRuntimeController controller = new AiModelRuntimeController(client, authorizationService);

        Map<String, Object> result = controller.runtime(mock(HttpServletRequest.class));

        assertEquals("pytorch_runtime_ready", result.get("status"));
        assertEquals("cpu", result.get("device"));
        assertEquals(true, result.get("proxiedByBackend"));
    }
}
