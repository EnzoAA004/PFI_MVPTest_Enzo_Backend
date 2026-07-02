package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class TraceIdFilterTest {

    @Test
    void keepsIncomingTraceIdAndReturnsItAsHeader() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();

        mockMvc.perform(get("/test-trace").header(TraceIdFilter.TRACE_ID_HEADER, "demo-trace-123"))
            .andExpect(status().isOk())
            .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "demo-trace-123"));
    }

    @Test
    void generatesTraceIdWhenMissing() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();

        mockMvc.perform(get("/test-trace"))
            .andExpect(status().isOk())
            .andExpect(result -> {
                String traceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
                assertTrue(traceId != null && traceId.startsWith("trace-"));
            });
    }

    @RestController
    static class TestController {
        @GetMapping("/test-trace")
        ResponseEntity<Map<String, Object>> testTrace() {
            return ResponseEntity.ok(Map.of("status", "ok"));
        }
    }
}
