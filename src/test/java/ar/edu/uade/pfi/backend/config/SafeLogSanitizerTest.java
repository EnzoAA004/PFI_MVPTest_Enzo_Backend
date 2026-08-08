package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** All examples here are synthetic — never a real secret. */
class SafeLogSanitizerTest {

  @Test
  void redactsBearerToken() {
    String value = "Bearer synthetic-token-value-abc123";
    assertTrue(SafeLogSanitizer.isSensitive(value));
    assertEquals(SafeLogSanitizer.REDACTED, SafeLogSanitizer.redactValue(value));
  }

  @Test
  void redactsJwtLookingString() {
    String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2lnbmF0dXJlLXBhcnQ";
    assertTrue(SafeLogSanitizer.isSensitive(jwt));
    assertEquals(SafeLogSanitizer.REDACTED, SafeLogSanitizer.redactValue(jwt));
  }

  @Test
  void redactsJdbcUrlWithCredentials() {
    String jdbc = "jdbc:postgresql://synthetic_user:synthetic_pw@db.example.internal:5432/pfi";
    assertTrue(SafeLogSanitizer.isSensitive(jdbc));
    assertEquals(SafeLogSanitizer.REDACTED, SafeLogSanitizer.redactValue(jdbc));
  }

  @Test
  void redactsUrlWithUserinfo() {
    String url = "https://synthetic_user:synthetic_pw@private.ai-module.internal/health";
    assertTrue(SafeLogSanitizer.isSensitive(url));
  }

  @Test
  void redactsWindowsPath() {
    String path = "C:\\Users\\someone\\secret\\case.dcm";
    assertTrue(SafeLogSanitizer.isSensitive(path));
    assertEquals(SafeLogSanitizer.REDACTED, SafeLogSanitizer.redactValue(path));
  }

  @Test
  void redactsTmpAndAppPaths() {
    assertTrue(SafeLogSanitizer.isSensitive("/tmp/upload-8231.npy"));
    assertTrue(SafeLogSanitizer.isSensitive("/app/data/case.dcm"));
  }

  @Test
  void redactsKeyValueSecretPairs() {
    assertTrue(SafeLogSanitizer.isSensitive("password=SyntheticPass123!"));
    assertTrue(SafeLogSanitizer.isSensitive("secret: synthetic-secret-value"));
  }

  @Test
  void redactsEmail() {
    assertTrue(SafeLogSanitizer.isSensitive("doctor.synthetic@hospital.example"));
  }

  @Test
  void redactsMedicalFilename() {
    assertTrue(SafeLogSanitizer.isSensitive("study_synthetic_patient.dcm"));
    assertTrue(SafeLogSanitizer.isSensitive("overlay_result.png"));
  }

  @Test
  void plainSafeValuePassesThroughUnchanged() {
    String safe = "sagittal";
    assertFalse(SafeLogSanitizer.isSensitive(safe));
    assertEquals(safe, SafeLogSanitizer.redactValue(safe));
  }

  @Test
  void sanitizeMessageRedactsSubstringsButKeepsTheRestOfTheMessage() {
    String message =
        "Connection failed for jdbc:postgresql://synth_user:synth_pw@db.internal:5432/pfi after retry";
    String sanitized = SafeLogSanitizer.sanitizeMessage(message);
    assertFalse(sanitized.contains("synth_pw"));
    assertTrue(sanitized.contains("Connection failed"));
    assertTrue(sanitized.contains("[redacted]"));
  }

  @Test
  void sanitizeMessageRedactsBearerTokenSubstring() {
    String message = "Rejected request with header Authorization: Bearer synthetic-abc.def.ghi";
    String sanitized = SafeLogSanitizer.sanitizeMessage(message);
    assertFalse(sanitized.contains("synthetic-abc"));
  }

