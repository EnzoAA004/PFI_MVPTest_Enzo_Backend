package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.AiMultiplanarContractVersion;
import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.SafeLogSanitizer;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.config.error.ApiErrorCode;
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
import ar.edu.uade.pfi.backend.service.OperationalMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

/**
 * The only HTTP client for the AI Module. Never sends the user's JWT/refresh token,
 * email, or roles — this call is backend-to-AI-Module, not a passthrough of the
 * frontend's own credentials. Every call carries an X-Trace-Id header: the current
 * request's trace id (read from MDC, set by TraceIdFilter) when there is one, or a
 * freshly generated {@code technical-<uuid>} — resolved once per call, never stored
 * globally, never leaked across threads — for a background/non-HTTP-request process with
 * nothing in MDC. See docs/P10_B_ERRORS_AUDIT_OBSERVABILITY.md §9/§14.
 *
 * P10-B.1: never builds a public exception message by concatenating the upstream
 * response body, an unwrapped exception's own message, a WebClientResponseException
 * body, or a host/URL — every public message here comes from
 * {@link ApiErrorCode#publicMessage()}. The upstream body is only ever used to
 * deserialize {@link AiStructuredErrorV2Dto} (the v2 structured-error contract); when it
 * isn't structured, it is discarded entirely — not even its length is logged unsanitized.
 */
