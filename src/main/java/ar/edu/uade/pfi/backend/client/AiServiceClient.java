package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;

@Component
public class AiServiceClient implements AiServiceOperations {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
        new ParameterizedTypeReference<>() {};

    private final WebClient aiWebClient;
    private final Duration timeout;

    public AiServiceClient(WebClient aiWebClient, AiServiceProperties properties) {
        this.aiWebClient = aiWebClient;
        this.timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
    }

    @Override
    public Map<String, Object> health() {
        return execute(() -> aiWebClient.get()
            .uri("/health")
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(timeout));
    }

    @Override
    public Object models() {
        return execute(() -> aiWebClient.get()
            .uri("/models")
            .retrieve()
            .bodyToMono(Object.class)
            .block(timeout));
    }

    @Override
    public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
        return execute(() -> aiWebClient.post()
            .uri("/pipeline/run")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(timeout));
    }

    @Override
    public Map<String, Object> getAgentReport(String runId) {
        return execute(() -> aiWebClient.get()
            .uri("/agent/report/{runId}", runId)
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(timeout));
    }

    private <T> T execute(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            throw translateException(ex);
        }
    }

    public ResponseStatusException translateException(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (unwrapped instanceof WebClientResponseException responseException) {
            String responseBody = responseException.getResponseBodyAsString();
            String detail = responseBody == null || responseBody.isBlank()
                ? "AI Module responded with status " + responseException.getStatusCode().value()
                : "AI Module responded with status " + responseException.getStatusCode().value() + ": " + responseBody;
            return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                detail,
                responseException
            );
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Module is not available: " + unwrapped.getMessage(), unwrapped);
    }
}
