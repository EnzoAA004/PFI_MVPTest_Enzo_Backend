package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StudyRunService {
    private final StudyRepository repository;
    private final Clock clock;

    public StudyRunService(StudyRepository repository) {
        this(repository, Clock.systemUTC());
    }

    StudyRunService(StudyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Study createStudy(String caseId, String status) {
        Instant now = clock.instant();
        return repository.saveStudy(new Study(
            UUID.randomUUID().toString(),
            caseId,
            status,
            now,
            now
        ));
    }

    public InputResource createInput(Study study, String plane, String inputId, String format, long size) {
        return repository.saveInput(new InputResource(
            UUID.randomUUID().toString(),
            study.id(),
            plane,
            inputId,
            format,
            size,
            clock.instant()
        ));
    }

    public StudyRun createRun(
        Study study,
        String multiplanarRunId,
        String traceId,
        String requestedInferenceMode,
        String effectiveInferenceMode,
        String sagittalModelKey,
        String axialModelKey,
        String sagittalRunId,
        String axialRunId,
        Map<String, Object> assets,
        String status
    ) {
        return createRun(
            study,
            multiplanarRunId,
            traceId,
            requestedInferenceMode,
            effectiveInferenceMode,
            sagittalModelKey,
            axialModelKey,
            "",
            "",
            sagittalRunId,
            axialRunId,
            assets,
            Map.of(),
            List.of(),
            status,
            "pending",
            "",
            null,
            ""
        );
    }

    public StudyRun createRun(
        Study study,
        String multiplanarRunId,
        String traceId,
        String requestedInferenceMode,
        String effectiveInferenceMode,
        String sagittalModelKey,
        String axialModelKey,
        String sagittalArtifactHash,
        String axialArtifactHash,
        String sagittalRunId,
        String axialRunId,
        Map<String, Object> assets,
        Map<String, Object> metricsSnapshot,
        List<RunArtifact> artifacts,
        String status,
        String reviewStatus,
        String reviewer,
        Instant reviewedAt,
        String comments
    ) {
        return createRunWithId(
            UUID.randomUUID().toString(),
            study,
            multiplanarRunId,
            traceId,
            requestedInferenceMode,
            effectiveInferenceMode,
            sagittalModelKey,
            axialModelKey,
            sagittalArtifactHash,
            axialArtifactHash,
            sagittalRunId,
            axialRunId,
            assets,
            metricsSnapshot,
            artifacts,
            status,
            reviewStatus,
            reviewer,
            reviewedAt,
            comments
        );
    }

    public StudyRun createRunWithId(
        String id,
        Study study,
        String multiplanarRunId,
        String traceId,
        String requestedInferenceMode,
        String effectiveInferenceMode,
        String sagittalModelKey,
        String axialModelKey,
        String sagittalArtifactHash,
        String axialArtifactHash,
        String sagittalRunId,
        String axialRunId,
        Map<String, Object> assets,
        Map<String, Object> metricsSnapshot,
        List<RunArtifact> artifacts,
        String status,
        String reviewStatus,
        String reviewer,
        Instant reviewedAt,
        String comments
    ) {
        Instant now = clock.instant();
        return repository.saveRun(new StudyRun(
            id,
            study.id(),
            multiplanarRunId,
            traceId,
            requestedInferenceMode,
            effectiveInferenceMode,
            sagittalModelKey,
            axialModelKey,
            sagittalArtifactHash,
            axialArtifactHash,
            sagittalRunId,
            axialRunId,
            assets,
            metricsSnapshot,
            artifacts,
            status,
            reviewStatus,
            reviewer,
            reviewedAt,
            comments,
            now,
            now
        ));
    }

    public Optional<Study> findStudyByCaseId(String caseId) {
        return repository.findStudyByCaseId(caseId);
    }

    public List<InputResource> findInputs(Study study) {
        return repository.findInputsByStudyId(study.id());
    }

    public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) {
        return repository.findRunByMultiplanarRunId(multiplanarRunId);
    }

    public Optional<StudyRun> findRunByTraceId(String traceId) {
        return repository.findRunByTraceId(traceId);
    }
}
