package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunAssetContent;
import ar.edu.uade.pfi.backend.domain.RunAssetStorageDiagnostics;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class RunAssetSnapshotServiceTest {

    @Test
    void snapshotsInputAndOverlayPngButNeverTreatsMaskOrConfidenceAsPng() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        StudyRunService studyRunService = new StudyRunService(repository);
        Study study = studyRunService.createStudy("CASE-SNAPSHOT", "created");

        List<RunArtifact> artifacts = List.of(
            artifact(study.id(), "run-sag-snap", "sagittal", "input.png", "image/png"),
            artifact(study.id(), "run-sag-snap", "sagittal", "overlay.png", "image/png"),
            artifact(study.id(), "run-sag-snap", "sagittal", "mask.npy", "application/octet-stream"),
            artifact(study.id(), "run-sag-snap", "sagittal", "confidence.npy", "application/octet-stream")
        );
        StudyRun run = studyRunService.createRunWithId(
            UUID.randomUUID().toString(), study, "multi-snapshot", "trace-snapshot",
            "real_baseline", "real_baseline", "sagittal_spider", "", "sha256:sag", "",
            "run-sag-snap", "", Map.of(), Map.of(), artifacts, "completed", "pending", "", null, ""
        );

        AiServiceOperations ai = mock(AiServiceOperations.class);
        byte[] pngBytes = {1, 2, 3, 4};
        when(ai.getAsset(eq("run-sag-snap"), eq("sagittal"), eq("input.png"))).thenReturn(pngResponse(pngBytes));
        when(ai.getAsset(eq("run-sag-snap"), eq("sagittal"), eq("overlay.png"))).thenReturn(pngResponse(pngBytes));

        FakeRunAssetContentStorage storage = new FakeRunAssetContentStorage();
        RunAssetSnapshotService snapshotService = new RunAssetSnapshotService(ai, repository, storage, null, 5_242_880);

        snapshotService.snapshot(run);

        StudyRun reloaded = repository.findRunByMultiplanarRunId("multi-snapshot").orElseThrow();
        Map<String, String> statusByAsset = new HashMap<>();
        for (RunArtifact reloadedArtifact : reloaded.artifacts()) {
            statusByAsset.put(reloadedArtifact.assetName(), reloadedArtifact.storageStatus());
        }

        assertEquals("stored", statusByAsset.get("input.png"));
        assertEquals("stored", statusByAsset.get("overlay.png"));
        assertEquals("rejected", statusByAsset.get("mask.npy"));
        assertEquals("rejected", statusByAsset.get("confidence.npy"));
        assertEquals(2, storage.stored.size());
    }

    @Test
    void snapshotsWorkspaceLumbarMeshJsonAsDurablePublicAsset() {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        StudyRunService studyRunService = new StudyRunService(repository);
        Study study = studyRunService.createStudy("CASE-3D-SNAPSHOT", "created");
        RunArtifact mesh = artifact(study.id(), "multi-3d-snapshot", "workspace", "lumbar-3d-mesh.json", "application/json");
        StudyRun run = studyRunService.createRunWithId(
            UUID.randomUUID().toString(), study, "multi-3d-snapshot", "trace-3d-snapshot",
            "real_baseline", "real_baseline", "sagittal_spider", "axial_t2_alkafri", "sha256:sag", "sha256:ax",
            "run-sag-3d", "run-ax-3d", Map.of(), Map.of(), List.of(mesh), "completed", "pending", "", null, ""
        );

        AiServiceOperations ai = mock(AiServiceOperations.class);
        byte[] body = "{\"kind\":\"experimental_geometric_proxy\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(ai.getAsset(eq("multi-3d-snapshot"), eq("workspace"), eq("lumbar-3d-mesh.json"))).thenReturn(jsonResponse(body));
        FakeRunAssetContentStorage storage = new FakeRunAssetContentStorage();

        new RunAssetSnapshotService(ai, repository, storage, null, 5_242_880).snapshot(run);

        StudyRun reloaded = repository.findRunByMultiplanarRunId("multi-3d-snapshot").orElseThrow();
        RunArtifact stored = reloaded.artifacts().stream().filter(artifact -> artifact.assetName().equals("lumbar-3d-mesh.json")).findFirst().orElseThrow();
        assertEquals("stored", stored.storageStatus());
        assertEquals("application/json", stored.contentType());
        assertEquals(1, storage.stored.size());
    }

    private RunArtifact artifact(String studyId, String runId, String plane, String assetName, String contentType) {
        return new RunArtifact(UUID.randomUUID().toString(), studyId, runId, plane, assetName, contentType, assetName, Instant.now());
    }

    private ResponseEntity<byte[]> pngResponse(byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private ResponseEntity<byte[]> jsonResponse(byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private static final class FakeRunAssetContentStorage implements RunAssetContentStorage {
        private final Map<String, RunAssetContent> stored = new HashMap<>();

        @Override
        public RunAssetContent store(RunArtifact artifact, byte[] content, String sha256) {
            RunAssetContent value = new RunAssetContent(artifact.id(), content, sha256, content.length, "fake", Instant.now());
            stored.put(artifact.id(), value);
            return value;
        }

        @Override
        public Optional<RunAssetContent> find(String artifactId) {
            return Optional.ofNullable(stored.get(artifactId));
        }

        @Override
        public boolean exists(String artifactId) {
            return stored.containsKey(artifactId);
        }

        @Override
        public RunAssetContent deleteOrReplace(RunArtifact artifact, byte[] content, String sha256) {
            return store(artifact, content, sha256);
        }

        @Override
        public RunAssetStorageDiagnostics diagnostics() {
            return new RunAssetStorageDiagnostics("fake", true, stored.size(), 0, 0);
        }
    }
}
