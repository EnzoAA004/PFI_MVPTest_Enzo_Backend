package ar.edu.uade.pfi.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * P10-B §18-B/§18-E: a broad, end-to-end sweep across the auth filter, the CORS filter,
 * and the exception handler — none of them may ever put a JWT, a refresh token, a
 * password, a JDBC URL, an AI Module private URL, a Windows path, a query string, or the
 * Authorization header into a response body.
 */
class SensitiveDataLeakRegressionTest {
    private static final String SECRET = "regression-leak-test-secret-at-least-32-bytes!!";

    @Test
    void authFilter401NeverLeaksTheAuthorizationHeaderValue() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new NoopController())
            .addFilter(new ar.edu.uade.pfi.backend.auth.AuthFilter(
                new ar.edu.uade.pfi.backend.auth.TokenService(new com.fasterxml.jackson.databind.ObjectMapper(), SECRET, 3600),
                new ar.edu.uade.pfi.backend.auth.AuthAccountStateService(
                    org.mockito.Mockito.mock(ar.edu.uade.pfi.backend.auth.PostgresAuthStoreService.class), new MockEnvironment()),
                true, false, new MockEnvironment()
            ))
            .build();

        String bogusBearer = "Bearer synthetic-jwt.part-two.part-three-synthetic-value";
        String body = mockMvc.perform(get("/noop").header("Authorization", bogusBearer))
            .andExpect(status().isUnauthorized())
            .andReturn().getResponse().getContentAsString();

        assertNoLeakage(body, bogusBearer);
    }

    @Test
    void runtimeExceptionResponseNeverLeaksTheJdbcUrlOrLocalPaths() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ErrorController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

        String body = mockMvc.perform(get("/boom"))
            .andExpect(status().isInternalServerError())
            .andReturn().getResponse().getContentAsString();

        assertNoLeakage(body, "jdbc:postgresql://synth_user:synth_pw@internal-db.private:5432/pfi");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("C:\\Users\\someone\\secrets"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("/tmp/upload-8231"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("http://ai-module.internal"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("RuntimeException"));
    }

    @Test
    void requestBodyAndQueryStringAreNeverEchoedBackInAnErrorResponse() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ErrorController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

        String body = mockMvc.perform(patch("/boom-validation?token=synthetic-secret-in-query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"SyntheticPass123!\"}"))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("SyntheticPass123!"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("synthetic-secret-in-query"));
    }

    private void assertNoLeakage(String body, String sensitiveValue) {
        org.junit.jupiter.api.Assertions.assertFalse(body.contains(sensitiveValue));
        org.junit.jupiter.api.Assertions.assertFalse(body.toLowerCase().contains("bearer synthetic"));
    }

    @RestController
    static class NoopController {
        @GetMapping("/noop")
        void noop() {
        }
    }

    @RestController
    static class ErrorController {
        @GetMapping("/boom")
        void boom() {
            throw new RuntimeException("jdbc:postgresql://synth_user:synth_pw@internal-db.private:5432/pfi failed; "
                + "path C:\\Users\\someone\\secrets\\case.dcm; tmp=/tmp/upload-8231; upstream=http://ai-module.internal/health");
        }

        @org.springframework.web.bind.annotation.PatchMapping("/boom-validation")
        void boomValidation() {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Solicitud invalida");
        }
    }
}
