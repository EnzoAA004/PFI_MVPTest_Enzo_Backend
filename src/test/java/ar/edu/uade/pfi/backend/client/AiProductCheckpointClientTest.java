package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.dto.DiscDegenerativeProductRequestDto;
import ar.edu.uade.pfi.backend.dto.DiscSegmentationSourceDto;
import ar.edu.uade.pfi.backend.dto.FullSeriesSegmentationRequestDto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AiProductCheckpointClientTest {
    @Test
    void versionedClientUsesExactRoutesAndDoesNotLeakBackendPersistenceIdUpstream() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> segmentationBody = new AtomicReference<>();
        AtomicReference<String> discBody = new AtomicReference<>();
        AtomicReference<String> traceHeader = new AtomicReference<>();
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G'};

        server.createContext("/v2/product-checkpoint/contract", exchange -> json(exchange, 200, "{\"schemaVersion\":\"pfi.p10-9.ai-product-checkpoint.v1\"}"));
        server.createContext("/v2/series-segmentation/run", exchange -> {
            segmentationBody.set(read(exchange));
            traceHeader.set(exchange.getRequestHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER));
            json(exchange, 200, "{\"schemaVersion\":\"pfi.full-series-segmentation.v1\",\"status\":\"completed\"}");
        });
        server.createContext("/v2/series-segmentation/series-run/sagittal/slices/0/original.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.createContext("/v2/degenerative-findings/disc-multitask/from-series-segmentation", exchange -> {
            discBody.set(read(exchange));
            json(exchange, 200, "{\"humanReviewRequired\":true,\"notClinicalDiagnosis\":true,\"autonomousDiagnosis\":false}");
        });
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AiProductCheckpointClient client = new AiProductCheckpointClient(
                WebClient.builder().baseUrl(baseUrl).build(),
                new AiServiceProperties(baseUrl, 30, "v2")
            );

            assertEquals(
                "pfi.p10-9.ai-product-checkpoint.v1",
                client.productCheckpoint().get("schemaVersion")
            );

            client.runFullSeriesSegmentation(new FullSeriesSegmentationRequestDto(
                "case-1", "inp-sag", "sagittal", "sagittal_spider"
            ));
            assertTrue(segmentationBody.get().contains("\"caseId\":\"case-1\""));
            assertTrue(segmentationBody.get().contains("\"inputId\":\"inp-sag\""));
            assertNotNull(traceHeader.get());
            assertFalse(traceHeader.get().isBlank());

            assertArrayEquals(
                png,
                client.getFullSeriesAsset("series-run", "sagittal", 0, "original.png").getBody()
            );

            client.predictDiscDegenerativeFromSegmentation(new DiscDegenerativeProductRequestDto(
                "backend-multi-1",
                "case-1",
                List.of(new DiscSegmentationSourceDto("sagittal_t2", "inp-t2", "series-t2"))
            ));
            assertTrue(discBody.get().contains("\"caseId\":\"case-1\""));
            assertTrue(discBody.get().contains("\"segmentationRunId\":\"series-t2\""));
            assertFalse(discBody.get().contains("backend-multi-1"));
            assertFalse(discBody.get().contains("multiplanarRunId"));
        } finally {
            server.stop(0);
        }
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
