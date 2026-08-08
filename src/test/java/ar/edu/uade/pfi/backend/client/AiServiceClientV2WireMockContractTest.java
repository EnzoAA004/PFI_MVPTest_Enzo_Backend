package ar.edu.uade.pfi.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wire-level contract test: stubs the AI Module's real pfi.multiplanar-run.v2 shape (the sanitized
 * 101_t2.mha evidence, using runId/plane.runId, no JsonAlias anywhere) behind an actual HTTP
 * server, and verifies AiServiceClient deserializes, adapts, and validates it end-to-end without
 * ever falling back to the legacy /multiplanar/run endpoint.
 */
class AiServiceClientV2WireMockContractTest {
  @RegisterExtension static WireMockExtension wireMock = WireMockExtension.newInstance().build();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearTrace() {
    MDC.clear();
  }

  @Test
  void deserializesRealSanitizedWireResponseAdaptsValidatesAndNeverCallsV1Endpoint()
      throws Exception {
    String realWireJson =
        Files.readString(
            Path.of("src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json"));
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v2/multiplanar/run"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(realWireJson)));

    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-wiremock-1");
    WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
    AiServiceClient client =
        new AiServiceClient(
            webClient,
            new AiServiceProperties(wireMock.baseUrl(), 60, "v2"),
            new AiMultiplanarV2RequestMapper(),
            new AiMultiplanarV2ResponseAdapter(objectMapper),
            new AiMultiplanarV1ResponseAdapter(),
            new MultiplanarRealBaselineContractValidator(),
            new MultiplanarV2RealBaselineValidator(),
            objectMapper);

    CanonicalMultiplanarRun canonical =
        client.runMultiplanar(strictSagittalOnlyRequest("trace-wiremock-1"));

    // wire.runId -> canonical.multiplanarRunId
    assertEquals("multi-32d66dabd290b661c709", canonical.multiplanarRunId());
    assertEquals("pfi.multiplanar-run.v2", canonical.schemaVersion());

    CanonicalPlaneRun sagittal = canonical.sagittal();
    assertNotNull(sagittal);
    // wire.plane.runId -> canonical.planeRunId
    assertEquals("bec20aa91f96c9cd", sagittal.planeRunId());

    assertEquals("sagittal_spider", sagittal.model().get("key"));
    assertEquals("sagittal-spider-final-v1", sagittal.model().get("version"));
    assertNotNull(sagittal.model().get("key"));
    assertNotNull(sagittal.model().get("version"));

    Map<String, Object> readiness = canonical.readiness();
    assertEquals(true, readiness.get("sagittal"));
    assertEquals(false, readiness.get("axial"));
    assertEquals(false, readiness.get("dual"));

    assertFalse(canonical.synthetic());
    assertNull(canonical.fallbackReason());
    assertTrue(canonical.governance().humanReviewRequired());
    assertTrue(canonical.governance().notClinicalDiagnosis());

    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/v2/multiplanar/run")));
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo("/multiplanar/run")));
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
}
