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
import java.util.Set;
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
            artifacts(studyRunId, response, sagittal, axial, now),
            response.synthetic() ? "completed_synthetic" : "completed",
            "pending",
            "",
            null,
            ""
        );
        if (runAssetSnapshotService != null) {
            boolean meshAvailable = runAssetSnapshotService.snapshot(persistedRun);
            if (!meshAvailable) {
                downgradeThreeDIfMeshUnavailable(persistedRun);
            }
        }
    }

    /**
     * The run's metricsSnapshot (with threeD.enabled=true, computed from the AI
     * Module's own response) is persisted before the workspace mesh is actually
     * downloaded/validated/stored — that download can still fail afterward. If it
     * does, this rewrites threeD to an honest blocked state so the backend never
     * publishes threeD.enabled=true with a URL that points at unavailable content
     * (no rollback of the whole run: sagittal/axial planes remain valid and reviewable).
     */
    @SuppressWarnings("unchecked")
    private void downgradeThreeDIfMeshUnavailable(ar.edu.uade.pfi.backend.domain.StudyRun persistedRun) {
        Object rawThreeD = persistedRun.metricsSnapshot().get("threeD");
        if (!(rawThreeD instanceof Map<?, ?> threeDMap) || !Boolean.TRUE.equals(threeDMap.get("enabled"))) return;
        Map<String, Object> blockedThreeD = new LinkedHashMap<>((Map<String, Object>) threeDMap);
        blockedThreeD.put("enabled", false);
        blockedThreeD.put("status", "experimental_blocked_insufficient_geometry");
        blockedThreeD.put("assets", List.of());
        Map<String, Object> updatedSnapshot = new LinkedHashMap<>(persistedRun.metricsSnapshot());
        updatedSnapshot.put("threeD", blockedThreeD);
        studyRunService.updateRunMetricsSnapshot(persistedRun.multiplanarRunId(), updatedSnapshot);
    }

    private void registerInput(Study study, String plane, String inputId) {
        if (blank(inputId)) return;
        studyRunService.createInput(study, plane, inputId, "ai-module-input", 0);
    }

    private List<RunArtifact> artifacts(String studyRunId, CanonicalMultiplanarRun response, CanonicalPlaneRun sagittal, CanonicalPlaneRun axial, Instant now) {
        List<RunArtifact> artifacts = new ArrayList<>();
        addArtifacts(artifacts, studyRunId, "sagittal", sagittal, now);
        addArtifacts(artifacts, studyRunId, "axial", axial, now);
        addWorkspaceArtifacts(artifacts, studyRunId, response, now);
        return artifacts;
    }

    private void addArtifacts(List<RunArtifact> artifacts, String studyRunId, String plane, CanonicalPlaneRun planeRun, Instant now) {
        if (planeRun == null || blank(planeRun.planeRunId())) return;
        for (Map<String, Object> asset : planeRun.assets()) {
            if (!isValidAssetMetadata(asset, planeRun.planeRunId(), plane)) continue;
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

    private void addWorkspaceArtifacts(List<RunArtifact> artifacts, String studyRunId, CanonicalMultiplanarRun response, Instant now) {
        Map<String, Object> threeD = response.threeD();
        Object assetsValue = threeD.get("assets");
        if (!(assetsValue instanceof List<?> assets)) return;
        for (Object value : assets) {
            if (!(value instanceof Map<?, ?> rawAsset)) continue;
            Map<String, Object> asset = objectMap(rawAsset);
            if (!isValidAssetMetadata(asset, response.multiplanarRunId(), "workspace")) continue;
            String assetName = text(asset.get("assetName"));
            String contentType = blankOrDefault(text(asset.get("contentType")), "application/octet-stream");
            artifacts.add(new RunArtifact(
                UUID.randomUUID().toString(),
                studyRunId,
                response.multiplanarRunId(),
                "workspace",
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
    private boolean isValidAssetMetadata(Map<String, Object> asset, String planeRunId, String plane) {
        String assetName = text(asset.get("assetName"));
        if (blank(assetName)) return false;
        if (!Boolean.TRUE.equals(asset.get("generated"))) return false;
        if (!isAllowedAsset(assetName, text(asset.get("contentType")))) return false;
        String relativePath = text(asset.get("relativePath"));
        if (blank(relativePath)) return false;
        String normalized = relativePath.toLowerCase(Locale.ROOT);
        if (normalized.contains("..")) return false;
        if (normalized.matches("^[a-z][a-z0-9+.-]*://.*")) return false;
        if (normalized.matches("^[a-z]:\\\\.*") || normalized.matches("^[a-z]:/.*")) return false;
        if (normalized.contains("localhost") || normalized.contains("cloudflare") || normalized.contains("host.docker.internal")) return false;
        if (!relativePath.contains(planeRunId)) return false;
        return relativePath.contains("/" + plane + "/" + assetName);
    }

    /**
     * Per-class segmentation mask ({@code mask-vertebra_group.png}). Cannot live in a
     * fixed list because the classes depend on the model. The pattern only accepts the
     * shape of a class name — lowercase, digits and underscore — so it admits no path
     * separators and no arbitrary extension.
     */
    private static final java.util.regex.Pattern CLASS_MASK_NAME =
            java.util.regex.Pattern.compile("^mask-[a-z][a-z0-9_]{0,31}\\.png$");

    private boolean isAllowedAsset(String assetName, String contentType) {
        if ("lumbar-3d-mesh.json".equals(assetName)) return "application/json".equalsIgnoreCase(contentType);
        boolean known = List.of("input.png", "overlay.png", "mask-preview.png").contains(assetName)
                || CLASS_MASK_NAME.matcher(assetName).matches();
        return known && "image/png".equalsIgnoreCase(contentType);
    }

    private Map<String, Object> objectMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() instanceof String key) copy.put(key, entry.getValue());
        }
        return copy;
    }

    private Map<String, Object> metricsSnapshot(CanonicalMultiplanarRun response) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
        snapshot.put("sourceSchemaVersion", valueOrEmpty(response.schemaVersion()));
        snapshot.put("status", valueOrEmpty(response.status()));
        snapshot.put("caseId", valueOrEmpty(response.caseId()));
        snapshot.put("workspaceMode", valueOrEmpty(response.workspaceMode()));
        snapshot.put("requestedInferenceMode", valueOrEmpty(response.requestedInferenceMode()));
        snapshot.put("effectiveInferenceMode", valueOrEmpty(response.effectiveInferenceMode()));
        snapshot.put("requestedPlanes", response.requestedPlanes());
        snapshot.put("completedPlanes", response.completedPlanes());
        snapshot.put("synthetic", response.synthetic());
        putIfPresent(snapshot, "fallbackReason", response.fallbackReason());
        snapshot.put("readiness", response.readiness());
        snapshot.put("governance", governanceMap(response.governance()));
        snapshot.put("threeD", response.threeD());
        snapshot.put("quality", response.quality());
        if (!response.degenerativeFindings().isEmpty()) {
            snapshot.put("degenerativeFindings", response.degenerativeFindings());
        }
        snapshot.put("review", response.review());
        Map<String, Object> planes = new LinkedHashMap<>();
        planes.put("sagittal", planeSnapshot(response.sagittal(), response.governance()));
        planes.put("axial", planeSnapshot(response.axial(), response.governance()));
        snapshot.put("planes", planes);
        return snapshot;
    }

    private Map<String, Object> planeSnapshot(CanonicalPlaneRun plane, CanonicalMultiplanarRun.Governance governance) {
        if (plane == null) return null;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("planeRunId", plane.planeRunId());
        snapshot.put("plane", plane.plane());
        snapshot.put("status", plane.status());
        snapshot.put("effectiveInferenceMode", plane.effectiveInferenceMode());
        snapshot.put("synthetic", plane.synthetic());
        putIfPresent(snapshot, "fallbackReason", plane.fallbackReason());
        snapshot.put("model", plane.model());
        snapshot.put("input", plane.input());
        snapshot.put("coordinateSpace", plane.coordinateSpace());
        snapshot.put("series", plane.series());
        snapshot.put("masks", plane.masks());
        snapshot.put("landmarks", plane.landmarks());
        snapshot.put("measurements", transformMeasurements(plane));
        snapshot.put("segmentation", plane.segmentation());
        snapshot.put("quality", plane.quality());
        snapshot.put("humanReviewRequired", governance.humanReviewRequired());
        snapshot.put("notClinicalDiagnosis", governance.notClinicalDiagnosis());
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
            transformed.put("level", vertebralLevel(measurement.get("level")));
            transformed.put("levelScope", "study".equals(measurement.get("levelScope")) ? "study" : "level");
            transformed.put("points", measurement.getOrDefault("points", List.of()));
            transformed.put("experimental", Boolean.TRUE.equals(measurement.get("experimental")));
            transformed.put("detail", measurement.get("detail"));
            transformed.put("sliceIndex", measurement.get("sliceIndex"));
            transformed.put("measurementBasis", measurement.get("measurementBasis"));
            transformed.put("linkedLandmarkIds", measurement.getOrDefault("linkedLandmarkIds", List.of()));
            result.add(transformed);
        }
        return result;
    }

    /**
     * Accepts a measurement's level only when it names a vertebral level.
     *
     * Older AI Module runs put the plane ("sagittal") in this field, which is not a
     * vertebral level at all; letting that through would file every measurement under
     * a level that does not exist.
     *
     * <p>The check is on shape and not on a fixed list of five disc spaces. That list
     * was silently destroying data the AI Module had got right: a disc the model
     * identified as T12-L1 and a vertebral body it named L4 both arrived here and
     * left as null, so the reviewer saw "unassigned" — which reads as "the AI could
     * not tell" — for a level it had in fact determined. The shape still rejects
     * "sagittal", which is what this filter exists for.
     */
    private String vertebralLevel(Object value) {
        if (!(value instanceof String text)) return null;
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        return VERTEBRAL_LEVEL.matcher(normalized).matches() ? normalized : null;
    }

    /** A vertebral body ({@code L4}, {@code T12}, {@code S1}) or a space between two. */
    private static final java.util.regex.Pattern VERTEBRAL_LEVEL =
            java.util.regex.Pattern.compile("^[CTLS]\\d{1,2}(-[CTLS]\\d{1,2})?$");

    private String artifactHash(CanonicalPlaneRun plane) {
        return plane == null ? "" : text(plane.model().get("artifactHash"));
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
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
