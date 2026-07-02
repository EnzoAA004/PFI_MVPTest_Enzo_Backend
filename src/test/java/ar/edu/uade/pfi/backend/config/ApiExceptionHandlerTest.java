package ar.edu.uade.pfi.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

class ApiExceptionHandlerTest {

    @Test
    void responseStatusExceptionUsesStandardErrorShapeAndTraceId() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ErrorController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

        mockMvc.perform(get("/boom/not-found").header(TraceIdFilter.TRACE_ID_HEADER, "demo-trace-err"))
            .andExpect(status().isNotFound())
            .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "demo-trace-err"))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Demo no encontrado"))
            .andExpect(jsonPath("$.traceId").value("demo-trace-err"))
            .andExpect(jsonPath("$.path").value("/boom/not-found"))
            .andExpect(jsonPath("$.method").value("GET"))
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true));
    }

    @Test
    void runtimeExceptionIsMaskedAsInternalError() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ErrorController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

        mockMvc.perform(get("/boom/runtime").header(TraceIdFilter.TRACE_ID_HEADER, "demo-trace-500"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("Error interno del backend"))
            .andExpect(jsonPath("$.traceId").value("demo-trace-500"));
    }

    @RestController
    static class ErrorController {
        @GetMapping("/boom/not-found")
        void notFound() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo no encontrado");
        }

        @GetMapping("/boom/runtime")
        void runtime() {
            throw new RuntimeException("sensitive detail");
        }
    }
}
