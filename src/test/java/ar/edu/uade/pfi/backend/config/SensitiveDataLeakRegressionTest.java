package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.PostgresReviewStoreService;
import ar.edu.uade.pfi.backend.service.SystemDiagnosticsService;
import ar.edu.uade.pfi.backend.service.exceptions.AiContractViolationException;
import ar.edu.uade.pfi.backend.service.exceptions.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.service.exceptions.AiMultiplanarUpstreamException;
import ar.edu.uade.pfi.backend.service.exceptions.DatabaseUnavailableException;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * P10-B §18-B/§18-E, extended in P10-B.1 §15: an end-to-end sweep across the auth filter, the
 * exception handler, AI-upstream exception types, and ADMIN diagnostics — none of them may ever put
 * a JWT, a refresh token, a password, a JDBC URL, a Cloudflare tunnel host, localhost, an internal
 * path, or an email into a response body, a captured log line, or an audit event. A single
 * synthetic "poison" message combining all of those is reused across every exception type so no one
 * call site can quietly regress.
 */
class SensitiveDataLeakRegressionTest {
  private static final String SECRET = "regression-leak-test-secret-at-least-32-bytes!!";
  private static final String POISON =
      "jdbc:postgresql://synth_user:synth_pw@internal-db.private:5432/pfi "
          + "via synthetic-tunnel.trycloudflare.com and localhost:8000 storing at /tmp/upload-1 and /app/data "
          + "C:\\Users\\someone\\secret password=SyntheticPass123! token=synthetic-tok.en.value "
          + "for synthetic.doctor@hospital.example";
  private static final String[] POISON_FRAGMENTS = {
    "synth_pw",
    "trycloudflare.com",
    "localhost:8000",
    "/tmp/upload-1",
    "/app/data",
    "Users\\someone\\secret",
    "SyntheticPass123!",
    "synthetic-tok.en.value",
    "synthetic.doctor@hospital.example"
  };

  private ListAppender<ILoggingEvent> logCapture;

  @BeforeEach
  void captureLogs() {
    logCapture = new ListAppender<>();
    logCapture.start();
    ((Logger) LoggerFactory.getLogger("ar.edu.uade.pfi.backend")).addAppender(logCapture);
  }

  @AfterEach
  void stopCapture() {
    ((Logger) LoggerFactory.getLogger("ar.edu.uade.pfi.backend")).detachAppender(logCapture);
  }

