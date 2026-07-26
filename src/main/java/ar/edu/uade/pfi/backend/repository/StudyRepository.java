package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StudyRepository {
    Study saveStudy(Study study);

    InputResource saveInput(InputResource input);

    StudyRun saveRun(StudyRun run);

    List<Study> findAllStudies();

    Optional<Study> findStudyByCaseId(String caseId);

    List<InputResource> findInputsByStudyId(String studyId);

    List<StudyRun> findRunsByStudyId(String studyId);

    Optional<StudyRun> findLatestRunByStudyId(String studyId);

    Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId);

    Optional<StudyRun> findRunByTraceId(String traceId);

    List<RunArtifact> findArtifactsByRunId(String studyRunId);

    Optional<RunArtifact> findArtifactByRunPlaneAndName(String runId, String plane, String assetName);

    RunArtifact updateArtifactStorage(String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256);

    RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections);

    Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId);

    List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId);

    DomainAuditEvent saveAuditEvent(DomainAuditEvent event);

    List<DomainAuditEvent> findAuditEventsByTraceId(String traceId);

    List<DomainAuditEvent> findAuditEventsByEntityId(String entityId);

    List<DomainAuditEvent> findAuditEventsByStudyId(String studyId);
}
