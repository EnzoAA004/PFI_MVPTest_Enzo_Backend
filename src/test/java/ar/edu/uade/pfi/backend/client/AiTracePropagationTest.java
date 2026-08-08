package ar.edu.uade.pfi.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * P10-B §9/§18-C, extended in P10-B.1 §9/§14: every AI Module call carries an X-Trace-Id — the
 * current request's trace id from MDC, or a freshly generated {@code technical-<uuid>} when there
 * is none — and never carries the frontend's Authorization header, a JWT, an email, or roles. For
 * the v2 contract, the body's {@code traceId} field always matches the header, MDC-derived or
 * technical alike.
 */
class AiTracePropagationTest {
  @RegisterExtension static WireMockExtension wireMock = WireMockExtension.newInstance().build();

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearTrace() {
    MDC.clear();
  }

  @Test
  void healthCallCarriesTheCurrentTraceIdHeader() {
    wireMock.stubFor(
        get(urlEqualTo("/health"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ok\"}")));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-health-1");

    client().health();

    wireMock.verify(
        getRequestedFor(urlEqualTo("/health"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-health-1")));
  }

  @Test
  void readinessCallCarriesTheCurrentTraceIdHeader() {
    wireMock.stubFor(
        get(urlEqualTo("/readiness"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ready\"}")));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-readiness-1");

    client().readiness();

    wireMock.verify(
        getRequestedFor(urlEqualTo("/readiness"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-readiness-1")));
  }

  @Test
  void warmupCallCarriesTheCurrentTraceIdHeader() {
    wireMock.stubFor(
        get(urlEqualTo("/warmup"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ok\"}")));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-warmup-1");

    client().warmup();

    wireMock.verify(
        getRequestedFor(urlEqualTo("/warmup"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-warmup-1")));
  }

  @Test
  void assetRequestCarriesTheCurrentTraceIdHeader() {
    wireMock.stubFor(
        get(urlPathEqualTo("/assets/run-1/sagittal/overlay.png"))
            .willReturn(aResponse().withStatus(200).withBody(new byte[] {1, 2, 3})));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-asset-1");

    client().getAsset("run-1", "sagittal", "overlay.png");

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/assets/run-1/sagittal/overlay.png"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-asset-1")));
  }

  @Test
  void uploadInputCarriesTheCurrentTraceIdHeaderAndNeverAnAuthorizationHeader() {
    wireMock.stubFor(
        post(urlEqualTo("/inputs"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"inputId\":\"input-1\",\"caseId\":\"CASE-1\",\"plane\":\"sagittal\",\"format\":\"npy\",\"size\":3}")));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-upload-1");
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "input.npy", "application/octet-stream", new byte[] {1, 2, 3});

    client().uploadInput(file, "CASE-1", "sagittal");

    wireMock.verify(
        postRequestedFor(urlEqualTo("/inputs"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-upload-1")));
    wireMock.verify(
        exactly(0),
        postRequestedFor(urlEqualTo("/inputs")).withHeader("Authorization", matching(".*")));
  }

  @Test
  void uploadStudyCarriesTraceIdAndNeverAnAuthorizationHeader() {
    wireMock.stubFor(
        post(urlEqualTo("/inputs/study"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-study-ai-1")
                    .withBody(
                        "{\"caseId\":\"CASE-1\",\"studyId\":\"study-1\",\"sagittal\":{\"inputId\":\"input-sag\",\"plane\":\"sagittal\",\"format\":\"dcm\",\"size\":3}}")));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-study-1");
    MockMultipartFile file =
        new MockMultipartFile("file", "study.zip", "application/zip", new byte[] {'P', 'K', 3, 4});

    Map<String, Object> response = client().uploadStudy(file, "CASE-1");

    assertEquals("trace-study-ai-1", response.get("traceId"));
    wireMock.verify(
        postRequestedFor(urlEqualTo("/inputs/study"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-study-1")));
    wireMock.verify(
        exactly(0),
        postRequestedFor(urlEqualTo("/inputs/study")).withHeader("Authorization", matching(".*")));
  }

  @Test
  void uploadStudyMapsKnownUpstreamRejectionsWithoutLeakingResponseBody() {
    wireMock.stubFor(
        post(urlEqualTo("/inputs/study"))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"detail\":\"/tmp/private/series token secret\"}")));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                client()
                    .uploadStudy(
                        new MockMultipartFile(
                            "file", "study.zip", "application/zip", new byte[] {'P', 'K', 3, 4}),
                        "CASE-1"));

    assertEquals(422, ex.getStatusCode().value());
    assertEquals("El estudio no contiene planos utilizables.", ex.getReason());
  }

  @Test
  void technicalTraceIdIsSentWhenThereIsNoCurrentTraceId() {
    wireMock.stubFor(
        get(urlEqualTo("/health"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ok\"}")));

    client().health();

    wireMock.verify(
        getRequestedFor(urlEqualTo("/health"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("technical-.*")));
  }

  @Test
  void eachCallWithoutMdcGetsItsOwnFreshTechnicalTraceId() {
    wireMock.stubFor(
        get(urlEqualTo("/health"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ok\"}")));
    AiServiceClient client = client();

    client.health();
    client.health();

    java.util.List<String> traceIds =
        wireMock.findAll(getRequestedFor(urlEqualTo("/health"))).stream()
            .map(request -> request.getHeader(TraceIdFilter.TRACE_ID_HEADER))
            .toList();
    assertTrue(traceIds.size() == 2 && !traceIds.get(0).equals(traceIds.get(1)));
  }

  @Test
  void v2CallWithoutMdcUsesTheSameTechnicalTraceIdInBodyAndHeader() throws Exception {
    String v2Json =
        Files.readString(
            Path.of("src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json"));
    wireMock.stubFor(
        post(urlEqualTo("/v2/multiplanar/run"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(v2Json)));

    CanonicalMultiplanarRun canonical = v2Client().runMultiplanar(strictSagittalOnlyRequest());

    // TraceIdConsistencyGuard would have thrown AiMultiplanarContractViolationException
    // if the v2 request body's traceId and the X-Trace-Id header ever disagreed — a
    // successful, non-throwing call already proves they matched. Confirm the header
    // itself is a technical id (no MDC was set for this test).
    assertTrue(canonical != null);
    wireMock.verify(
        postRequestedFor(urlEqualTo("/v2/multiplanar/run"))
            .withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("technical-.*")));
  }

  @Test
  void aiCallsNeverCarryJwtEmailOrRolesHeaders() {
    wireMock.stubFor(
        get(urlEqualTo("/health"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"ok\"}")));
    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-no-auth-1");

    client().health();

    wireMock.verify(
        exactly(0),
        getRequestedFor(urlEqualTo("/health")).withHeader("Authorization", matching(".*")));
    wireMock.verify(
        exactly(0),
        getRequestedFor(urlEqualTo("/health")).withHeader("X-User-Email", matching(".*")));
    wireMock.verify(
        exactly(0),
        getRequestedFor(urlEqualTo("/health")).withHeader("X-User-Roles", matching(".*")));
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

  private AiServiceClient v2Client() {
    WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
    return new AiServiceClient(
        webClient,
        new AiServiceProperties(wireMock.baseUrl(), 10, "v2"),
        new AiMultiplanarV2RequestMapper(),
        new AiMultiplanarV2ResponseAdapter(objectMapper),
        new AiMultiplanarV1ResponseAdapter(),
        new MultiplanarRealBaselineContractValidator(),
        new MultiplanarV2RealBaselineValidator(),
        objectMapper);
  }

  private AiServiceClient client() {
    WebClient webClient = WebClient.builder().baseUrl(wireMock.baseUrl()).build();
    return new AiServiceClient(
        webClient,
        new AiServiceProperties(wireMock.baseUrl(), 10, "v1"),
        new AiMultiplanarV2RequestMapper(),
        new AiMultiplanarV2ResponseAdapter(objectMapper),
        new AiMultiplanarV1ResponseAdapter(),
        new MultiplanarRealBaselineContractValidator(),
        new MultiplanarV2RealBaselineValidator(),
        objectMapper);
  }
}
