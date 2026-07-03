package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;

@Component
public class AiServiceClient implements AiServiceOperations {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE = new ParameterizedTypeReference<>() {};

    private final WebClient aiWebClient;
    private final Duration timeout;

    public AiServiceClient(WebClient aiWebClient, AiServiceProperties properties) {
        this.aiWebClient = aiWebClient;
        this.timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
    }

    @Override
    public Map<String, Object> health() {
        return getMap("/health");
    }

    @Override
    public Map<String, Object> readiness() {
        return getMap("/readiness");
    }

    @Override
    public Object models() {
        return execute(() -> aiWebClient.get().uri("/models").retrieve().bodyToMono(Object.class).block(timeout));
    }

    @Override
    public Map<String, Object> verifyModels() {
        return getMap("/models/verify");
    }

    @Override
    public Map<String, Object> syncModels(boolean force) {
        return execute(() -> aiWebClient.post()
            .uri(uriBuilder -> uriBuilder.path("/models/sync").queryParam("force", force).build())
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(timeout));
    }

    @Override
    public Map<String, Object> warmup() {
        return getMap("/warmup");
    }

    @Override
    public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
        PipelineRunRequestDto tracedRequest = withTraceMetadata(request);
        return execute(() -> aiWebClient.post()
            .uri("/pipeline/run")
            .bodyValue(tracedRequest)
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(timeout));
    }

    @Override
    public Map<String, Object> getAgentReport(String runId) {
        return execute(() -> aiWebClient.get().uri("/agent/report/{runId}", runId).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    @Override
    public Map<String, Object> getAgentReportSummary(String runId) {
        return execute(() -> aiWebClient.get().uri("/agent/report/{runId}/summary", runId).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    @Override
    public Map<String, Object> getRecentAgentReports(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/agent/reports").queryParam("limit", safeLimit).build())
            .retrieve()
            .bodyToMono(MAP_RESPONSE)
            .block(timeout));
    }

    @Override
    public Map<String, Object> getEvaluationSummary() {
        return getMap("/evaluation/summary");
    }

    @Override
    public Map<String, Object> getEvaluationEvidence() {
        return getMap("/evaluation/evidence");
    }

    @Override
    public Map<String, Object> getMultiplanarContract() {
        return getMap("/multiplanar/contract");
    }

    private Map<String, Object> getMap(String path) {
        return execute(() -> aiWebClient.get().uri(path).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    private PipelineRunRequestDto withTraceMetadata(PipelineRunRequestDto request) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            return request;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        metadata.putIfAbsent("traceId", traceId);
        metadata.putIfAbsent("backendTraceId", traceId);
        metadata.putIfAbsent("correlationId", traceId);
        return new PipelineRunRequestDto(request.caseId(), request.plane(), request.modelKey(), request.inputPath(), metadata);
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
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Module responded with status " + responseException.getStatusCode().value(), responseException);
        }
        String message = unwrapped.getMessage() == null ? "unknown error" : compactMessage(unwrapped.getMessage());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Module is not available: " + message, unwrapped);
    }

    private String compactMessage(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "..." : oneLine;
    }
}
