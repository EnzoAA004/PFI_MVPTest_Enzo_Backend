package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StudyRepository {
  Study saveStudy(Study study);

  InputResource saveInput(InputResource input);

  StudyRun saveRun(StudyRun run);

  List<Study> findAllStudies();

  Optional<Study> findStudyByCaseId(String caseId);

  List<Study> findStudiesBySubjectRef(String subjectRef);

  List<Study> findStudiesByPatientId(String patientId);

  Optional<Study> updatePatientIfExpected(
      String caseId, String targetPatientId, String expectedPatientId);

  List<InputResource> findInputsByStudyId(String studyId);

  List<StudyRun> findRunsByStudyId(String studyId);

  Optional<StudyRun> findLatestRunByStudyId(String studyId);

  Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId);

  Optional<StudyRun> findRunByTraceId(String traceId);

  List<RunArtifact> findArtifactsByRunId(String studyRunId);

  Optional<RunArtifact> findArtifactByRunPlaneAndName(String runId, String plane, String assetName);

  RunArtifact updateArtifactStorage(
      String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256);

  /**
   * Overwrites the persisted metricsSnapshot for a run. Used to downgrade a run's threeD to a
   * blocked state after the workspace mesh asset failed validation/storage post-persistence — never
   * to fabricate new data.
   */
  StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot);

  RunReview saveReview(
      String multiplanarRunId,
      String reviewStatus,
      String reviewer,
      Instant reviewedAt,
      String comments,
      List<MeasurementCorrection> corrections);

  RunReview saveReview(
      String multiplanarRunId,
      String reviewStatus,
      String reviewer,
      Instant reviewedAt,
      String comments,
      List<MeasurementCorrection> corrections,
      DomainAuditEvent auditEvent);

  Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId);

  List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId);

  /**
   * Replaces the reviewer's annotations for a run with the given list.
   *
   * <p>Replace and not append: the client owns the full set it is editing, and an append-only
   * endpoint would make deleting an annotation impossible without a second call that could
   * half-apply.
   */
  List<ReviewerAnnotation> replaceAnnotations(
      String multiplanarRunId, List<ReviewerAnnotation> annotations);

  List<ReviewerAnnotation> findAnnotationsByRunId(String multiplanarRunId);

  DomainAuditEvent saveAuditEvent(DomainAuditEvent event);

  List<DomainAuditEvent> findAuditEventsByTraceId(String traceId);

  List<DomainAuditEvent> findAuditEventsByEntityId(String entityId);

  List<DomainAuditEvent> findAuditEventsByStudyId(String studyId);
}
