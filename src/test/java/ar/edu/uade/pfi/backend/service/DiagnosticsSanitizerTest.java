package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiagnosticsSanitizerTest {

    @Test
    void dropsKnownSensitiveKeys() {
        Map<String, Object> raw = Map.ofEntries(
            Map.entry("baseUrl", "https://synthetic-ai-module.internal"),
            Map.entry("url", "https://synthetic.example/health"),
            Map.entry("token", "synthetic-token"),
            Map.entry("secret", "synthetic-secret"),
            Map.entry("credential", "synthetic-credential"),
            Map.entry("authorization", "Bearer synthetic"),
            Map.entry("password", "SyntheticPass123!"),
            Map.entry("host", "synthetic-host.internal"),
            Map.entry("port", 8000),
            Map.entry("localPath", "/tmp/synthetic"),
            Map.entry("storagePath", "/app/data"),
            Map.entry("filename", "case.dcm"),
            Map.entry("file", "case.dcm"),
            Map.entry("path", "/api/studies"),
            Map.entry("status", "ok")
        );

        Map<String, Object> safe = DiagnosticsSanitizer.sanitize(raw);

        assertEquals(Map.of("status", "ok"), safe);
    }

    @Test
    void keysThatMerelyContainSensitiveSubstringsAreNotDropped() {
        // "reportCount" contains "port"; must not be treated as sensitive.
        Map<String, Object> raw = Map.of("reportCount", 1, "profile", "sagittal_spider");

        Map<String, Object> safe = DiagnosticsSanitizer.sanitize(raw);

        assertEquals(1, safe.get("reportCount"));
        assertEquals("sagittal_spider", safe.get("profile"));
    }

    @Test
    void preservesAllowedDiagnosticFields() {
        Map<String, Object> raw = Map.ofEntries(
            Map.entry("status", "ok"),
            Map.entry("schemaVersion", "pfi.multiplanar-run.v2"),
            Map.entry("multiplanarContractVersion", "v2"),
            Map.entry("multiplanarEndpoint", "/v2/multiplanar/run"),
            Map.entry("modelKey", "sagittal_spider"),
            Map.entry("modelVersion", "sagittal-spider-final-v1"),
            Map.entry("artifactHash", "sha256:abcdef"),
            Map.entry("baselineReady", true),
            Map.entry("availableForRealInference", true),
            Map.entry("humanReviewRequired", true),
            Map.entry("notClinicalDiagnosis", true)
        );

        Map<String, Object> safe = DiagnosticsSanitizer.sanitize(raw);

        assertEquals(raw, safe);
    }

    @Test
    void redactsSensitiveValuesEvenUnderAnAllowedKeyName() {
        Map<String, Object> raw = Map.of("message", "failed calling http://synthetic-ai.trycloudflare.com/health");

        Map<String, Object> safe = DiagnosticsSanitizer.sanitize(raw);

        assertFalse(String.valueOf(safe.get("message")).contains("trycloudflare"));
    }

    @Test
    void recursesIntoNestedMapsAndLists() {
        Map<String, Object> raw = Map.of(
            "contract", Map.of("status", "stable", "baseUrl", "https://synthetic.internal"),
            "items", List.of(Map.of("host", "synthetic-host", "modelKey", "sagittal_spider"))
        );

        Map<String, Object> safe = DiagnosticsSanitizer.sanitize(raw);

        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) safe.get("contract");
        assertFalse(contract.containsKey("baseUrl"));
        assertEquals("stable", contract.get("status"));

        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) safe.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) items.get(0);
        assertFalse(item.containsKey("host"));
        assertEquals("sagittal_spider", item.get("modelKey"));
    }

    @Test
    void nullAndEmptyAreHandledSafely() {
        assertEquals(Map.of(), DiagnosticsSanitizer.sanitize(null));
        assertEquals(Map.of(), DiagnosticsSanitizer.sanitize(Map.of()));
    }

    @Test
    void numbersAndBooleansPassThroughUnchanged() {
        Map<String, Object> raw = Map.of("artifactsAvailable", 3, "readyForRealInference", false);

        Map<String, Object> safe = DiagnosticsSanitizer.sanitize(raw);

        assertEquals(3, safe.get("artifactsAvailable"));
        assertEquals(false, safe.get("readyForRealInference"));
        assertTrue(safe.get("artifactsAvailable") instanceof Integer);
    }
}
