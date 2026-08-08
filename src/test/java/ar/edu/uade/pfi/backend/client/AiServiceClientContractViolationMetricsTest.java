package ar.edu.uade.pfi.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.service.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator;
import ar.edu.uade.pfi.backend.service.OperationalMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * P10-B.1 §13: a single multiplanar contract violation must increment {@code aiContractViolations}
 * exactly once — P10-B had both {@code AiServiceClient} and {@code ApiExceptionHandler}
 * incrementing it for the same exception, double-counting every violation. The strict validator is
 * mocked to throw on demand rather than relying on fragile real-contract-shape violations, since
 * only the counting behavior is under test here (contract-shape correctness itself is covered
 * elsewhere).
 */
class AiServiceClientContractViolationMetricsTest {
  @RegisterExtension static WireMockExtension wireMock = WireMockExtension.newInstance().build();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearTrace() {
    MDC.clear();
  }

  @Test
  void v2StrictValidatorFailureIncrementsAiContractViolationsExactlyOnce() throws Exception {
    String v2Json =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json"));
    wireMock.stubFor(
        post(urlEqualTo("/v2/multiplanar/run"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(v2Json)));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-contract-violation-metrics");

    MultiplanarV2RealBaselineValidator failingValidator =
        mock(MultiplanarV2RealBaselineValidator.class);
    doThrow(new AiMultiplanarContractViolationException("synthetic strict-validator failure"))
        .when(failingValidator)
        .validate(any(), any());
    OperationalMetricsService metrics = new OperationalMetricsService();

    WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
    AiServiceClient client =
        new AiServiceClient(
            webClient,
            new AiServiceProperties(wireMock.baseUrl(), 10, "v2"),
            new AiMultiplanarV2RequestMapper(),
            new AiMultiplanarV2ResponseAdapter(objectMapper),
            new AiMultiplanarV1ResponseAdapter(),
            new MultiplanarRealBaselineContractValidator(),
            failingValidator,
            objectMapper,
            metrics);

    assertThrows(
        AiMultiplanarContractViolationException.class,
        () -> client.runMultiplanar(strictSagittalOnlyRequest()));

    Map<String, Object> counters = counters(metrics);
    assertEquals(1L, counters.get("aiContractViolations"));
    assertEquals(1L, counters.get("aiCallsFailed"));
    assertEquals(0L, counters.get("aiCallsSucceeded"));
  }

  @Test
  void aHttp200WithAnInvalidContractNeverCountsAsSucceeded() throws Exception {
    String v2Json =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json"));
    wireMock.stubFor(
        post(urlEqualTo("/v2/multiplanar/run"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(v2Json)));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-200-invalid-contract");

    MultiplanarV2RealBaselineValidator failingValidator =
        mock(MultiplanarV2RealBaselineValidator.class);
    doThrow(new AiMultiplanarContractViolationException("synthetic strict-validator failure"))
        .when(failingValidator)
        .validate(any(), any());
    OperationalMetricsService metrics = new OperationalMetricsService();

    WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
    AiServiceClient client =
        new AiServiceClient(
            webClient,
            new AiServiceProperties(wireMock.baseUrl(), 10, "v2"),
            new AiMultiplanarV2RequestMapper(),
            new AiMultiplanarV2ResponseAdapter(objectMapper),
            new AiMultiplanarV1ResponseAdapter(),
            new MultiplanarRealBaselineContractValidator(),
            failingValidator,
            objectMapper,
            metrics);

    assertThrows(
        AiMultiplanarContractViolationException.class,
        () -> client.runMultiplanar(strictSagittalOnlyRequest()));

    // The HTTP call itself succeeded (200) — only the post-hoc contract validation
    // failed. aiCallsSucceeded must stay at 0.
    assertEquals(0L, counters(metrics).get("aiCallsSucceeded"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> counters(OperationalMetricsService metrics) {
    return (Map<String, Object>) metrics.snapshot().get("counters");
  }

  private MultiplanarRunRequestDto strictSagittalOnlyRequest() {
    return new MultiplanarRunRequestDto(
        "P9A-SPIDER-101-T2",
        "inp_2822bf9640ab40a289050a30ce2fe6fd",
        null,
        null,
        null,
        "sagittal_spider",
        "axial_t2_alkafri",
        false,
        Map.of("inferenceMode", "real_baseline"));
  }
}
