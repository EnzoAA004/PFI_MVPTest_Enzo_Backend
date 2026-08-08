package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator;
import ar.edu.uade.pfi.backend.service.exceptions.AiMultiplanarUpstreamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class AiServiceClientMultiplanarContractDispatchTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearTrace() {
    MDC.clear();
  }

  @Test
  void v1ConfigCallsLegacyEndpointExactlyOnceAndNeverV2() {
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    AtomicInteger callCount = new AtomicInteger();
    WebClient webClient =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  captured.set(request);
                  callCount.incrementAndGet();
                  return Mono.just(
                      ClientResponse.create(HttpStatus.OK)
                          .header("Content-Type", "application/json")
                          .body(v1ResponseJson())
                          .build());
                })
            .build();
    AiServiceClient client = newClient(webClient, "v1");

    // Not a strict real_baseline request: the raw v1 fixture below is intentionally
    // minimal (only exercises endpoint routing + adaptation), so it must not trigger
    // the full strict-contract assertions.
    CanonicalMultiplanarRun response = client.runMultiplanar(nonStrictSagittalOnlyRequest());

    assertEquals(1, callCount.get());
    assertEquals("/multiplanar/run", captured.get().url().toString());
    assertEquals("multi-legacy-1", response.multiplanarRunId());
  }

  @Test
  void v2ConfigCallsV2EndpointExactlyOnceAndNeverV1() throws Exception {
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-dispatch-1");
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    AtomicInteger callCount = new AtomicInteger();
    String v2Json =
        Files.readString(
            Path.of("src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json"));
    WebClient webClient =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  captured.set(request);
                  callCount.incrementAndGet();
                  return Mono.just(
                      ClientResponse.create(HttpStatus.OK)
                          .header("Content-Type", "application/json")
                          .body(v2Json)
                          .build());
                })
            .build();
    AiServiceClient client = newClient(webClient, "v2");

    CanonicalMultiplanarRun response =
        client.runMultiplanar(strictSagittalOnlyRequest("trace-dispatch-1"));

    assertEquals(1, callCount.get());
    assertEquals("/v2/multiplanar/run", captured.get().url().toString());
    assertEquals(
        "trace-dispatch-1", captured.get().headers().getFirst(TraceIdFilter.TRACE_ID_HEADER));
    assertEquals("multi-32d66dabd290b661c709", response.multiplanarRunId());
    assertEquals("pfi.multiplanar-run.v2", response.schemaVersion());
    assertEquals("sagittal_only", response.workspaceMode());
    assertTrue(response.sagittal() != null);
    assertNull(response.axial());
    assertTrue(response.governance().humanReviewRequired());
    assertTrue(response.governance().notClinicalDiagnosis());
  }

  @Test
  void v2StructuredErrorPreservesCodeAndAiTraceId() {
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-dispatch-err");
    WebClient webClient =
        WebClient.builder()
            .exchangeFunction(
                request ->
                    Mono.just(
                        ClientResponse.create(HttpStatus.CONFLICT)
                            .header("Content-Type", "application/json")
                            .body(
                                """
                    {
                      "status": "error",
                      "schemaVersion": "pfi.multiplanar-run.v2",
                      "code": "MODEL_NOT_READY",
                      "message": "model checkpoint warming up",
                      "traceId": "ai-trace-999",
                      "caseId": "CASE-001",
                      "requestedPlanes": ["sagittal"],
                      "details": {},
                      "governance": {"humanReviewRequired": true, "notClinicalDiagnosis": true, "deidentified": true, "diagnosisGenerated": false}
                    }
                    """)
                            .build()))
            .build();
    AiServiceClient client = newClient(webClient, "v2");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class,
            () -> client.runMultiplanar(strictSagittalOnlyRequest("trace-dispatch-err")));

    assertEquals(HttpStatus.CONFLICT, ex.status());
    assertEquals("AI_MODEL_NOT_READY", ex.code());
    assertEquals("ai-trace-999", ex.aiTraceId());
  }

  @Test
  void v2TimeoutProducesGatewayTimeoutWithAiModuleTimeoutCode() {
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-dispatch-timeout");
    WebClient webClient =
        WebClient.builder()
            .exchangeFunction(
                request -> Mono.error(new TimeoutException("simulated upstream timeout")))
            .build();
    AiServiceClient client = newClient(webClient, "v2");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class,
            () -> client.runMultiplanar(strictSagittalOnlyRequest("trace-dispatch-timeout")));

    assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex.status());
    assertEquals("AI_MODULE_TIMEOUT", ex.code());
  }

  private AiServiceClient newClient(WebClient webClient, String contractVersion) {
    return new AiServiceClient(
        webClient,
        new AiServiceProperties("http://ai-module", 60, contractVersion),
        new AiMultiplanarV2RequestMapper(),
        new AiMultiplanarV2ResponseAdapter(objectMapper),
        new AiMultiplanarV1ResponseAdapter(),
        new MultiplanarRealBaselineContractValidator(),
        new MultiplanarV2RealBaselineValidator(),
        objectMapper);
  }

  private MultiplanarRunRequestDto nonStrictSagittalOnlyRequest() {
    return new MultiplanarRunRequestDto(
        "CASE-001",
        "inp_sagittal_001",
        null,
        null,
        null,
        "sagittal_spider",
        "axial_t2_alkafri",
        true,
        Map.of("inferenceMode", "real_baseline"));
  }

  private MultiplanarRunRequestDto strictSagittalOnlyRequest(String traceId) {
    return new MultiplanarRunRequestDto(
        "P9A-SPIDER-101-T2",
        "inp_2822bf9640ab40a289050a30ce2fe6fd",
        null,
        null,
        null,
        "sagittal_spider",
        "axial_t2_alkafri",
        false,
        Map.of("inferenceMode", "real_baseline", "traceId", traceId));
  }

  private String v1ResponseJson() {
    return """
            {
              "runId": "multi-legacy-1",
              "traceId": "trace-legacy-1",
              "status": "multiplanar_run_ready",
              "schemaVersion": "multiplanar-run-v1",
              "caseId": "CASE-001",
              "effectiveInferenceMode": "real_baseline",
              "planes": {"sagittal": null, "axial": null},
              "humanReviewRequired": true,
              "notClinicalDiagnosis": true
            }
            """;
  }
}
