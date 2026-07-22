package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MultiplanarRunPersistenceService {
    private final StudyRunService studyRunService;
    private final Clock clock;

    @Autowired
    public MultiplanarRunPersistenceService(StudyRunService studyRunService) {
        this(studyRunService, Clock.systemUTC());
    }

    MultiplanarRunPersistenceService(StudyRunService studyRunService, Clock clock) {
        this.studyRunService = studyRunService;
        this.clock = clock;
    }

    public void persistSuccessfulRun(MultiplanarRunRequestDto request, MultiplanarRunResponseDto response) {
        if (response == null || blank(response.runId())) return;
        Study study = studyRunService.findStudyByCaseId(request.caseId())
            .orElseGet(() -> studyRunService.createStudy(request.caseId(), "created"));

        registerInput(study, "sagittal", request.sagittalInputId());
        registerInput(study, "axial", request.axialInputId());

        MultiplanarRunResponseDto.PlaneDto sagittal = response.planes() == null ? null : response.planes().sagittal();
        MultiplanarRunResponseDto.PlaneDto axial = response.planes() == null ? null : response.planes().axial();
        String studyRunId = UUID.randomUUID().toString();
        Instant now = clock.instant();

        studyRunService.createRunWithId(
            studyRunId,
            study,
            response.runId(),
            valueOrEmpty(response.traceId()),
            requestedInferenceMode(request),
            valueOrDefault(response.effectiveInferenceMode(), requestedInferenceMode(request)),
            valueOrDefault(modelKey(sagittal), request.sagittalModelKey()),
            valueOrDefault(modelKey(axial), request.axialModelKey()),
            artifactHash(sagittal),
            artifactHash(axial),
            valueOrEmpty(runId(sagittal)),
            valueOrEmpty(runId(axial)),
            safeMap(response.assets()),
            metricsSnapshot(response),
            artifacts(studyRunId, sagittal, axial, now),
            "completed",
            reviewStatus(response.review()),
            "",
            null,
            ""
        );
    }

    private void registerInput(Study study, String plane, String inputId) {
        if (blank(inputId)) return;
        studyRunService.createInput(study, plane, inputId, "ai-module-input", 0);
    }

    private List<RunArtifact> artifacts(String studyRunId, MultiplanarRunResponseDto.PlaneDto sagittal, MultiplanarRunResponseDto.PlaneDto axial, Instant now) {
        List<RunArtifact> artifacts = new ArrayList<>();
        addArtifacts(artifacts, studyRunId, "sagittal", runId(sagittal), sagittal == null ? null : sagittal.assets(), now);
        addArtifacts(artifacts, studyRunId, "axial", runId(axial), axial == null ? null : axial.assets(), now);
        return artifacts;
    }

    private void addArtifacts(List<RunArtifact> artifacts, String studyRunId, String plane, String runId, Map<String, Object> assets, Instant now) {
        if (blank(runId) || assets == null) return;
        for (Object value : assets.values()) {
            String assetName = basename(String.valueOf(value));
            if (blank(assetName)) continue;
            artifacts.add(new RunArtifact(
                UUID.randomUUID().toString(),
                studyRunId,
                runId,
                plane,
                assetName,
                assetName.endsWith(".png") ? "image/png" : "application/octet-stream",
                assetName,
                now
            ));
        }
    }

    private Map<String, Object> metricsSnapshot(MultiplanarRunResponseDto response) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (response.planes() != null) {
            metrics.put("sagittal", planeMetrics(response.planes().sagital()));
            metrics.put("axial", planeMetrics(response.planes().axial()));
        }
        return metrics;
    }

    private Map<String, Object> planeMetrics(MultiplanarRunResponseDto.PlaneDto plane) {
        if (plane == null) return Map.of();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("effectiveInferenceMode", valueOrEmpty(plane.effectiveInferenceMode()));
        metrics.put("inferenceMode", valueOrEmpty(plane.inferenceMode()));
        metrics.put("measurements", safeMap(plane.measurements()));
        metrics.put("evidence", safeMap(plane.evidence()));
        metrics.put("quality", safeMap(plane.quality()));
        return metrics;
    }

    private String requestedInferenceMode(MultiplanarRunRequestDto request) {
        if (request.metadata() == null) return "";
        Object value = request.metadata().get("inferenceMode");
        return value == null ? "" : String.valueOf(value);
    }

    private String artifactHash(MultiplanarRunResponseDto.PlaneDto plane) {
        if (plane == null) return "";
        if (!blank(plane.artifactHash())) return plane.artifactHash();
        String aiOutputHash = valueFrom(plane.aiOutput(), "artifactHash");
        if (!blank(aiOutputHash)) return aiOutputHash;
        if (plane.modelArtifact() == null) return "";
        for (String key : List.of("artifactHash", "checkpointHash", "hash", "sha256")) {
            String value = valueFrom(plane.modelArtifact(), key);
            if (!blank(value)) return value;
        }
        return "";
    }

    private String valueFrom(Map<String, Object> map, String key) {
        if (map == null) return "";
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String reviewStatus(Map<String, Object> review) {
        if (review == null) return "pending";
        Object value = review.get("status");
        if (value == null || String.valueOf(value).isBlank() || "pendiente".equalsIgnoreCase(String.valueOf(value))) return "pending";
        return String.valueOf(value);
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String modelKey(MultiplanarRunResponseDto.PlaneDto plane) {
        return plane == null ? "" : valueOrEmpty(plane.modelKey());
    }

    private String runId(MultiplanarRunResponseDto.PlaneDto plane) {
        return plane == null ? "" : valueOrEmpty(plane.runId());
    }

    private String basename(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.contains("..") ? "" : name;
    }

    private String valueOrDefault(String value, String fallback) {
        return blank(value) ? valueOrEmpty(fallback) : value.trim();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