  @Test
  void authFilter401NeverLeaksTheAuthorizationHeaderValue() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new NoopController())
            .addFilter(
                new ar.edu.uade.pfi.backend.auth.AuthFilter(
                    new ar.edu.uade.pfi.backend.auth.TokenService(new ObjectMapper(), SECRET, 3600),
                    new ar.edu.uade.pfi.backend.auth.AuthAccountStateService(
                        mock(ar.edu.uade.pfi.backend.auth.PostgresAuthStoreService.class),
                        new MockEnvironment()),
                    true,
                    false,
                    new MockEnvironment()))
            .build();

    String bogusBearer = "Bearer synthetic-jwt.part-two.part-three-synthetic-value";
    String body =
        mockMvc
            .perform(get("/noop").header("Authorization", bogusBearer))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoLeakage(body, bogusBearer);
  }

  @Test
  void runtimeExceptionResponseNeverLeaksThePoisonMessage() throws Exception {
    String body =
        errorMockMvc(new RuntimeException(POISON), "/boom", HttpStatus.INTERNAL_SERVER_ERROR)
            .perform(get("/boom"))
            .andExpect(status().isInternalServerError())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoPoisonAnywhere(body, "response body");
    assertFalse(body.contains("RuntimeException"));
  }

  @Test
  void databaseUnavailableExceptionNeverLeaksThePoisonMessage() throws Exception {
    String body =
        errorMockMvc(
                new DatabaseUnavailableException(POISON), "/boom", HttpStatus.SERVICE_UNAVAILABLE)
            .perform(get("/boom"))
            .andExpect(status().isServiceUnavailable())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoPoisonAnywhere(body, "response body");
    assertTrue(body.contains("Base de datos temporalmente no disponible."));
  }

  @Test
  void aiContractViolationExceptionNeverLeaksThePoisonMessage() throws Exception {
    String body =
        errorMockMvc(new AiContractViolationException(POISON), "/boom", HttpStatus.BAD_GATEWAY)
            .perform(get("/boom"))
            .andExpect(status().isBadGateway())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoPoisonAnywhere(body, "response body");
  }

  @Test
  void aiMultiplanarContractViolationExceptionNeverLeaksThePoisonMessage() throws Exception {
    String body =
        errorMockMvc(
                new AiMultiplanarContractViolationException(POISON),
                "/boom",
                HttpStatus.BAD_GATEWAY)
            .perform(get("/boom"))
            .andExpect(status().isBadGateway())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertNoPoisonAnywhere(body, "response body");
  }

  @Test
  void aiMultiplanarUpstreamExceptionNeverLeaksThePoisonMessageAt502Or504() throws Exception {
    String body502 =
        errorMockMvc(
                new AiMultiplanarUpstreamException(
                    HttpStatus.BAD_GATEWAY, "AI_MODULE_ERROR", POISON, "ai-trace-poison"),
                "/boom",
                HttpStatus.BAD_GATEWAY)
            .perform(get("/boom"))
            .andExpect(status().isBadGateway())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertNoPoisonAnywhere(body502, "response body (502)");

    var result504 =
        errorMockMvc(
                new AiMultiplanarUpstreamException(
                    HttpStatus.GATEWAY_TIMEOUT, "AI_MODULE_TIMEOUT", POISON, "ai-trace-poison"),
                "/boom",
                HttpStatus.GATEWAY_TIMEOUT)
            .perform(get("/boom"))
            .andReturn();
    assertTrue(result504.getResponse().getStatus() == 504);
    String body504 = result504.getResponse().getContentAsString();
    assertNoPoisonAnywhere(body504, "response body (504)");
  }

  @Test
  void responseStatusException502And503NeverLeakThePoisonMessage() throws Exception {
    String body502 =
        errorMockMvc(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, POISON),
                "/boom",
                HttpStatus.BAD_GATEWAY)
            .perform(get("/boom"))
            .andExpect(status().isBadGateway())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertNoPoisonAnywhere(body502, "response body (502)");

    String body503 =
        errorMockMvc(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, POISON),
                "/boom",
                HttpStatus.SERVICE_UNAVAILABLE)
            .perform(get("/boom"))
            .andExpect(status().isServiceUnavailable())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertNoPoisonAnywhere(body503, "response body (503)");
  }

  @Test
  void adminDiagnosticsNeverLeakThePoisonMessageFromAFailingAiModule() {
    AiServiceOperations poisonedClient =
        (AiServiceOperations)
            Proxy.newProxyInstance(
                AiServiceOperations.class.getClassLoader(),
                new Class<?>[] {AiServiceOperations.class},
                (proxy, method, args) -> {
                  throw new RuntimeException(POISON);
                });
    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<
            ar.edu.uade.pfi.backend.service.RunAssetContentStorage>
        assetProvider = mock(org.springframework.beans.factory.ObjectProvider.class);
    SystemDiagnosticsService service =
        new SystemDiagnosticsService(
            poisonedClient,
            new PostgresReviewStoreService(new ObjectMapper(), "memory", ""),
            mock(ar.edu.uade.pfi.backend.auth.AuthService.class),
            assetProvider,
            new ar.edu.uade.pfi.backend.config.AiServiceProperties("http://ai-module", 60, "v1"),
            false,
            "memory",
            false,
            null);

    Map<String, Object> diagnostics = service.diagnostics();

    String serialized = diagnostics.toString();
    assertNoPoisonAnywhere(serialized, "diagnostics");
    assertTrue(serialized.contains("AI Module no disponible."));
  }

  @Test
  void auditedErrorEventNeverPersistsThePoisonMessage() throws Exception {
    java.util.concurrent.atomic.AtomicReference<DomainAuditEvent> captured =
        new java.util.concurrent.atomic.AtomicReference<>();
    StudyRepository repository = mock(StudyRepository.class);
    org.mockito.Mockito.when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              DomainAuditEvent event = invocation.getArgument(0);
              captured.set(event);
              return event;
            });
    AuditService auditService = new AuditService(repository);

    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ErrorController(
                    new RuntimeException(POISON), "/boom", HttpStatus.INTERNAL_SERVER_ERROR))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .addFilters(new TraceIdFilter())
            .build();

    mockMvc.perform(get("/boom")).andExpect(status().isInternalServerError());

    DomainAuditEvent event = captured.get();
    assertTrue(event != null);
    assertNoPoisonAnywhere(event.metadata().toString(), "audit metadata");
  }

  @Test
  void capturedLogLinesNeverContainThePoisonMessageAtDefaultLogLevel() throws Exception {
    errorMockMvc(new RuntimeException(POISON), "/boom", HttpStatus.INTERNAL_SERVER_ERROR)
        .perform(get("/boom"))
        .andExpect(status().isInternalServerError());

    for (ILoggingEvent event : logCapture.list) {
      String formatted = event.getFormattedMessage();
      assertNoPoisonAnywhere(formatted, "log line: " + formatted);
    }
  }

  @Test
  void requestBodyAndQueryStringAreNeverEchoedBackInAnErrorResponse() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new ValidationController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

    String body =
        mockMvc
            .perform(
                patch("/boom-validation?token=synthetic-secret-in-query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"password\":\"SyntheticPass123!\"}"))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertFalse(body.contains("SyntheticPass123!"));
    assertFalse(body.contains("synthetic-secret-in-query"));
  }

  private void assertNoLeakage(String body, String sensitiveValue) {
    assertFalse(body.contains(sensitiveValue));
    assertFalse(body.toLowerCase().contains("bearer synthetic"));
  }

  private void assertNoPoisonAnywhere(String haystack, String where) {
    String lower = haystack == null ? "" : haystack.toLowerCase(java.util.Locale.ROOT);
    for (String fragment : POISON_FRAGMENTS) {
      assertFalse(
          lower.contains(fragment.toLowerCase(java.util.Locale.ROOT)),
          "Found leaked fragment '" + fragment + "' in " + where);
    }
  }

  private MockMvc errorMockMvc(RuntimeException toThrow, String path, HttpStatus expectedStatus) {
    return MockMvcBuilders.standaloneSetup(new ErrorController(toThrow, path, expectedStatus))
        .setControllerAdvice(new ApiExceptionHandler())
        .addFilters(new TraceIdFilter())
        .build();
  }

  @RestController
  static class NoopController {
    @GetMapping("/noop")
    void noop() {}
  }

  @RestController
  static class ErrorController {
    private final RuntimeException toThrow;

    ErrorController(RuntimeException toThrow, String path, HttpStatus expectedStatus) {
      this.toThrow = toThrow;
    }

    @GetMapping("/boom")
    void boom() {
      throw toThrow;
    }
  }

  @RestController
  static class ValidationController {
    @org.springframework.web.bind.annotation.PatchMapping("/boom-validation")
    void boomValidation() {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud invalida");
    }
  }
}
