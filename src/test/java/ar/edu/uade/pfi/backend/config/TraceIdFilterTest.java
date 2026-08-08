package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class TraceIdFilterTest {

  @Test
  void keepsIncomingTraceIdAndReturnsItAsHeader() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();

    mockMvc
        .perform(get("/test-trace").header(TraceIdFilter.TRACE_ID_HEADER, "demo-trace-123"))
        .andExpect(status().isOk())
        .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "demo-trace-123"));
  }

  @Test
  void generatesTraceIdWhenMissing() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();

    mockMvc
        .perform(get("/test-trace"))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              String traceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
              assertTrue(traceId != null && traceId.startsWith("trace-"));
            });
  }

  @Test
  void invalidCharactersAreSanitizedNotRejected() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();

    mockMvc
        .perform(
            get("/test-trace").header(TraceIdFilter.TRACE_ID_HEADER, "trace with spaces/and*junk"))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              String traceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
              assertTrue(traceId.matches("[a-zA-Z0-9._:-]+"));
            });
  }

  @Test
  void incomingTraceIdLongerThan96CharactersIsTruncated() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();
    String tooLong = "a".repeat(200);

    mockMvc
        .perform(get("/test-trace").header(TraceIdFilter.TRACE_ID_HEADER, tooLong))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              String traceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
              assertEquals(96, traceId.length());
            });
  }

  @Test
  void onlyWhitespaceOrPunctuationTraceIdFallsBackToGenerated() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();

    mockMvc
        .perform(get("/test-trace").header(TraceIdFilter.TRACE_ID_HEADER, "   "))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              String traceId = result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
              assertTrue(traceId.startsWith("trace-"));
            });
  }

  @Test
  void mdcIsClearedEvenWhenTheControllerThrows() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new TestController())
            .addFilters(new TraceIdFilter())
            .build();

    try {
      mockMvc.perform(
          get("/test-trace-throw").header(TraceIdFilter.TRACE_ID_HEADER, "trace-will-throw"));
    } catch (Exception expected) {
      // The controller deliberately throws; only the MDC-cleanup guarantee is under test here.
    }

    assertNull(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
  }

  @Test
  void concurrentRequestsDoNotContaminateEachOthersMdc() throws Exception {
    TraceIdFilter filter = new TraceIdFilter();
    int threadCount = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch go = new CountDownLatch(1);
    List<String> observed = new CopyOnWriteArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      String expected = "trace-thread-" + i;
      pool.submit(
          () -> {
            try {
              ready.countDown();
              go.await();
              var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/x");
              request.addHeader(TraceIdFilter.TRACE_ID_HEADER, expected);
              var response = new org.springframework.mock.web.MockHttpServletResponse();
              filter.doFilter(
                  request,
                  response,
                  (req, res) -> {
                    observed.add(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
                  });
            } catch (Exception ignored) {
              // best-effort concurrency probe
            }
          });
    }
    ready.await(5, TimeUnit.SECONDS);
    go.countDown();
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    assertEquals(threadCount, observed.size());
    for (int i = 0; i < threadCount; i++) {
      assertTrue(observed.contains("trace-thread-" + i));
    }
    assertNull(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
  }

  @RestController
  static class TestController {
    @GetMapping("/test-trace")
    ResponseEntity<Map<String, Object>> testTrace() {
      return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/test-trace-throw")
    ResponseEntity<Map<String, Object>> testTraceThrow() {
      throw new RuntimeException("boom");
    }
  }
}
