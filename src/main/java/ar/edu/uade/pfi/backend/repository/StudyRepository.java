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

    Optional<Study> findStudyByCaseId(String caseId);

    List<InputResource> findInputsByStudyId(String studyId);

    Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId);

    Optional<StudyRun> findRunByTraceId(String traceId);

    List<RunArtifact> findArtifactsByRunId(String studyRunId);

    RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections);

    Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId);

    DomainAuditEvent saveAuditEvent(DomainAuditEvent event);

    List<DomainAuditEvent> findAuditEventsByTraceId(String traceId);

    List<DomainAuditEvent> findAuditEventsByEntityId(String entityId);
}
