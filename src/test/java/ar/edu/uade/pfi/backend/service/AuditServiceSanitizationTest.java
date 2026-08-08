package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P10-B §11/§12: sanitize() value-based detection (not just key names), depth/list/size caps, and
 * record() never letting a persistence failure mask the caller's operation.
 */
class AuditServiceSanitizationTest {

  @Test
  void redactsSensitiveValueUnderAGenericKeyName() {
    AuditService service = new AuditService(mock(StudyRepository.class));

    Map<String, Object> safe = service.sanitize(Map.of("value", "Bearer synthetic-token.part.two"));

    assertEquals("[redacted]", safe.get("value"));
  }

  @Test
  void redactsEmailUnderAGenericKeyName() {
    AuditService service = new AuditService(mock(StudyRepository.class));

    Map<String, Object> safe =
        service.sanitize(Map.of("detail", "synthetic.doctor@hospital.example"));

    assertEquals("[redacted]", safe.get("detail"));
  }

  @Test
  void redactsJdbcUrlUnderAGenericKeyName() {
    AuditService service = new AuditService(mock(StudyRepository.class));

    Map<String, Object> safe =
        service.sanitize(
            Map.of("info", "jdbc:postgresql://synthetic_user:synthetic_pw@db:5432/pfi"));

    assertEquals("[redacted]", safe.get("info"));
  }

  @Test
  void deeplyNestedMapsAreCappedAtMaxDepth() {
    AuditService service = new AuditService(mock(StudyRepository.class));
    Map<String, Object> level6 = Map.of("leaf", "value");
    Map<String, Object> level5 = Map.of("nested", level6);
    Map<String, Object> level4 = Map.of("nested", level5);
    Map<String, Object> level3 = Map.of("nested", level4);
    Map<String, Object> level2 = Map.of("nested", level3);
    Map<String, Object> level1 = Map.of("nested", level2);

    Map<String, Object> safe = service.sanitize(level1);

    // Just asserting it terminates and doesn't blow the stack / grow unbounded.
    assertTrue(safe.containsKey("nested"));
  }

  @Test
  void listsAreCappedInSize() {
    AuditService service = new AuditService(mock(StudyRepository.class));
    List<String> hugeList =
        java.util.stream.IntStream.range(0, 200).mapToObj(i -> "item-" + i).toList();

    Map<String, Object> safe = service.sanitize(Map.of("items", hugeList));

    @SuppressWarnings("unchecked")
    List<Object> result = (List<Object>) safe.get("items");
    assertTrue(result.size() <= 20);
  }

  @Test
  void totalMetadataEntriesAreCapped() {
    AuditService service = new AuditService(mock(StudyRepository.class));
    Map<String, Object> huge = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 100; i++) huge.put("key" + i, "value" + i);

    Map<String, Object> safe = service.sanitize(huge);

    assertTrue(safe.size() <= 40);
  }

  @Test
  void recordNeverThrowsWhenPersistenceFailsAndReturnsNull() {
    StudyRepository repository = mock(StudyRepository.class);
    when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("simulated db failure"));
    AuditService service = new AuditService(repository);

    var result = service.record("actor-1", "SOME_ACTION", "entity-1", "trace-1", Map.of("k", "v"));

    assertNull(result);
  }

  @Test
  void recordFailureIncrementsAuditWriteFailuresMetric() {
    StudyRepository repository = mock(StudyRepository.class);
    when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("simulated db failure"));
    OperationalMetricsService metrics = new OperationalMetricsService();
    AuditService service = new AuditService(repository, metrics);

    service.record("actor-1", "SOME_ACTION", "entity-1", "trace-1", Map.of());

    assertEquals(1L, (Long) metrics.snapshot().get("auditWriteFailures"));
  }

  @Test
  void emailAttemptedAsEntityIdIsNeverPersisted() {
    StudyRepository repository = mock(StudyRepository.class);
    when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AuditService service = new AuditService(repository);

    var result =
        service.record(
            "actor-1",
            "PROFESSIONAL_ACTIVATED",
            "synthetic.doctor@hospital.example",
            "trace-1",
            Map.of());

    assertEquals("[redacted]", result.entityId());
    assertFalse(result.entityId().contains("@"));
  }

  @Test
  void pathLikeActorIsRedactedRatherThanPersistedVerbatim() {
    StudyRepository repository = mock(StudyRepository.class);
    when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AuditService service = new AuditService(repository);

    var result =
        service.record(
            "C:\\Users\\someone\\secret", "ACCESS_DENIED", "entity-1", "trace-1", Map.of());

    assertEquals("[redacted]", result.actor());
  }

  @Test
  void unsafeActionCharactersAreStrippedNotPersistedVerbatim() {
    StudyRepository repository = mock(StudyRepository.class);
    when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AuditService service = new AuditService(repository);

    var result =
        service.record(
            "actor-1", "weird action; DROP TABLE audit;--", "entity-1", "trace-1", Map.of());

    assertFalse(result.action().contains(";"));
    assertFalse(result.action().contains(" "));
  }

  @Test
  void oversizedTraceIdIsCappedAtNinetySixCharacters() {
    StudyRepository repository = mock(StudyRepository.class);
    when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AuditService service = new AuditService(repository);

    var result = service.record("actor-1", "SOME_ACTION", "entity-1", "a".repeat(200), Map.of());

    assertTrue(result.traceId().length() <= 96);
  }

  @Test
  void recordSucceedsAndPersistsSanitizedMetadataWhenRepositoryWorks() {
    StudyRepository repository = mock(StudyRepository.class);
    when(repository.saveAuditEvent(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AuditService service = new AuditService(repository);

    var result =
        service.record(
            "actor-1", "SOME_ACTION", "entity-1", "trace-1", Map.of("plane", "sagittal"));

    assertEquals("SOME_ACTION", result.action());
    assertFalse(result.metadata().isEmpty());
  }
}
