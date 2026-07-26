package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Persists a CanonicalMultiplanarRun (produced by AiServiceClient for either contract
 * version). There is no v1/v2 branching here: by the time a run reaches this service it
 * has already been adapted to the canonical shape, so persistence logic is uniform.
 */
@Service
public class MultiplanarRunPersistenceService {
    private static final String SNAPSHOT_SCHEMA_VERSION = "pfi.backend-run-snapshot.v2";

    private final StudyRunService studyRunService;
    private final RunAssetSnapshotService runAssetSnapshotService;
    private final Clock clock;

    @Autowired
    public MultiplanarRunPersistenceService(StudyRunService studyRunService, RunAssetSnapshotService runAssetSnapshotService) {
        this(studyRunService, runAssetSnapshotService, Clock.systemUTC());
    }

    public MultiplanarRunPersistenceService(StudyRunService studyRunService) {
        this(studyRunService, null, Clock.systemUTC());
    }

    MultiplanarRunPersistenceService(StudyRunService studyRunService, Clock clock) {
        this(studyRunService, null, clock);
    }

    MultiplanarRunPersistenceService(StudyRunService studyRunService, RunAssetSnapshotService runAssetSnapshotService, Clock clock) {
        this.studyRunService = studyRunService;
        this.runAssetSnapshotService = runAssetSnapshotService;
        this.clock = clock;
    }

    public void persistSuccessfulRun(MultiplanarRunRequestDto request, CanonicalMultiplanarRun response) {
        persistSuccessfulRun(request, null, response);
    }

    public StudyRunService.PreparedStudyMetadata prepareStudyMetadata(String caseId, StudyMetadataDto studyMetadata) {
        return studyRunService.prepareStudyMetadata(caseId, studyMetadata);
    }

    public void persistSuccessfulRun(MultiplanarRunRequestDto request, StudyMetadataDto studyMetadata, CanonicalMultiplanarRun response) {
        if (response == null || blank(response.multiplanarRunId())) return;
        Study study = studyRunService.upsertStudyMetadata(request.caseId(), studyMetadata);

        registerInput(study, "sagittal", request.sagittalInputId());
        registerInput(study, "axial", request.axialInputId());

        CanonicalPlaneRun sagittal = response.sagittal();
        CanonicalPlaneRun axial = response.axial();
        String studyRunId = UUID.randomUUID().toString();
        Instant now = clock.instant();

        var persistedRun = studyRunService.createRunWithId(
            studyRunId,
            study,
            response.multiplanarRunId(),
            valueOrEmpty(response.traceId()),
            valueOrEmpty(response.requestedInferenceMode()),
            valueOrDefault(response.effectiveInferenceMode(), response.requestedInferenceMode()),
            valueOrDefault(modelKey(sagittal), request.sagittalModelKey()),
            valueOrDefault(modelKey(axial), request.axialModelKey()),
            artifactHash(sagittal),
            artifactHash(axial),
            valueOrEmpty(planeRunId(sagittal)),
            valueOrEmpty(planeRunId(axial)),
            Map.of(),
            metricsSnapshot(response),
            artifacts(studyRunId, sagittal, axial, now),
            response.synthetic() ? "completed_synthetic" : "completed",
            "pending",
            "",
            null,
            ""
        );
        if (runAssetSnapshotService != null) {
            runAssetSnapshotService.snapshot(persistedRun);
        }
    }

    private void registerInput(Study study, String plane, String inputId) {
        if (blank(inputId)) return;
        studyRunService.createInput(study, plane, inputId, "ai-module-input", 0);
    }

    private List<RunArtifact> artifacts(String studyRunId, CanonicalPlaneRun sagittal, CanonicalPlaneRun axial, Instant now) {
        List<RunArtifact> artifacts = new ArrayList<>();
        addArtifacts(artifacts, studyRunId, "sagittal", sagittal, now);
        addArtifacts(artifacts, studyRunId, "axial", axial, now);
        return artifacts;
    }

    private void addArtifacts(List<RunArtifact> artifacts, String studyRunId, String plane, CanonicalPlaneRun planeRun, Instant now) {
        if (planeRun == null || blank(planeRun.planeRunId())) return;
        for (Map<String, Object> asset : planeRun.assets()) {
            if (!isValidAssetMetadata(asset, planeRun.planeRunId())) continue;
            String assetName = text(asset.get("assetName"));
            String contentType = blankOrDefault(text(asset.get("contentType")), "application/octet-stream");
            artifacts.add(new RunArtifact(
                UUID.randomUUID().toString(),
                studyRunId,
                planeRun.planeRunId(),
                plane,
                assetName,
                contentType,
                assetName,
                now
            ));
        }
    }

