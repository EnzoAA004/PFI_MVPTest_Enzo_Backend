package ar.edu.uade.pfi.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.service.exceptions.AiContractViolationException;
import ar.edu.uade.pfi.backend.service.exceptions.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.service.exceptions.DatabaseUnavailableException;
import ar.edu.uade.pfi.backend.service.exceptions.StudyNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * P10-B §1/§18-A: every error response — regardless of the triggering exception — carries the full,
 * uniform contract, including category/retryable/governance flags.
 */
class ApiExceptionHandlerContractTest {
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new ErrorController())
          .setControllerAdvice(new ApiExceptionHandler())
          .addFilters(new TraceIdFilter())
          .build();

  @Test
  void unauthorizedHasFullContract() throws Exception {
    assertFullContract(
        "/boom/401", status().isUnauthorized(), "AUTHENTICATION_REQUIRED", "AUTHENTICATION");
  }

  @Test
  void forbiddenHasFullContractIdenticalShapeToUnauthorized() throws Exception {
    assertFullContract("/boom/403", status().isForbidden(), "ACCESS_DENIED", "AUTHORIZATION");
  }

  @Test
  void notFoundHasFullContract() throws Exception {
    assertFullContract(
        "/boom/study-not-found", status().isNotFound(), "STUDY_NOT_FOUND", "RESOURCE");
  }

  @Test
  void databaseUnavailableIsRetryable() throws Exception {
    mockMvc
        .perform(get("/boom/db-unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
        .andExpect(jsonPath("$.category").value("DATABASE"))
        .andExpect(jsonPath("$.retryable").value(true));
  }

  @Test
  void aiContractViolationIsNeverRetryable() throws Exception {
    mockMvc
        .perform(get("/boom/ai-contract"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("AI_CONTRACT_VIOLATION"))
        .andExpect(jsonPath("$.category").value("AI_CONTRACT"))
        .andExpect(jsonPath("$.retryable").value(false));
  }

  @Test
  void aiMultiplanarContractViolationCategory() throws Exception {
    mockMvc
        .perform(get("/boom/ai-multiplanar-contract"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("AI_MULTIPLANAR_CONTRACT_VIOLATION"))
        .andExpect(jsonPath("$.category").value("AI_CONTRACT"));
  }

  @Test
  void internalErrorNeverLeaksTheOriginalMessage() throws Exception {
    mockMvc
        .perform(get("/boom/runtime"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.category").value("INTERNAL"))
        .andExpect(jsonPath("$.message").value("Error interno del backend."))
        .andExpect(
            result -> {
              String body = result.getResponse().getContentAsString();
              if (body.contains("jdbc:postgresql://synthetic_user:synthetic_pw@internal-db")) {
                throw new AssertionError("leaked the raw exception message into the response body");
              }
            });
  }

  private void assertFullContract(
      String path,
      org.springframework.test.web.servlet.ResultMatcher statusMatcher,
      String expectedCode,
      String expectedCategory)
      throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(statusMatcher)
        .andExpect(jsonPath("$.status").value("error"))
        .andExpect(jsonPath("$.code").value(expectedCode))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.traceId").exists())
        .andExpect(jsonPath("$.path").value(path))
        .andExpect(jsonPath("$.method").value("GET"))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.category").value(expectedCategory))
        .andExpect(jsonPath("$.retryable").exists())
        .andExpect(jsonPath("$.humanReviewRequired").value(true))
        .andExpect(jsonPath("$.notClinicalDiagnosis").value(true));
  }

  @RestController
  static class ErrorController {
    @GetMapping("/boom/401")
    void unauthorized() {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Autenticacion requerida.");
    }

    @GetMapping("/boom/403")
    void forbidden() {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta operacion.");
    }

    @GetMapping("/boom/study-not-found")
    void studyNotFound() {
      throw new StudyNotFoundException("CASE-X");
    }

    @GetMapping("/boom/db-unavailable")
    void dbUnavailable() {
      throw new DatabaseUnavailableException("Base de datos no disponible.");
    }

    @GetMapping("/boom/ai-contract")
    void aiContract() {
      throw new AiContractViolationException("contrato invalido");
    }

    @GetMapping("/boom/ai-multiplanar-contract")
    void aiMultiplanarContract() {
      throw new AiMultiplanarContractViolationException("contrato multiplanar invalido");
    }

    @GetMapping("/boom/runtime")
    void runtime() {
      throw new RuntimeException(
          "jdbc:postgresql://synthetic_user:synthetic_pw@internal-db:5432/pfi connection refused");
    }
  }
}