  @Test
  void sanitizeMessageStripsQueryStrings() {
    String message = "GET /api/studies?caseId=CASE-1&token=synthetic failed";
    String sanitized = SafeLogSanitizer.sanitizeMessage(message);
    assertFalse(sanitized.contains("caseId=CASE-1"));
    assertFalse(sanitized.contains("token=synthetic"));
  }

  @Test
  void resultIsCappedToAMaximumLength() {
    String longValue = "a".repeat(500);
    String sanitized = SafeLogSanitizer.sanitizeMessage(longValue);
    assertTrue(sanitized.length() <= 210);
  }

  @Test
  void nullAndBlankAreHandledSafely() {
    assertEquals(null, SafeLogSanitizer.redactValue(null));
    assertFalse(SafeLogSanitizer.isSensitive(null));
    assertFalse(SafeLogSanitizer.isSensitive(""));
  }

  // ---- P10-B.1 additions ----

  @Test
  void redactsAnyHttpUrl() {
    assertTrue(SafeLogSanitizer.isSensitive("http://synthetic-ai-module.example:8000/health"));
    assertTrue(SafeLogSanitizer.isSensitive("https://synthetic-ai-module.example/models"));
  }

  @Test
  void redactsLocalhostVariants() {
    assertTrue(SafeLogSanitizer.isSensitive("localhost:8000"));
    assertTrue(SafeLogSanitizer.isSensitive("connection refused to 127.0.0.1:5432"));
    assertTrue(SafeLogSanitizer.isSensitive("bound to 0.0.0.0:8080"));
  }

  @Test
  void redactsDockerAndCloudflareHosts() {
    assertTrue(SafeLogSanitizer.isSensitive("host.docker.internal:8000"));
    assertTrue(SafeLogSanitizer.isSensitive("synthetic-tunnel-name.trycloudflare.com"));
  }

  @Test
  void redactsInternalHosts() {
    assertTrue(SafeLogSanitizer.isSensitive("ai-module.internal"));
    assertTrue(SafeLogSanitizer.isSensitive("db-primary.internal:5432"));
  }

  @Test
  void redactsPrivateIpv4Addresses() {
    assertTrue(SafeLogSanitizer.isSensitive("10.0.4.12"));
    assertTrue(SafeLogSanitizer.isSensitive("172.20.0.5:5432"));
    assertTrue(SafeLogSanitizer.isSensitive("192.168.1.50"));
  }

  @Test
  void redactsFullQueryStringsInSanitizeMessage() {
    String message = "request failed for /api/studies?caseId=CASE-1&reviewer=synthetic-reviewer";
    String sanitized = SafeLogSanitizer.sanitizeMessage(message);
    assertFalse(sanitized.contains("caseId=CASE-1"));
    assertFalse(sanitized.contains("reviewer=synthetic-reviewer"));
  }

  @Test
  void redactsGenericLinuxInternalPaths() {
    assertTrue(SafeLogSanitizer.isSensitive("/var/lib/synthetic-app/data.bin"));
    assertTrue(SafeLogSanitizer.isSensitive("/opt/synthetic-app/config.yml"));
    assertTrue(SafeLogSanitizer.isSensitive("/home/synthetic-user/case.dcm"));
  }

  @Test
  void redactsJdbcUrlEvenWithoutExplicitCredentials() {
    String jdbc = "jdbc:postgresql://synthetic-db-host.internal:5432/pfi_db";
    assertTrue(SafeLogSanitizer.isSensitive(jdbc));
    assertEquals(SafeLogSanitizer.REDACTED, SafeLogSanitizer.redactValue(jdbc));
  }

  @Test
  void sanitizeMessageRedactsPrivateHostMentionsButKeepsSurroundingText() {
    String message =
        "Upstream call to https://synthetic-ai.trycloudflare.com/v2/multiplanar/run failed after retry";
    String sanitized = SafeLogSanitizer.sanitizeMessage(message);
    assertFalse(sanitized.contains("synthetic-ai.trycloudflare.com"));
    assertTrue(sanitized.contains("Upstream call"));
    assertTrue(sanitized.contains("failed after retry"));
  }
}
