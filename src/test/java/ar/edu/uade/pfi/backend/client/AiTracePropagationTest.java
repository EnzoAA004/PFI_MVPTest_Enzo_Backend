package ar.edu.uade.pfi.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * P10-B §9/§18-C: every AI Module call carries the current request's X-Trace-Id, and
 * never carries the frontend's Authorization header, a JWT, or an email.
 */
class AiTracePropagationTest {
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance().build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearTrace() {
        MDC.clear();
    }

    @Test
    void healthCallCarriesTheCurrentTraceIdHeader() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"status\":\"ok\"}")));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-health-1");

        client().health();

        wireMock.verify(getRequestedFor(urlEqualTo("/health")).withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-health-1")));
    }

    @Test
    void readinessCallCarriesTheCurrentTraceIdHeader() {
        wireMock.stubFor(get(urlEqualTo("/readiness")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"status\":\"ready\"}")));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-readiness-1");

        client().readiness();

        wireMock.verify(getRequestedFor(urlEqualTo("/readiness")).withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-readiness-1")));
    }

    @Test
    void warmupCallCarriesTheCurrentTraceIdHeader() {
        wireMock.stubFor(get(urlEqualTo("/warmup")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"status\":\"ok\"}")));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-warmup-1");

        client().warmup();

        wireMock.verify(getRequestedFor(urlEqualTo("/warmup")).withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-warmup-1")));
    }

    @Test
    void assetRequestCarriesTheCurrentTraceIdHeader() {
        wireMock.stubFor(get(urlPathEqualTo("/assets/run-1/sagittal/overlay.png")).willReturn(aResponse().withStatus(200).withBody(new byte[] {1, 2, 3})));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-asset-1");

        client().getAsset("run-1", "sagittal", "overlay.png");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/assets/run-1/sagittal/overlay.png")).withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-asset-1")));
    }

    @Test
    void uploadInputCarriesTheCurrentTraceIdHeaderAndNeverAnAuthorizationHeader() {
        wireMock.stubFor(post(urlEqualTo("/inputs")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"inputId\":\"input-1\",\"caseId\":\"CASE-1\",\"plane\":\"sagittal\",\"format\":\"npy\",\"size\":3}")));
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-upload-1");
        MockMultipartFile file = new MockMultipartFile("file", "input.npy", "application/octet-stream", new byte[] {1, 2, 3});

        client().uploadInput(file, "CASE-1", "sagittal");

        wireMock.verify(postRequestedFor(urlEqualTo("/inputs")).withHeader(TraceIdFilter.TRACE_ID_HEADER, matching("trace-upload-1")));
        wireMock.verify(exactly(0), postRequestedFor(urlEqualTo("/inputs")).withHeader("Authorization", matching(".*")));
    }

    @Test
    void noHeaderIsSentWhenThereIsNoCurrentTraceId() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"status\":\"ok\"}")));

        client().health();

        wireMock.verify(exactly(0), anyRequestedFor(urlEqualTo("/health")).withHeader(TraceIdFilter.TRACE_ID_HEADER, matching(".*")));
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
            objectMapper
        );
    }
}
