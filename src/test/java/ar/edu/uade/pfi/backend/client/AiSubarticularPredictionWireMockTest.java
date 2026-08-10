package ar.edu.uade.pfi.backend.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarUpstreamException;
import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.dto.AiSubarticularPredictRequestDto;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingSeverityV1;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingSideV1;
import ar.edu.uade.pfi.backend.dto.SubarticularPredictionResponseDto;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarV2RealBaselineValidator;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Contrato de cable del puente subarticular contra un servidor HTTP real.
 *
 * <p>Lo que se verifica no es que el cliente "ande", sino las tres cosas que pueden salir mal en
 * silencio: que el cuerpo que sale sea exactamente el que el modulo de IA acepta ({@code
 * extra="forbid"} rechaza cualquier campo de mas), que el trace id viaje, y que un error
 * estructurado aguas arriba se traduzca a un codigo estable del backend sin filtrar el cuerpo
 * crudo.
 */
class AiSubarticularPredictionWireMockTest {
  @RegisterExtension static WireMockExtension wireMock = WireMockExtension.newInstance().build();

  private static final String PREDICT_PATH = "/degenerative-findings/subarticular/predict";

  private final ObjectMapper objectMapper = new ObjectMapper();

  @AfterEach
  void clearTrace() {
    MDC.clear();
  }

  private AiServiceClient client() {
    return new AiServiceClient(
        WebClient.builder().baseUrl(wireMock.baseUrl()).build(),
        new AiServiceProperties(wireMock.baseUrl(), 60, "v2"),
        new AiMultiplanarV2RequestMapper(),
        new AiMultiplanarV2ResponseAdapter(objectMapper),
        new AiMultiplanarV1ResponseAdapter(),
        new MultiplanarRealBaselineContractValidator(),
        new MultiplanarV2RealBaselineValidator(),
        objectMapper);
  }

  private AiSubarticularPredictRequestDto request() {
    return new AiSubarticularPredictRequestDto(
        "input-axial-1", 12, 128.5d, 96.25d, "right", "L4-L5");
  }

