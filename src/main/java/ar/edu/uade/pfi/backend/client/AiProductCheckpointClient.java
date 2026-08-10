package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.dto.DiscDegenerativeProductRequestDto;
import ar.edu.uade.pfi.backend.dto.DiscSegmentationSourceDto;
import ar.edu.uade.pfi.backend.dto.FullSeriesSegmentationRequestDto;
import ar.edu.uade.pfi.backend.web.error.ApiErrorCode;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Versioned P10.9 client surface.
 *
 * <p>This class is intentionally small and additive: it leaves the proven P10.6 client paths
 * untouched while the new AI product checkpoint is still under E2E validation. It never forwards
 * browser credentials and never includes an upstream response body in a public exception.
 */
@Component
public class AiProductCheckpointClient {
  private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
      new ParameterizedTypeReference<>() {};
  private static final Pattern SAFE_RUN_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,96}$");
  private static final Pattern SAFE_ASSET = Pattern.compile("^(original|overlay)\\.png$");

  private final WebClient aiWebClient;
  private final Duration timeout;
  private final Duration diagnosticTimeout;

  public AiProductCheckpointClient(WebClient aiWebClient, AiServiceProperties properties) {
    this.aiWebClient = aiWebClient;
    this.timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
    this.diagnosticTimeout = Duration.ofSeconds(properties.resolvedDiagnosticTimeoutSeconds());
  }

  public Map<String, Object> productCheckpoint() {
    return aiWebClient
        .get()
        .uri("/v2/product-checkpoint/contract")
        .headers(headers -> applyTrace(headers, resolveTraceId()))
        .exchangeToMono(response -> mapOrSafeError(response, "AI product checkpoint unavailable"))
        .block(diagnosticTimeout);
  }

  public Map<String, Object> runFullSeriesSegmentation(FullSeriesSegmentationRequestDto request) {
    String traceId = resolveTraceId();
    return aiWebClient
        .post()
        .uri("/v2/series-segmentation/run")
        .headers(headers -> applyTrace(headers, traceId))
        .bodyValue(request)
        .exchangeToMono(response -> mapOrSafeError(response, "AI full-series segmentation failed"))
        .block(timeout);
  }

  public ResponseEntity<byte[]> getFullSeriesAsset(
      String runId, String plane, int index, String assetName) {
    if (!SAFE_RUN_ID.matcher(runId == null ? "" : runId).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId invalido.");
    }
    if (!("sagittal".equals(plane) || "axial".equals(plane))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "plane invalido.");
    }
    if (index < 0 || index > 511) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slice index invalido.");
    }
    if (!SAFE_ASSET.matcher(assetName == null ? "" : assetName).matches()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "asset no disponible.");
    }

    String traceId = resolveTraceId();
    return aiWebClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/v2/series-segmentation/{runId}/{plane}/slices/{index}/{assetName}")
                    .build(runId, plane, index, assetName))
        .headers(headers -> applyTrace(headers, traceId))
        .exchangeToMono(
            response -> {
              if (response.statusCode().is2xxSuccessful()) {
                return response.toEntity(byte[].class);
              }
              if (response.statusCode().is4xxClientError()) {
                return response
                    .releaseBody()
                    .then(
                        Mono.error(
                            new ResponseStatusException(
                                response.statusCode(), "AI series asset unavailable")));
              }
              return response.releaseBody().then(Mono.error(upstreamUnavailable()));
            })
        .block(timeout);
  }

  public Map<String, Object> predictDiscDegenerativeFromSegmentation(
      DiscDegenerativeProductRequestDto request) {
    String traceId = resolveTraceId();
    Map<String, Object> upstream = new LinkedHashMap<>();
    upstream.put("caseId", request.caseId());
    upstream.put("sources", request.sources().stream().map(this::sourceMap).toList());
    return aiWebClient
        .post()
        .uri("/v2/degenerative-findings/disc-multitask/from-series-segmentation")
        .headers(headers -> applyTrace(headers, traceId))
        .bodyValue(upstream)
        .exchangeToMono(response -> mapOrSafeError(response, "AI disc findings request failed"))
        .block(timeout);
  }

  private Map<String, Object> sourceMap(DiscSegmentationSourceDto source) {
    return Map.of(
        "role", source.role(),
        "inputId", source.inputId(),
        "segmentationRunId", source.segmentationRunId());
  }

  private Mono<Map<String, Object>> mapOrSafeError(
      org.springframework.web.reactive.function.client.ClientResponse response,
      String publicMessage) {
    if (response.statusCode().is2xxSuccessful()) {
      return response.bodyToMono(MAP_RESPONSE);
    }
    if (response.statusCode().is4xxClientError()) {
      return response
          .releaseBody()
          .then(Mono.error(new ResponseStatusException(response.statusCode(), publicMessage)));
    }
    return response.releaseBody().then(Mono.error(upstreamUnavailable()));
  }

  private String resolveTraceId() {
    String current = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
    return current == null || current.isBlank() ? "technical-" + UUID.randomUUID() : current;
  }

  private void applyTrace(HttpHeaders headers, String traceId) {
    headers.set(TraceIdFilter.TRACE_ID_HEADER, traceId);
  }

  private ResponseStatusException upstreamUnavailable() {
    return new ResponseStatusException(
        HttpStatus.BAD_GATEWAY, ApiErrorCode.UPSTREAM_UNAVAILABLE.publicMessage());
  }
}