    /**
     * Enforces the v2 asset-metadata contract before it is trusted for persistence:
     * generated must be true, relativePath must be a same-origin relative asset path
     * (never a filesystem-absolute path, a foreign host, or a directory traversal) and
     * must reference the plane it claims to belong to.
     */
    private boolean isValidAssetMetadata(Map<String, Object> asset, String planeRunId) {
        String assetName = text(asset.get("assetName"));
        if (blank(assetName)) return false;
        if (!Boolean.TRUE.equals(asset.get("generated"))) return false;
        String relativePath = text(asset.get("relativePath"));
        if (blank(relativePath)) return false;
        String normalized = relativePath.toLowerCase(Locale.ROOT);
        if (normalized.contains("..")) return false;
        if (normalized.matches("^[a-z][a-z0-9+.-]*://.*")) return false;
        if (normalized.matches("^[a-z]:\\\\.*") || normalized.matches("^[a-z]:/.*")) return false;
        if (normalized.contains("localhost") || normalized.contains("cloudflare") || normalized.contains("host.docker.internal")) return false;
        return relativePath.contains(planeRunId);
    }

    private Map<String, Object> metricsSnapshot(CanonicalMultiplanarRun response) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        snapshot.put("sourceSchemaVersion", valueOrEmpty(response.schemaVersion()));
        snapshot.put("workspaceMode", valueOrEmpty(response.workspaceMode()));
        snapshot.put("synthetic", response.synthetic());
        snapshot.put("readiness", response.readiness());
        snapshot.put("governance", governanceMap(response.governance()));
        snapshot.put("threeD", response.threeD());
        Map<String, Object> planes = new LinkedHashMap<>();
        planes.put("sagittal", planeSnapshot(response.sagittal()));
        planes.put("axial", planeSnapshot(response.axial()));
        snapshot.put("planes", planes);
        return snapshot;
    }

    private Map<String, Object> planeSnapshot(CanonicalPlaneRun plane) {
        if (plane == null) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("model", plane.model());
        snapshot.put("input", plane.input());
        snapshot.put("coordinateSpace", plane.coordinateSpace());
        snapshot.put("series", plane.series());
        snapshot.put("masks", plane.masks());
        snapshot.put("landmarks", plane.landmarks());
        snapshot.put("measurements", transformMeasurements(plane));
        snapshot.put("quality", plane.quality());
        return snapshot;
    }

    private Map<String, Object> governanceMap(CanonicalMultiplanarRun.Governance governance) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("humanReviewRequired", governance.humanReviewRequired());
        map.put("notClinicalDiagnosis", governance.notClinicalDiagnosis());
        map.put("deidentified", governance.deidentified());
        map.put("diagnosisGenerated", governance.diagnosisGenerated());
        return map;
    }

    /**
     * Transforms the AI Module's raw v2 measurement list into the shape consumed by
     * worklist/history/frontend. Only fields the AI Module actually provided are
     * carried through — level, reviewer values and clinical labels (e.g. "L4-L5") are
     * never invented here. Reviewer corrections live separately in
     * domain_review_corrections.
     */
    private List<Map<String, Object>> transformMeasurements(CanonicalPlaneRun plane) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> measurement : plane.measurements()) {
            Object value = measurement.get("value");
            Map<String, Object> transformed = new LinkedHashMap<>();
            transformed.put("id", measurement.get("id"));
            transformed.put("labelKey", measurement.get("labelKey"));
            transformed.put("aiValue", value);
            transformed.put("value", value);
            transformed.put("unit", measurement.get("unit"));
            transformed.put("confidence", measurement.get("confidence"));
            transformed.put("source", "AI");
            transformed.put("status", measurement.get("status"));
            transformed.put("plane", plane.plane());
            transformed.put("level", null);
            transformed.put("measurementBasis", measurement.get("measurementBasis"));
            transformed.put("linkedLandmarkIds", measurement.getOrDefault("linkedLandmarkIds", List.of()));
            result.add(transformed);
        }
        return result;
    }

    private String artifactHash(CanonicalPlaneRun plane) {
        return plane == null ? "" : text(plane.model().get("artifactHash"));
    }

    /**
     * Model key lives under different map keys depending on the upstream contract:
     * v2 stores the wire name "key" as-is, v1 stores it as "modelKey".
     */
    private String modelKey(CanonicalPlaneRun plane) {
        if (plane == null) return "";
        Map<String, Object> model = plane.model();
        return text(model.containsKey("key") ? model.get("key") : model.get("modelKey"));
    }

    private String planeRunId(CanonicalPlaneRun plane) {
        return plane == null ? "" : valueOrEmpty(plane.planeRunId());
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String blankOrDefault(String value, String fallback) {
        return blank(value) ? fallback : value;
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
