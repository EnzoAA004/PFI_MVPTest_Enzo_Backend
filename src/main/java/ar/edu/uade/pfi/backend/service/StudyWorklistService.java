package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.StudyDetailResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyListResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyRowDto;
import ar.edu.uade.pfi.backend.dto.StudyRunDetailDto;
import ar.edu.uade.pfi.backend.dto.StudyRunSummaryDto;
import ar.edu.uade.pfi.backend.dto.StudyRunsResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class StudyWorklistService {
    private static final String DATA_ORIGIN_DATABASE = "database";

    private final StudyRepository repository;
    private final boolean demoEnabled;
    private final PublicThreeDAssetPublisher threeDAssetPublisher;
    private final VolumeSliceCatalogService volumeSliceCatalogService = new VolumeSliceCatalogService();

    public StudyWorklistService(StudyRepository repository, boolean demoEnabled) {
        this(repository, demoEnabled, new PublicThreeDAssetPublisher());
    }

    @Autowired
    public StudyWorklistService(
        StudyRepository repository,
        @Value("${pfi.demo.enabled:false}") boolean demoEnabled,
        PublicThreeDAssetPublisher threeDAssetPublisher
    ) {
        this.repository = repository;
        this.demoEnabled = demoEnabled;
        this.threeDAssetPublisher = threeDAssetPublisher;
    }

    public StudyListResponseDto listStudies() {
        return databaseCall(() -> {
            List<Study> studies = repository.findAllStudies();
            List<StudyRowDto> rows = new ArrayList<>();
            for (Study study : studies) {
                List<InputResource> inputs = repository.findInputsByStudyId(study.id());
                Optional<StudyRun> latestRun = repository.findLatestRunByStudyId(study.id());
                rows.add(toRow(study, inputs, latestRun.orElse(null)));
            }
            return new StudyListResponseDto(
                "ok",
                "postgres-domain",
                DATA_ORIGIN_DATABASE,
                rows,
                summary(rows),
                true,
                true
            );
        });
    }

    public StudyDetailResponseDto getStudy(String caseId) {
        return databaseCall(() -> {
            Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
            List<InputResource> inputs = repository.findInputsByStudyId(study.id());
            List<StudyRun> runs = repository.findRunsByStudyId(study.id());
            List<StudyRunDetailDto> runDetails = runs.stream().map(run -> toRunDetail(study, run)).toList();
            List<DomainAuditEvent> auditTrail = repository.findAuditEventsByStudyId(study.id());
            return new StudyDetailResponseDto(
                "ok",
                toRow(study, inputs, runs.isEmpty() ? null : runs.get(0)),
                inputs,
                runDetails,
                auditTrail,
                true,
                true
            );
        });
    }

    public StudyRunsResponseDto getStudyRuns(String caseId) {
        return databaseCall(() -> {
            Study study = repository.findStudyByCaseId(caseId).orElseThrow(() -> new StudyNotFoundException(caseId));
            List<StudyRunDetailDto> runs = repository.findRunsByStudyId(study.id()).stream()
                .map(run -> toRunDetail(study, run))
                .toList();
            return new StudyRunsResponseDto("ok", study.caseId(), runs, true, true);
        });
    }

    public StudyDetailResponseDto createDemoStudy() {
        requireDemoEnabled();
        Instant now = Instant.now();
        Study study = new Study(
            UUID.randomUUID().toString(),
            "CASE-DEMO-0142",
            "ready",
            "PAT-0087",
            null,
            "MRI",
            "Lumbar Spine",
            "medium",
            now,
            now
        );
        StudyRowDto row = new StudyRowDto(
            study.caseId(),
            study.subjectRef(),
            null,
            study.status(),
            List.of("sagittal"),
            "sagittal",
            null,
            null,
            "demo-only",
            "pendiente",
            ReviewStatusMapper.toApiPriority(study.reviewPriority()),
            now.toString(),
            now.toString(),
            "demo"
        );
        return new StudyDetailResponseDto("ok", row, List.of(), List.of(), List.of(), true, true);
    }

    public void requireDemoEnabled() {
        if (!demoEnabled) {
            throw new StudyNotFoundException("demo");
        }
    }

    private StudyRowDto toRow(Study study, List<InputResource> inputs, StudyRun latestRun) {
        List<String> planes = planesFor(inputs, latestRun);
        String primaryPlane = planes.contains("sagittal") ? "sagittal" : planes.stream().findFirst().orElse(null);
        String modelKey = latestRun == null
            ? null
            : firstNonBlank(latestRun.sagittalModelKey(), latestRun.axialModelKey());
        return new StudyRowDto(
            study.caseId(),
            study.subjectRef(),
            study.studyDate() == null ? null : study.studyDate().toString(),
            study.status(),
            planes,
            primaryPlane,
            latestRun == null ? null : latestRun.multiplanarRunId(),
            modelKey,
            latestRun == null ? "sin_corrida" : latestRun.status(),
            latestRun == null ? null : ReviewStatusMapper.toApiStatus(latestRun.reviewStatus()),
            ReviewStatusMapper.toApiPriority(study.reviewPriority()),
            study.createdAt().toString(),
            study.updatedAt().toString(),
            DATA_ORIGIN_DATABASE
        );
    }

    private StudyRunDetailDto toRunDetail(Study study, StudyRun run) {
        Map<String, List<Map<String, Object>>> measurementsByPlane = measurementsByPlane(run.metricsSnapshot());
        Map<String, List<Map<String, Object>>> artifactsByPlane = artifactsByPlane(run.artifacts());
        List<MeasurementCorrection> corrections = repository.findCorrectionsByStudyRunId(run.id());
        StudyRunSummaryDto summary = new StudyRunSummaryDto(
            run.multiplanarRunId(),
            run.id(),
            run.traceId(),
            study.caseId(),
            run.requestedInferenceMode(),
            run.effectiveInferenceMode(),
            run.status(),
            ReviewStatusMapper.toApiStatus(run.reviewStatus()),
            blankToNull(run.reviewer()),
            run.reviewedAt() == null ? null : run.reviewedAt().toString(),
            blankToNull(run.comments()),
            blankToNull(run.sagittalRunId()),
            blankToNull(run.axialRunId()),
            blankToNull(run.sagittalModelKey()),
            blankToNull(run.axialModelKey()),
            blankToNull(run.sagittalArtifactHash()),
            blankToNull(run.axialArtifactHash()),
            planesFor(List.of(), run),
            measurementsByPlane.values().stream().mapToInt(List::size).sum(),
            run.artifacts().size(),
            run.createdAt().toString(),
            run.updatedAt().toString(),
            true,
            true,
            DATA_ORIGIN_DATABASE
        );
        Map<String, Object> canonicalRun = canonicalRun(study, run, corrections);
        return new StudyRunDetailDto(summary, measurementsByPlane, artifactsByPlane, corrections, publishedMetricsSnapshot(run, corrections), canonicalRun);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedMetricsSnapshot(StudyRun run) {
        return publishedMetricsSnapshot(run, repository.findCorrectionsByStudyRunId(run.id()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedMetricsSnapshot(StudyRun run, List<MeasurementCorrection> corrections) {
        Map<String, Object> snapshot = new LinkedHashMap<>(run.metricsSnapshot());
        Object threeD = snapshot.get("threeD");
        if (threeD instanceof Map<?, ?> map) {
            snapshot.put("threeD", threeDAssetPublisher.publish(run.multiplanarRunId(), (Map<String, Object>) map));
        }
        snapshot.put("planes", publishVolumeSlices(snapshot.getOrDefault("planes", Map.of()), run.artifacts(), corrections));
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> canonicalRun(Study study, StudyRun run, List<MeasurementCorrection> corrections) {
        Map<String, Object> snapshot = publishedMetricsSnapshot(run, corrections);
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schemaVersion", String.valueOf(snapshot.getOrDefault("sourceSchemaVersion", "pfi.backend-run-snapshot.v2")));
        canonical.put("runId", run.multiplanarRunId());
        canonical.put("traceId", run.traceId());
        canonical.put("caseId", study.caseId());
        canonical.put("workspaceMode", snapshot.get("workspaceMode"));
        canonical.put("requestedInferenceMode", run.requestedInferenceMode());
        canonical.put("effectiveInferenceMode", run.effectiveInferenceMode());
        canonical.put("status", snapshot.getOrDefault("status", run.status()));
        canonical.put("requestedPlanes", snapshot.getOrDefault("requestedPlanes", List.of()));
        canonical.put("completedPlanes", snapshot.getOrDefault("completedPlanes", List.of()));
        canonical.put("readiness", snapshot.getOrDefault("readiness", Map.of()));
        canonical.put("planes", snapshot.getOrDefault("planes", Map.of()));
        canonical.put("threeD", snapshot.get("threeD"));
        canonical.put("quality", snapshot.getOrDefault("quality", Map.of()));
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", ReviewStatusMapper.toApiStatus(run.reviewStatus()));
        review.put("reviewer", blankToNull(run.reviewer()));
        review.put("reviewedAt", run.reviewedAt() == null ? null : run.reviewedAt().toString());
        review.put("comments", blankToNull(run.comments()));
        review.put("corrections", corrections.stream().map(this::publishedCorrection).toList());
        canonical.put("review", review);
        // No fabricated positive defaults: a legacy snapshot without a stored
        // governance map yields humanReviewRequired/notClinicalDiagnosis=null
        // here, never a fabricated true — the frontend already treats null as
        // "not evaluable", exactly like a live run missing that field would.
        Map<String, Object> governance = snapshot.get("governance") instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
        canonical.put("governance", governance);
        canonical.put("humanReviewRequired", governance.get("humanReviewRequired"));
        canonical.put("notClinicalDiagnosis", governance.get("notClinicalDiagnosis"));
        canonical.put("synthetic", snapshot.get("synthetic"));
        canonical.put("fallbackReason", snapshot.get("fallbackReason"));
        return canonical;
    }

    @SuppressWarnings("unchecked")
    private Object publishVolumeSlices(Object planesNode, List<RunArtifact> artifacts, List<MeasurementCorrection> corrections) {
        if (!(planesNode instanceof Map<?, ?> rawPlanes)) return planesNode;
        Map<String, Object> planes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawPlanes.entrySet()) {
            String planeName = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (!(value instanceof Map<?, ?> rawPlane)) {
                planes.put(planeName, value);
                continue;
            }
            Map<String, Object> plane = new LinkedHashMap<>((Map<String, Object>) rawPlane);
            Object inputNode = plane.get("input");
            if (inputNode instanceof Map<?, ?> inputMap) {
                String runId = String.valueOf(plane.getOrDefault("planeRunId", ""));
                List<Map<String, Object>> measurements = mapList(plane.get("measurements"));
                List<Map<String, Object>> landmarks = mapList(plane.get("landmarks"));
                plane.put("input", volumeSliceCatalogService.normalizePlaneInput(
                    new LinkedHashMap<>((Map<String, Object>) inputMap),
                    runId,
                    planeName,
                    artifacts,
                    measurements,
                    landmarks,
                    corrections
                ));
            }
            planes.put(planeName, plane);
        }
        return planes;
    }

    private Map<String, Object> publishedCorrection(MeasurementCorrection correction) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("measurementId", correction.measurementId());
        value.put("label", correction.label());
        value.put("beforeValue", correction.beforeValue());
        value.put("afterValue", correction.afterValue());
        value.put("comment", correction.comment());
        value.put("createdAt", correction.createdAt().toString());
        return value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    private List<String> planesFor(List<InputResource> inputs, StudyRun run) {
        Set<String> planes = new LinkedHashSet<>();
        if (run != null) {
            if (hasText(run.sagittalRunId())) planes.add("sagittal");
            if (hasText(run.axialRunId())) planes.add("axial");
        }
        for (InputResource input : inputs) {
            if (hasText(input.plane())) planes.add(input.plane());
        }
        return List.copyOf(planes);
    }

    private Map<String, List<Map<String, Object>>> measurementsByPlane(Map<String, Object> metricsSnapshot) {
        Map<String, List<Map<String, Object>>> byPlane = new LinkedHashMap<>();
        byPlane.put("sagittal", measurementValues(metricsSnapshot, "sagittal"));
        byPlane.put("axial", measurementValues(metricsSnapshot, "axial"));
        byPlane.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return byPlane;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> measurementValues(Map<String, Object> metricsSnapshot, String plane) {
        Object planesNode = metricsSnapshot.get("planes");
        if (!(planesNode instanceof Map<?, ?> planesMap)) return List.of();
        Object planeNode = planesMap.get(plane);
        if (!(planeNode instanceof Map<?, ?> planeMap)) return List.of();
        Object measurements = planeMap.get("measurements");
        if (!(measurements instanceof List<?> list)) return List.of();
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> value = new LinkedHashMap<>((Map<String, Object>) raw);
            value.putIfAbsent("aiValue", value.get("value"));
            value.put("plane", plane);
            value.putIfAbsent("source", "AI");
            value.putIfAbsent("status", "pendiente");
            normalized.add(value);
        }
        return normalized;
    }

    private Map<String, List<Map<String, Object>>> artifactsByPlane(List<RunArtifact> artifacts) {
        Map<String, List<Map<String, Object>>> byPlane = new LinkedHashMap<>();
        for (RunArtifact artifact : artifacts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("plane", artifact.plane());
            item.put("runId", artifact.runId());
            item.put("assetName", artifact.assetName());
            item.put("contentType", artifact.contentType());
            item.put("proxyUrl", "/api/ai/assets/" + artifact.runId() + "/" + artifact.plane() + "/" + artifact.assetName());
            item.put("storageStatus", artifact.storageStatus());
            item.put("storageKind", artifact.storageKind());
            item.put("sizeBytes", artifact.sizeBytes());
            item.put("sha256", artifact.sha256());
            item.put("available", artifact.available());
            item.put("createdAt", artifact.createdAt().toString());
            byPlane.computeIfAbsent(artifact.plane(), ignored -> new ArrayList<>()).add(item);
        }
        return byPlane;
    }

    private Map<String, Object> summary(List<StudyRowDto> rows) {
        long pending = rows.stream().filter(row -> "pendiente".equals(row.reviewStatus()) || "observado".equals(row.reviewStatus())).count();
        long completed = rows.stream().filter(row -> "aceptado".equals(row.reviewStatus())).count();
        long flagged = rows.stream().filter(row -> "alta".equals(row.priority()) || "observado".equals(row.reviewStatus())).count();
        return Map.of(
            "total", rows.size(),
            "pending", pending,
            "completed", completed,
            "flagged", flagged
        );
    }

    private <T> T databaseCall(DatabaseSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (StudyNotFoundException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException(ex);
        }
    }

    private String firstNonBlank(String first, String second) {
        return hasText(first) ? first : blankToNull(second);
    }

    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface DatabaseSupplier<T> {
        T get();
    }
}
