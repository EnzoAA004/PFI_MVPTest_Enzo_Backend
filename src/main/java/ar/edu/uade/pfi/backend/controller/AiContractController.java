package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.util.ResponseNormalizer;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;

@RestController
@RequestMapping("/api/ai/pipeline")
public class AiContractController {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
        new ParameterizedTypeReference<>() {};

    private final WebClient aiWebClient;
    private final Duration timeout;

    public AiContractController(WebClient aiWebClient, AiServiceProperties properties) {
        this.aiWebClient = aiWebClient;
        this.timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
    }

    @GetMapping("/schema")
    public Map<String, Object> pipelineSchema() {
        try {
            Map<String, Object> response = aiWebClient.get()
                .uri("/pipeline/schema")
                .retrieve()
                .bodyToMono(MAP_RESPONSE)
                .block(timeout);
            Map<String, Object> normalized = ResponseNormalizer.normalizeMap(response);
            normalized.put("proxiedByBackend", true);
            normalized.put("humanReviewRequired", true);
            normalized.put("notClinicalDiagnosis", true);
            return normalized;
        } catch (RuntimeException ex) {
            throw translateException(ex);
        }
    }

    private ResponseStatusException translateException(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (unwrapped instanceof WebClientResponseException responseException) {
            return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "AI Module responded with status " + responseException.getStatusCode().value(),
                responseException
            );
        }
        String message = unwrapped.getMessage() == null ? "unknown error" : compactMessage(unwrapped.getMessage());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Module is not available: " + message, unwrapped);
    }

    private String compactMessage(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "..." : oneLine;
    }
}
