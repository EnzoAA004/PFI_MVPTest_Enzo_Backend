package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StudyRunService {
    private static final Pattern SUBJECT_REF_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{3,64}$");

    private final StudyRepository repository;
    private final Clock clock;

    @Autowired
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

    public Study upsertStudyMetadata(String caseId, String status, StudyMetadataDto metadata) {
        String normalizedCaseId = requireCaseId(caseId);
        StudyMetadataDto normalized = normalizeMetadata(metadata);
        Optional<Study> existing = repository.findStudyByCaseId(normalizedCaseId);
        if (existing.isEmpty()) {
            Instant now = clock.instant();
            Study created = repository.saveStudy(new Study(
                UUID.randomUUID().toString(),
                normalizedCaseId,
                status,
                normalized == null ? null : normalized.subjectRef(),
                normalized == null ? null : normalized.studyDate(),
                normalized == null ? null : normalized.modality(),
                normalized == null ? null : normalized.description(),
                normalized == null ? "medium" : valueOrDefault(normalized.reviewPriority(), "medium"),
                now,
                now
            ));
            audit("study.metadata.created", created, normalized, Map.of("metadataPresent", normalized != null));
            if (created.subjectRef() != null) audit("study.subject_ref.assigned", created, normalized, Map.of("subjectRef", created.subjectRef()));
            return created;
        }

        Study current = existing.get();
        if (normalized == null) return current;
        if (normalized.subjectRef() != null && current.subjectRef() != null && !current.subjectRef().equalsIgnoreCase(normalized.subjectRef())) {
            audit("study.subject_ref.conflict", current, normalized, Map.of(
                "caseId", current.caseId(),
                "existingSubjectRef", current.subjectRef(),
                "requestedSubjectRef", normalized.subjectRef()
            ));
            throw new StudyMetadataException(
                HttpStatus.CONFLICT,
                "SUBJECT_REFERENCE_CONFLICT",
                "El estudio ya tiene una referencia de-identificada distinta."
            );
        }

        String subjectRef = firstNonBlank(current.subjectRef(), normalized.subjectRef());
        LocalDate studyDate = normalized.studyDate() == null ? current.studyDate() : normalized.studyDate();
        String modality = firstNonBlank(normalized.modality(), current.modality());
        String description = firstNonBlank(normalized.description(), current.description());
        String reviewPriority = firstNonBlank(normalized.reviewPriority(), current.reviewPriority());
        boolean changed =
            !same(current.subjectRef(), subjectRef)
                || !same(current.studyDate(), studyDate)
                || !same(current.modality(), modality)
                || !same(current.description(), description)
                || !same(current.reviewPriority(), reviewPriority)
                || !same(current.status(), status);
        if (!changed) return current;

        Study updated = repository.saveStudy(new Study(
            current.id(),
            current.caseId(),
            status,
            subjectRef,
            studyDate,
            modality,
            description,
            reviewPriority,
            current.createdAt(),
            clock.instant()
        ));
        audit("study.metadata.updated", updated, normalized, Map.of("metadataPresent", true));
        if (current.subjectRef() == null && updated.subjectRef() != null) {
            audit("study.subject_ref.assigned", updated, normalized, Map.of("subjectRef", updated.subjectRef()));
        }
        return updated;
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

    private StudyMetadataDto normalizeMetadata(StudyMetadataDto metadata) {
        if (metadata == null) return null;
        return new StudyMetadataDto(
            normalizeSubjectRef(metadata.subjectRef()),
            metadata.studyDate(),
            trimToNull(metadata.modality()),
            trimToNull(metadata.description()),
            normalizePriority(metadata.reviewPriority())
        );
    }

    private String normalizeSubjectRef(String subjectRef) {
        String value = trimToNull(subjectRef);
        if (value == null) return null;
        if (!SUBJECT_REF_PATTERN.matcher(value).matches() || value.contains("..")) {
            throw new StudyMetadataException(
                HttpStatus.BAD_REQUEST,
                "INVALID_SUBJECT_REFERENCE",
                "subjectRef debe ser una referencia academica de-identificada de 3 a 64 caracteres, sin espacios, arroba, barras ni traversal."
            );
        }
        return value;
    }

    private String normalizePriority(String priority) {
        String value = trimToNull(priority);
        if (value == null) return null;
        String normalized = value.toLowerCase();
        return switch (normalized) {
            case "low", "medium", "high" -> normalized;
            case "baja" -> "low";
            case "media" -> "medium";
            case "alta" -> "high";
            default -> "medium";
        };
    }

    private void audit(String action, Study study, StudyMetadataDto metadata, Map<String, Object> extra) {
        try {
            Map<String, Object> base = new java.util.LinkedHashMap<>();
            base.put("caseId", study.caseId());
            base.put("subjectRefPresent", study.subjectRef() != null);
            base.put("studyDatePresent", study.studyDate() != null);
            base.put("modalityPresent", study.modality() != null);
            base.put("descriptionPresent", study.description() != null);
            base.put("reviewPriority", study.reviewPriority());
            if (metadata != null && metadata.subjectRef() != null) base.put("subjectRef", metadata.subjectRef());
            base.putAll(extra);
            repository.saveAuditEvent(new DomainAuditEvent(
                UUID.randomUUID().toString(),
                "backend",
                action,
                study.id(),
                "",
                clock.instant(),
                base
            ));
        } catch (RuntimeException auditFailure) {
            // Metadata persistence must not be rolled back because audit storage is unavailable.
        }
    }

    private String requireCaseId(String caseId) {
        String value = trimToNull(caseId);
        if (value == null) throw new IllegalArgumentException("caseId es obligatorio");
        return value;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean same(Object left, Object right) {
        return java.util.Objects.equals(left, right);
    }
}
