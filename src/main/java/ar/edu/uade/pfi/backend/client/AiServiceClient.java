package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

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

    public Map<String, Object> getModelRuntime() {
        return getMap("/models/runtime");
    }

    @Override
    public Map<String, Object> syncModels(boolean force) {
        return execute(() -> aiWebClient.post()
            .uri(uriBuilder -> uriBuilder.path("/models/sync").queryParam("force", force).build())
            .exchangeToMono(response -> mapResponseOrError(response, "models/sync"))
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
            .exchangeToMono(response -> mapResponseOrError(response, "pipeline/run"))
            .block(timeout));
    }

    @Override
    public AiInputResponseDto uploadInput(MultipartFile file, String caseId, String plane) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file.getResource())
            .filename(file.getOriginalFilename() == null ? "input" : file.getOriginalFilename());
        body.part("caseId", caseId);
        body.part("plane", plane);
        return execute(() -> aiWebClient.post()
            .uri("/inputs")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(body.build())
            .retrieve()
            .bodyToMono(AiInputResponseDto.class)
            .block(timeout));
    }

    @Override
    public ResponseEntity<byte[]> getAsset(String runId, String plane, String assetName) {
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/assets/{runId}/{plane}/{assetName}").build(runId, plane, assetName))
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.toEntity(byte[].class);
                }
                int status = response.statusCode().value();
                if (status == 403 || status == 404) {
                    return response.releaseBody().then(Mono.error(new ResponseStatusException(response.statusCode(), "AI Module asset request failed")));
                }
                return response.createException().flatMap(Mono::error);
            })
            .block(timeout));
    }

    @Override
    public MultiplanarRunResponseDto runMultiplanar(MultiplanarRunRequestDto request) {
        MultiplanarRunRequestDto tracedRequest = withTraceMetadata(request);
        return execute(() -> aiWebClient.post()
            .uri("/multiplanar/run")
            .bodyValue(tracedRequest)
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.bodyToMono(MultiplanarRunResponseDto.class);
                }
                if (response.statusCode().is4xxClientError()) {
                    return response.bodyToMono(String.class)
                        .defaultIfEmpty("Multiplanar run rejected by AI Module")
                        .flatMap(body -> Mono.error(new ResponseStatusException(response.statusCode(), compactMessage(body))));
                }
                return response.createException().flatMap(Mono::error);
            })
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

    private Mono<Map<String, Object>> mapResponseOrError(org.springframework.web.reactive.function.client.ClientResponse response, String operation) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(MAP_RESPONSE);
        }
        if (response.statusCode().is4xxClientError()) {
            return response.bodyToMono(String.class)
                .defaultIfEmpty(operation + " rejected by AI Module")
                .flatMap(body -> Mono.error(new ResponseStatusException(response.statusCode(), compactMessage(body))));
        }
        return response.bodyToMono(String.class)
            .defaultIfEmpty(operation + " failed in AI Module")
            .flatMap(body -> Mono.error(new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "AI Module responded with status " + response.statusCode().value() + ": " + compactMessage(body)
            )));
    }

    private PipelineRunRequestDto withTraceMetadata(PipelineRunRequestDto request) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            return request;
        }
        Map<String, Object> metadata = mergedTraceMetadata(request.metadata(), traceId);
        return new PipelineRunRequestDto(request.caseId(), request.plane(), request.modelKey(), request.inputPath(), request.inputId(), metadata);
    }

    private MultiplanarRunRequestDto withTraceMetadata(MultiplanarRunRequestDto request) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            return request;
        }
        Map<String, Object> metadata = mergedTraceMetadata(request.metadata(), traceId);
        return new MultiplanarRunRequestDto(
            request.caseId(),
            request.sagittalInputId(),
            request.axialInputId(),
            request.sagittalInputPath(),
            request.axialInputPath(),
            request.sagittalModelKey(),
            request.axialModelKey(),
            request.allowContractFallback(),
            metadata
        );
    }

    private Map<String, Object> mergedTraceMetadata(Map<String, Object> source, String traceId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source != null) {
            metadata.putAll(source);
        }
        metadata.putIfAbsent("traceId", traceId);
        metadata.putIfAbsent("backendTraceId", traceId);
        metadata.putIfAbsent("correlationId", traceId);
        return metadata;
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
        if (unwrapped instanceof ResponseStatusException responseStatusException) {
            return responseStatusException;
        }
        if (unwrapped instanceof WebClientResponseException responseException) {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Module responded with status " + responseException.getStatusCode().value(), responseException);
        }
        if (unwrapped instanceof TimeoutException || compactMessage(unwrapped.getMessage() == null ? "" : unwrapped.getMessage()).toLowerCase().contains("timeout")) {
            return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI Module request timed out", unwrapped);
        }
        String message = unwrapped.getMessage() == null ? "unknown error" : compactMessage(unwrapped.getMessage());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Module is not available: " + message, unwrapped);
    }

    private String compactMessage(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "..." : oneLine;
    }
}