  @Test
  void enviaExactamenteLosSeisCamposDelContratoYPropagaElTraceId() {
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PREDICT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(okBody())));

    MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-sub-1");
    client().predictSubarticular(request());

    // equalToJson sin ignoreExtraElements: un campo de mas en el cuerpo hace fallar el
    // test acá, que es donde queremos enterarnos, y no con un 422 del modulo de IA.
    wireMock.verify(
        postRequestedFor(urlEqualTo(PREDICT_PATH))
            .withHeader(
                TraceIdFilter.TRACE_ID_HEADER,
                com.github.tomakehurst.wiremock.client.WireMock.equalTo("trace-sub-1"))
            .withRequestBody(
                equalToJson(
                    """
                {
                  "inputId": "input-axial-1",
                  "instanceNumber": 12,
                  "x": 128.5,
                  "y": 96.25,
                  "side": "right",
                  "level": "L4-L5"
                }
                """)));
  }

  @Test
  void deserializaLaRespuestaCompletaDelClasificador() {
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PREDICT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(okBody())));

    SubarticularPredictionResponseDto response = client().predictSubarticular(request());

    assertEquals("pfi.degenerative-findings.v1", response.degenerativeFindings().schemaVersion());
    assertEquals(1, response.degenerativeFindings().findings().size());
    var finding = response.degenerativeFindings().findings().get(0);
    assertEquals("L4-L5", finding.anatomy().level());
    assertEquals(DegenerativeFindingSideV1.RIGHT, finding.anatomy().side());
    assertEquals(DegenerativeFindingSeverityV1.MODERATE, finding.classification().label());
    assertEquals(0.62d, finding.classification().probabilities().moderate(), 1e-9);
    assertEquals("subarticular-frozen-v1", response.model().modelId());
    assertEquals("cpu", response.model().device());
    assertTrue(response.humanReviewRequired());
    assertTrue(response.notClinicalDiagnosis());
  }

  /**
   * El sobre lo enriquece el modulo de IA con metadata operativa. Que una clave nueva no tumbe la
   * respuesta es una decision explicita, no un descuido: ver el javadoc de
   * SubarticularPredictionResponseDto.
   */
  @Test
  void unCampoNuevoEnElSobreNoRompeLaDeserializacion() {
    String withExtra =
        okBody()
            .replace(
                "\"warnings\": []", "\"warnings\": [], \"inferenceMillis\": 41, \"queueDepth\": 0");
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PREDICT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(withExtra)));

    SubarticularPredictionResponseDto response = client().predictSubarticular(request());

    assertNotNull(response.degenerativeFindings());
  }

  @Test
  void checkpointNoConfiguradoVuelveComo503ConCodigoEstable() {
    stubError(503, "SUBARTICULAR_CHECKPOINT_UNAVAILABLE");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class, () -> client().predictSubarticular(request()));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
    assertEquals("AI_SUBARTICULAR_UNAVAILABLE", ex.code());
  }

  @Test
  void coordenadaInvalidaVuelveComo400() {
    stubError(400, "SUBARTICULAR_INVALID_INPUT");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class, () -> client().predictSubarticular(request()));

    assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    assertEquals("AI_SUBARTICULAR_INVALID_INPUT", ex.code());
  }

  @Test
  void checkpointConHashDistintoVuelveComoViolacionDeContrato() {
    stubError(500, "SUBARTICULAR_CHECKPOINT_HASH_MISMATCH");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class, () -> client().predictSubarticular(request()));

    assertEquals(HttpStatus.BAD_GATEWAY, ex.status());
    assertEquals("AI_SUBARTICULAR_CHECKPOINT_INVALID", ex.code());
  }

  /**
   * El inputId puede no estar registrado, y el modulo de IA rechaza eso antes de tocar el
   * clasificador, con el codigo generico NOT_FOUND. Verificado contra el servicio real: sin esta
   * entrada en la tabla llegaba al visor como un 502 del que no se puede deducir que el problema es
   * el input y no el modelo.
   */
  @Test
  void inputNoRegistradoVuelveComo404YNoComo502() {
    stubError(404, "NOT_FOUND");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class, () -> client().predictSubarticular(request()));

    assertEquals(HttpStatus.NOT_FOUND, ex.status());
    assertEquals("AI_INPUT_NOT_FOUND", ex.code());
  }

  /** La serie existe pero no es axial: el modulo de IA lo rechaza con 409 CONFLICT. */
  @Test
  void serieNoAxialVuelveComo409() {
    stubError(409, "CONFLICT");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class, () -> client().predictSubarticular(request()));

    assertEquals(HttpStatus.CONFLICT, ex.status());
    assertEquals("AI_SUBARTICULAR_INVALID_INPUT", ex.code());
  }

  /** Un codigo que el modulo de IA agregue manana no se filtra: colapsa al bucket generico. */
  @Test
  void codigoDesconocidoColapsaAlBucketGenerico() {
    stubError(500, "SUBARTICULAR_ALGO_QUE_NO_EXISTE_TODAVIA");

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class, () -> client().predictSubarticular(request()));

    assertEquals(HttpStatus.BAD_GATEWAY, ex.status());
    assertEquals("AI_MODULE_ERROR", ex.code());
  }

  /** El mensaje publico sale del catalogo, nunca del cuerpo que mando el modulo de IA. */
  @Test
  void elMensajeDelUpstreamNuncaLlegaAlMensajePublico() {
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PREDICT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                {
                  "status": "error",
                  "code": "SUBARTICULAR_RUNTIME_ERROR",
                  "message": "Traceback: /app/models/frozen_subarticular_checkpoint.pt no se pudo abrir",
                  "traceId": "ai-trace-9"
                }
                """)));

    AiMultiplanarUpstreamException ex =
        assertThrows(
            AiMultiplanarUpstreamException.class, () -> client().predictSubarticular(request()));

    assertEquals("AI_SUBARTICULAR_RUNTIME_ERROR", ex.code());
    assertEquals("El clasificador de hallazgos degenerativos fallo.", ex.getMessage());
    assertTrue(!ex.getMessage().contains("Traceback"));
    assertTrue(!ex.getMessage().contains(".pt"));
    // El trace id del modulo de IA si se conserva: es lo que permite cruzar los logs.
    assertEquals("ai-trace-9", ex.aiTraceId());
  }

  private void stubError(int status, String code) {
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PREDICT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                {
                  "status": "error",
                  "code": "%s",
                  "message": "mensaje del modulo de IA",
                  "traceId": "ai-trace-1",
                  "humanReviewRequired": true,
                  "notClinicalDiagnosis": true
                }
                """
                            .formatted(code))));
  }

  /**
   * Forma real de la respuesta del endpoint, tal como la arma api.py. Vive como fixture y no
   * embebida acá para que el test del controller lea el mismo cuerpo: dos copias del contrato es
   * como se llega a que una pase y la otra no.
   */
  static String okBody() {
    try {
      return java.nio.file.Files.readString(
          java.nio.file.Path.of(
              "src/test/resources/contracts/ai-module-subarticular-predict.json"));
    } catch (java.io.IOException ex) {
      throw new IllegalStateException("no se pudo leer el fixture del contrato subarticular", ex);
    }
  }
}
