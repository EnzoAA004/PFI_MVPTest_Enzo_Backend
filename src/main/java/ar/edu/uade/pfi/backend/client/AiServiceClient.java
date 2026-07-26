package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.AiMultiplanarContractVersion;
import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2RequestDto;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2ResponseDto;
import ar.edu.uade.pfi.backend.dto.AiStructuredErrorV2Dto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.service.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.service.AiMultiplanarUpstreamException;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
    private final AiMultiplanarContractVersion multiplanarContractVersion;
    private final AiMultiplanarV2RequestMapper v2RequestMapper;
    private final AiMultiplanarV2ResponseAdapter v2ResponseAdapter;
    private final AiMultiplanarV1ResponseAdapter v1ResponseAdapter;
    private final MultiplanarRealBaselineContractValidator v1StrictValidator;
    private final MultiplanarV2RealBaselineValidator v2StrictValidator;
    private final ObjectMapper objectMapper;

    public AiServiceClient(
        WebClient aiWebClient,
        AiServiceProperties properties,
        AiMultiplanarV2RequestMapper v2RequestMapper,
        AiMultiplanarV2ResponseAdapter v2ResponseAdapter,
        AiMultiplanarV1ResponseAdapter v1ResponseAdapter,
        MultiplanarRealBaselineContractValidator v1StrictValidator,
        MultiplanarV2RealBaselineValidator v2StrictValidator,
        ObjectMapper objectMapper
    ) {
        this.aiWebClient = aiWebClient;
        this.timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
        this.multiplanarContractVersion = properties.resolvedMultiplanarContractVersion();
        this.v2RequestMapper = v2RequestMapper;
        this.v2ResponseAdapter = v2ResponseAdapter;
        this.v1ResponseAdapter = v1ResponseAdapter;
        this.v1StrictValidator = v1StrictValidator;
        this.v2StrictValidator = v2StrictValidator;
        this.objectMapper = objectMapper;
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

    /**
     * A-F: resolves the configured contract version, builds the appropriate request,
     * calls exactly one endpoint, deserializes to the matching DTO, adapts to
     * CanonicalMultiplanarRun and returns it. There is no fallback between contracts
     * and no retry against the other endpoint on error.
     */
    @Override
    public CanonicalMultiplanarRun runMultiplanar(MultiplanarRunRequestDto request) {
        return multiplanarContractVersion == AiMultiplanarContractVersion.V2
            ? runMultiplanarV2(request)
            : runMultiplanarV1(request);
    }

    private CanonicalMultiplanarRun runMultiplanarV1(MultiplanarRunRequestDto request) {
        MultiplanarRunRequestDto tracedRequest = withTraceMetadata(request);
        MultiplanarRunResponseDto response = execute(() -> aiWebClient.post()
            .uri("/multiplanar/run")
            .bodyValue(tracedRequest)
            .exchangeToMono(clientResponse -> {
                if (clientResponse.statusCode().is2xxSuccessful()) {
                    return clientResponse.bodyToMono(MultiplanarRunResponseDto.class);
                }
                if (clientResponse.statusCode().is4xxClientError()) {
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("Multiplanar run rejected by AI Module")
                        .flatMap(body -> Mono.error(new ResponseStatusException(clientResponse.statusCode(), compactMessage(body))));
                }
                return clientResponse.createException().flatMap(Mono::error);
            })
            .block(timeout));
        v1StrictValidator.validate(tracedRequest, response);
        return v1ResponseAdapter.toCanonical(response);
    }

    private CanonicalMultiplanarRun runMultiplanarV2(MultiplanarRunRequestDto request) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        AiMultiplanarV2RequestDto v2Request = v2RequestMapper.toV2Request(request, traceId);
        TraceIdConsistencyGuard.require(v2Request.traceId(), traceId);

        AiMultiplanarV2ResponseDto response = executeV2(() -> aiWebClient.post()
            .uri("/v2/multiplanar/run")
            .headers(headers -> {
                if (traceId != null && !traceId.isBlank()) {
                    headers.set(TraceIdFilter.TRACE_ID_HEADER, traceId);
                }
            })
            .bodyValue(v2Request)
            .exchangeToMono(clientResponse -> {
                if (clientResponse.statusCode().is2xxSuccessful()) {
                    return clientResponse.bodyToMono(AiMultiplanarV2ResponseDto.class);
                }
                return clientResponse.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(buildV2Error(clientResponse.statusCode(), body, traceId)));
            })
            .block(timeout), traceId);

        CanonicalMultiplanarRun canonical = v2ResponseAdapter.toCanonical(response);
        v2StrictValidator.validate(request, canonical);
        return canonical;
    }

    private RuntimeException buildV2Error(HttpStatusCode originalStatus, String body, String traceId) {
        AiStructuredErrorV2Dto structured = tryParseStructuredError(body);
        if (structured != null && structured.code() != null && !structured.code().isBlank()) {
            AiMultiplanarV2ErrorCodeMapper.Mapped mapped = AiMultiplanarV2ErrorCodeMapper.resolve(structured.code());
            String message = structured.message() == null || structured.message().isBlank()
                ? "AI Module (v2) rejected the multiplanar request"
                : compactMessage(structured.message());
            String aiTraceId = structured.traceId() == null || structured.traceId().isBlank() ? traceId : structured.traceId();
            return new AiMultiplanarUpstreamException(mapped.status(), mapped.backendCode(), message, aiTraceId);
        }
        AiMultiplanarV2ErrorCodeMapper.Mapped unknown = AiMultiplanarV2ErrorCodeMapper.UNKNOWN;
        return new AiMultiplanarUpstreamException(unknown.status(), unknown.backendCode(), "AI Module (v2) respondio con un error no estructurado (status " + originalStatus.value() + ")", traceId);
    }

    private AiStructuredErrorV2Dto tryParseStructuredError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readValue(body, AiStructuredErrorV2Dto.class);
        } catch (Exception ex) {
            return null;
        }
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

    private <T> T executeV2(Supplier<T> supplier, String traceId) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            Throwable unwrapped = Exceptions.unwrap(ex);
            if (unwrapped instanceof AiMultiplanarUpstreamException upstream) {
                throw upstream;
            }
            if (unwrapped instanceof AiMultiplanarContractViolationException contractViolation) {
                throw contractViolation;
            }
            if (isTimeout(unwrapped)) {
                throw new AiMultiplanarUpstreamException(HttpStatus.GATEWAY_TIMEOUT, "AI_MODULE_TIMEOUT", "AI Module (v2) no respondio a tiempo", traceId);
            }
            String message = unwrapped.getMessage() == null ? "unknown error" : compactMessage(unwrapped.getMessage());
            throw new AiMultiplanarUpstreamException(HttpStatus.BAD_GATEWAY, "AI_MODULE_ERROR", "AI Module (v2) no esta disponible: " + message, traceId);
        }
    }

    private boolean isTimeout(Throwable unwrapped) {
        return unwrapped instanceof TimeoutException
            || compactMessage(unwrapped.getMessage() == null ? "" : unwrapped.getMessage()).toLowerCase(Locale.ROOT).contains("timeout");
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
