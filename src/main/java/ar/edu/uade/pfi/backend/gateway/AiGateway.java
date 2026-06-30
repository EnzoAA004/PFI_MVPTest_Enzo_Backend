package ar.edu.uade.pfi.backend.gateway;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiGateway {
    public Map<String, Object> health() {
        return Map.of("status", "ready");
    }
}