@Component
public class AiServiceClient implements AiServiceOperations {
    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);
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
    private final OperationalMetricsService metrics;

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
        this(aiWebClient, properties, v2RequestMapper, v2ResponseAdapter, v1ResponseAdapter, v1StrictValidator, v2StrictValidator, objectMapper, null);
    }

    @Autowired
    public AiServiceClient(
        WebClient aiWebClient,
        AiServiceProperties properties,
        AiMultiplanarV2RequestMapper v2RequestMapper,
        AiMultiplanarV2ResponseAdapter v2ResponseAdapter,
        AiMultiplanarV1ResponseAdapter v1ResponseAdapter,
        MultiplanarRealBaselineContractValidator v1StrictValidator,
        MultiplanarV2RealBaselineValidator v2StrictValidator,
        ObjectMapper objectMapper,
        @Nullable OperationalMetricsService metrics
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
        this.metrics = metrics;
    }

    /**
     * Current request's trace id from MDC, or a fresh {@code technical-<uuid>} — resolved
     * once by the caller and reused for the whole logical call (header + v2 body, where
     * applicable). Never written back to MDC — this is a per-call local value only.
     */
    private String resolveTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return "technical-" + UUID.randomUUID();
    }

    /** Every AI Module call carries a trace id header — see class docs and {@link #resolveTraceId()}. */
    private void applyTraceHeader(HttpHeaders headers) {
        headers.set(TraceIdFilter.TRACE_ID_HEADER, resolveTraceId());
    }

    private void applyTraceHeader(HttpHeaders headers, String traceId) {
        headers.set(TraceIdFilter.TRACE_ID_HEADER, traceId);
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
        return execute(() -> aiWebClient.get().uri("/models").headers(this::applyTraceHeader).retrieve().bodyToMono(Object.class).block(timeout));
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
            .headers(this::applyTraceHeader)
            .exchangeToMono(response -> mapResponseOrError(response))
            .block(timeout));
    }

    @Override
    public Map<String, Object> warmup() {
        return getMap("/warmup");
    }

    @Override
    public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
        String traceId = resolveTraceId();
        PipelineRunRequestDto tracedRequest = withTraceMetadata(request, traceId);
        return execute(() -> aiWebClient.post()
            .uri("/pipeline/run")
            .headers(headers -> applyTraceHeader(headers, traceId))
            .bodyValue(tracedRequest)
            .exchangeToMono(response -> mapResponseOrError(response))
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
            .headers(this::applyTraceHeader)
            .bodyValue(body.build())
            .retrieve()
            .bodyToMono(AiInputResponseDto.class)
            .block(timeout));
    }

    @Override
    public Map<String, Object> uploadStudy(MultipartFile file, String caseId) {
        String traceId = resolveTraceId();
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", file.getResource())
            .filename(file.getOriginalFilename() == null ? "study.zip" : file.getOriginalFilename());
        body.part("caseId", caseId);
        return execute(() -> aiWebClient.post()
            .uri("/inputs/study")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .headers(headers -> applyTraceHeader(headers, traceId))
            .bodyValue(body.build())
            .exchangeToMono(response -> mapStudyUploadResponseOrError(response, traceId))
            .block(timeout));
    }

    @Override
    public ResponseEntity<byte[]> getAsset(String runId, String plane, String assetName) {
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/assets/{runId}/{plane}/{assetName}").build(runId, plane, assetName))
            .headers(this::applyTraceHeader)
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.toEntity(byte[].class);
                }
                int status = response.statusCode().value();
                if (status == 403 || status == 404) {
                    return response.releaseBody().then(Mono.error(new ResponseStatusException(response.statusCode(), "AI Module asset request failed")));
                }
                return response.releaseBody().then(Mono.error(upstreamUnavailable()));
            })
            .block(timeout));
    }

    /**
     * One slice of a stored series, whether or not a model ever ran on it.
     *
     * <p>Addressed by input and not by run because these series have no run: they are
     * the ones the study carried and the AI does not analyse — the T1s, the axial T1
     * with no model, the localizer — and the reader needs them all the same.
     */
    public ResponseEntity<byte[]> getSeriesSlice(String inputId, int index) {
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/inputs/{inputId}/slices/{index}").build(inputId, index))
            .headers(this::applyTraceHeader)
            .exchangeToMono(response -> {
                if (response.statusCode().is2xxSuccessful()) {
                    return response.toEntity(byte[].class);
                }
                int status = response.statusCode().value();
                if (status == 403 || status == 404) {
                    return response.releaseBody().then(Mono.error(new ResponseStatusException(response.statusCode(), "AI Module series slice request failed")));
                }
                return response.releaseBody().then(Mono.error(upstreamUnavailable()));
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

    /**
     * A call only counts as {@code aiCallsSucceeded} once the whole logical operation —
     * HTTP, deserialization, adapter, contract validation — has completed and produced a
     * usable canonical result (P10-B.1 §12). An HTTP 200 with an invalid contract is a
     * failure, not a success; recorded exactly once, whichever stage throws.
     */
    private CanonicalMultiplanarRun runMultiplanarV1(MultiplanarRunRequestDto request) {
        String traceId = resolveTraceId();
        MultiplanarRunRequestDto tracedRequest = withTraceMetadata(request, traceId);
        long startedAt = System.currentTimeMillis();
        try {
            MultiplanarRunResponseDto response = executeHttp(() -> aiWebClient.post()
                .uri("/multiplanar/run")
                .headers(headers -> applyTraceHeader(headers, traceId))
                .bodyValue(tracedRequest)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(MultiplanarRunResponseDto.class);
                    }
                    if (clientResponse.statusCode().is4xxClientError()) {
                        return clientResponse.releaseBody().then(Mono.error(
                            new ResponseStatusException(clientResponse.statusCode(), "El modulo de IA rechazo la solicitud multiplanar.")));
                    }
                    return clientResponse.releaseBody().then(Mono.error(upstreamUnavailable()));
                })
                .block(timeout));
            v1StrictValidator.validate(tracedRequest, response);
            CanonicalMultiplanarRun canonical = v1ResponseAdapter.toCanonical(response);
            recordAiCall(true, startedAt);
            return canonical;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            if (ex instanceof AiMultiplanarContractViolationException) {
                incrementContractViolations();
            }
            throw ex;
        }
    }

    private CanonicalMultiplanarRun runMultiplanarV2(MultiplanarRunRequestDto request) {
        String traceId = resolveTraceId();
        long startedAt = System.currentTimeMillis();
        try {
            AiMultiplanarV2RequestDto v2Request = v2RequestMapper.toV2Request(request, traceId);
            TraceIdConsistencyGuard.require(v2Request.traceId(), traceId);

            AiMultiplanarV2ResponseDto response = dispatchV2Http(() -> aiWebClient.post()
                .uri("/v2/multiplanar/run")
                .headers(headers -> applyTraceHeader(headers, traceId))
                .bodyValue(v2Request)
                .exchangeToMono(clientResponse -> {
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(AiMultiplanarV2ResponseDto.class);
                    }
                    return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(buildV2Error(body, traceId)));
                })
                .block(timeout), traceId);

            CanonicalMultiplanarRun canonical = v2ResponseAdapter.toCanonical(response);
            v2StrictValidator.validate(request, canonical);
            recordAiCall(true, startedAt);
            return canonical;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            if (ex instanceof AiMultiplanarContractViolationException) {
                incrementContractViolations();
            }
            throw ex;
        }
    }

    private void incrementContractViolations() {
        if (metrics != null) metrics.incrementAiContractViolations();
    }

    /**
     * Only ever deserializes the upstream body into {@link AiStructuredErrorV2Dto} — the
     * raw body/message is never copied into the public exception. Technical details
     * (structured code, upstream trace id) are kept only as internal fields on the
     * resulting exception; the public message always comes from
     * {@link ApiErrorCode#publicMessage()} via {@link AiMultiplanarV2ErrorCodeMapper}.
     */
    private RuntimeException buildV2Error(String body, String traceId) {
        AiStructuredErrorV2Dto structured = tryParseStructuredError(body);
        AiMultiplanarV2ErrorCodeMapper.Mapped mapped = structured != null && structured.code() != null && !structured.code().isBlank()
            ? AiMultiplanarV2ErrorCodeMapper.resolve(structured.code())
            : AiMultiplanarV2ErrorCodeMapper.UNKNOWN;
        String aiTraceId = structured != null && structured.traceId() != null && !structured.traceId().isBlank()
            ? structured.traceId()
            : traceId;
        return new AiMultiplanarUpstreamException(mapped.status(), mapped.backendCode().name(), mapped.publicMessage(), aiTraceId);
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
        return execute(() -> aiWebClient.get().uri("/agent/report/{runId}", runId).headers(this::applyTraceHeader).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    @Override
    public Map<String, Object> getAgentReportSummary(String runId) {
        return execute(() -> aiWebClient.get().uri("/agent/report/{runId}/summary", runId).headers(this::applyTraceHeader).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    @Override
    public Map<String, Object> getRecentAgentReports(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return execute(() -> aiWebClient.get()
            .uri(uriBuilder -> uriBuilder.path("/agent/reports").queryParam("limit", safeLimit).build())
            .headers(this::applyTraceHeader)
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
        return execute(() -> aiWebClient.get().uri(path).headers(this::applyTraceHeader).retrieve().bodyToMono(MAP_RESPONSE).block(timeout));
    }

    /** Never surfaces the upstream body — a rejection just carries a fixed, safe public message. */
    private Mono<Map<String, Object>> mapResponseOrError(org.springframework.web.reactive.function.client.ClientResponse response) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.bodyToMono(MAP_RESPONSE);
        }
        if (response.statusCode().is4xxClientError()) {
            return response.releaseBody().then(Mono.error(
                new ResponseStatusException(response.statusCode(), "La solicitud enviada al modulo de IA es invalida.")));
        }
        return response.releaseBody().then(Mono.error(upstreamUnavailable()));
    }

    private Mono<Map<String, Object>> mapStudyUploadResponseOrError(org.springframework.web.reactive.function.client.ClientResponse response, String fallbackTraceId) {
        if (response.statusCode().is2xxSuccessful()) {
            return response.toEntity(MAP_RESPONSE).map(entity -> {
                Map<String, Object> body = new LinkedHashMap<>(entity.getBody() == null ? Map.of() : entity.getBody());
                String upstreamTraceId = entity.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER);
                body.put("traceId", upstreamTraceId == null || upstreamTraceId.isBlank() ? fallbackTraceId : upstreamTraceId);
                return body;
            });
        }
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        if (status == HttpStatus.BAD_REQUEST) {
            return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "El estudio enviado al modulo de IA es invalido.")));
        }
        if (status == HttpStatus.PAYLOAD_TOO_LARGE) {
            return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "El estudio supera el tamano maximo permitido.")));
        }
        if (status == HttpStatus.UNPROCESSABLE_ENTITY) {
            return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "El estudio no contiene planos utilizables.")));
        }
        return response.releaseBody().then(Mono.error(upstreamUnavailable()));
    }

    private ResponseStatusException upstreamUnavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiErrorCode.UPSTREAM_UNAVAILABLE.publicMessage());
    }

    private PipelineRunRequestDto withTraceMetadata(PipelineRunRequestDto request, String traceId) {
        Map<String, Object> metadata = mergedTraceMetadata(request.metadata(), traceId);
        return new PipelineRunRequestDto(request.caseId(), request.plane(), request.modelKey(), request.inputPath(), request.inputId(), metadata);
    }

    private MultiplanarRunRequestDto withTraceMetadata(MultiplanarRunRequestDto request, String traceId) {
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
            request.studySeries(),
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

    /** Simple calls (health/models/assets/...) — the HTTP call itself is the whole logical operation, so success/failure is recorded automatically around it. */
    private <T> T execute(Supplier<T> supplier) {
        long startedAt = System.currentTimeMillis();
        try {
            T result = supplier.get();
            recordAiCall(true, startedAt);
            return result;
        } catch (RuntimeException ex) {
            recordAiCall(false, startedAt);
            throw translateException(ex);
        }
    }

    /** Like {@link #execute}, but without metric recording — used inside {@link #runMultiplanarV1}, which records around the full HTTP+validate+adapt pipeline instead. */
    private <T> T executeHttp(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            throw translateException(ex);
        }
    }

    /** Like {@link #executeHttp}, for the v2 dispatch — metric recording happens once, around the full pipeline, in {@link #runMultiplanarV2}. */
    private <T> T dispatchV2Http(Supplier<T> supplier, String traceId) {
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
                throw new AiMultiplanarUpstreamException(HttpStatus.GATEWAY_TIMEOUT, ApiErrorCode.AI_MODULE_TIMEOUT.name(), ApiErrorCode.AI_MODULE_TIMEOUT.publicMessage(), traceId);
            }
            throw new AiMultiplanarUpstreamException(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_MODULE_ERROR.name(), ApiErrorCode.AI_MODULE_ERROR.publicMessage(), traceId);
        }
    }

    private void recordAiCall(boolean succeeded, long startedAtMs) {
        if (metrics == null) return;
        metrics.recordAiCall(succeeded, System.currentTimeMillis() - startedAtMs);
    }

    private boolean isTimeout(Throwable unwrapped) {
        return unwrapped instanceof TimeoutException;
    }

    /**
     * Translates a transport-level failure to a {@link ResponseStatusException} carrying
     * only a fixed public message — never the upstream response body, an unwrapped
     * exception's own message, a host, or a URL. A sanitized, DEBUG-only technical line
     * is logged separately for troubleshooting.
     */
    public ResponseStatusException translateException(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        if (unwrapped instanceof ResponseStatusException responseStatusException) {
            return responseStatusException;
        }
        logTechnicalDetail(unwrapped);
        if (unwrapped instanceof WebClientResponseException) {
            return upstreamUnavailable();
        }
        if (isTimeout(unwrapped)) {
            return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, ApiErrorCode.AI_MODULE_TIMEOUT.publicMessage(), unwrapped);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiErrorCode.UPSTREAM_UNAVAILABLE.publicMessage(), unwrapped);
    }

    /** DEBUG-only, sanitized — never at the default log level, never in a response body. */
    private void logTechnicalDetail(Throwable unwrapped) {
        if (!log.isDebugEnabled()) return;
        log.debug("event=ai_module_call_failed exceptionType={} message={}",
            unwrapped.getClass().getSimpleName(),
            SafeLogSanitizer.sanitizeMessage(unwrapped.getMessage()));
    }
}
