package ar.edu.uade.pfi.backend.config.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiErrorWriterTest {
    private final ApiErrorWriter writer = new ApiErrorWriter(new ObjectMapper());

    @Test
    void bodyContainsAllContractFields() {
        Map<String, Object> body = writer.body("NOT_FOUND", "Recurso no encontrado", "trace-1", "/api/studies/x", "GET", 404);

        assertEquals("error", body.get("status"));
        assertEquals("NOT_FOUND", body.get("code"));
        assertEquals("Recurso no encontrado", body.get("message"));
        assertEquals("trace-1", body.get("traceId"));
        assertEquals("/api/studies/x", body.get("path"));
        assertEquals("GET", body.get("method"));
        assertTrue(body.containsKey("timestamp"));
        assertEquals("RESOURCE", body.get("category"));
        assertEquals(false, body.get("retryable"));
        assertEquals(true, body.get("humanReviewRequired"));
        assertEquals(true, body.get("notClinicalDiagnosis"));
    }

    @Test
    void retryableTrueForDatabaseUnavailable() {
        Map<String, Object> body = writer.body("DATABASE_UNAVAILABLE", "Base de datos no disponible", "t", "/api/studies", "GET", 503);
        assertEquals(true, body.get("retryable"));
    }

    @Test
    void retryableTrueForGatewayStatusOnlyWhenCodeIsUnrecognized() {
        Map<String, Object> body = writer.body("SOME_TOTALLY_UNMAPPED_CODE", "x", "t", "/api/whatever", "GET", 503);
        assertEquals(true, body.get("retryable"));
    }

    @Test
    void retryableStaysFalseForAKnownNonTransientCodeEvenOnAGatewayStatus() {
        // AI_CONTRACT_VIOLATION is explicitly classified non-transient and is mapped to
        // 502 by ApiExceptionHandler — it must never be upgraded to retryable=true just
        // because of that HTTP status.
        Map<String, Object> body = writer.body("AI_CONTRACT_VIOLATION", "x", "t", "/api/ai/multiplanar/run", "POST", 502);
        assertEquals(false, body.get("retryable"));
    }

    @Test
    void retryableFalseFor4xxAnd5xxNonGateway() {
        assertEquals(false, writer.body("BAD_REQUEST", "x", "t", "/p", "POST", 400).get("retryable"));
        assertEquals(false, writer.body("ACCESS_DENIED", "x", "t", "/p", "GET", 403).get("retryable"));
        assertEquals(false, writer.body("INTERNAL_ERROR", "x", "t", "/p", "GET", 500).get("retryable"));
        assertEquals(false, writer.body("CONFLICT", "x", "t", "/p", "PATCH", 409).get("retryable"));
    }

    @Test
    void retryableIsForcedFalseForNonIdempotentInferencePostEvenOnGatewayStatus() {
        Map<String, Object> onMultiplanar = writer.body("UPSTREAM_UNAVAILABLE", "x", "t", "/api/ai/multiplanar/run", "POST", 503);
        Map<String, Object> onPipeline = writer.body("AI_TIMEOUT", "x", "t", "/api/ai/pipeline/run", "POST", 504);
        Map<String, Object> onUpload = writer.body("UPSTREAM_UNAVAILABLE", "x", "t", "/api/ai/inputs", "POST", 502);

        assertEquals(false, onMultiplanar.get("retryable"));
        assertEquals(false, onPipeline.get("retryable"));
        assertEquals(false, onUpload.get("retryable"));
    }

    @Test
    void retryableStillTrueForAGetToTheSameTransientCode() {
        Map<String, Object> body = writer.body("AI_TIMEOUT", "x", "t", "/api/ai/health", "GET", 504);
        assertEquals(true, body.get("retryable"));
    }

    @Test
    void unknownCodeFallsBackToInternalCategory() {
        Map<String, Object> body = writer.body("SOMETHING_NOBODY_HEARD_OF", "x", "t", "/p", "GET", 500);
        assertEquals("INTERNAL", body.get("category"));
    }

    @Test
    void writeErrorSetsHeadersAndSerializesBody() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter out = new StringWriter();
        when(request.getRequestURI()).thenReturn("/api/auth/me");
        when(request.getMethod()).thenReturn("GET");
        when(request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE)).thenReturn("trace-write-1");
        when(response.getWriter()).thenReturn(new PrintWriter(out));

        writer.writeError(request, response, 401, "AUTHENTICATION_REQUIRED", "Autenticacion requerida.");

        verify(response).setStatus(401);
        verify(response).setContentType("application/json");
        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).setHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-write-1");
        String json = out.toString();
        assertTrue(json.contains("\"code\":\"AUTHENTICATION_REQUIRED\""));
        assertTrue(json.contains("\"traceId\":\"trace-write-1\""));
        assertFalse(json.contains("Authorization"));
    }

    @Test
    void writeErrorNeverThrowsEvenIfWriterFails() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/x");
        when(request.getMethod()).thenReturn("GET");
        when(response.getWriter()).thenThrow(new java.io.IOException("simulated closed stream"));

        writer.writeError(request, response, 500, "INTERNAL_ERROR", "Error interno del backend");
        // No exception propagated — that's the assertion.
    }
}
