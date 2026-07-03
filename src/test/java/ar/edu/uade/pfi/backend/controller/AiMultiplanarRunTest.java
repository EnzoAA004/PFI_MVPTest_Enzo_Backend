package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiMultiplanarRunTest {
    @Test
    void proxiesMultiplanarRun() {
        AiServiceOperations ai = (AiServiceOperations) Proxy.newProxyInstance(
            AiServiceOperations.class.getClassLoader(),
            new Class<?>[] {AiServiceOperations.class},
            (proxy, method, args) -> Map.of("status", "multiplanar_run_ready", "runId", "multi-test", "caseId", "CASE-1")
        );
        AiMultiplanarController controller = new AiMultiplanarController(ai);
        MultiplanarRunRequestDto request = new MultiplanarRunRequestDto("CASE-1", null, null, null, null, Map.of());

        Map<String, Object> result = controller.run(request);

        assertEquals("multiplanar_run_ready", result.get("status"));
        assertEquals("multi-test", result.get("runId"));
        assertEquals("CASE-1", result.get("caseId"));
    }
}
