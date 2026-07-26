package ar.edu.uade.pfi.backend.controller;

import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.domain.RunAssetContent;
import ar.edu.uade.pfi.backend.domain.RunAssetStorageDiagnostics;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import ar.edu.uade.pfi.backend.service.RunAssetContentStorage;
import ar.edu.uade.pfi.backend.service.RunAssetSnapshotService;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class AiAssetDurableProxyControllerTest {
    @Test
    void getAssetServesPersistedPayloadBeforeCallingAiModule() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
        InMemoryStudyRepository repository = repositoryWithArtifact("multi-stored", "trace-stored", "run-sag-stored", "overlay.png");
        RunArtifact artifact = repository.findArtifactByRunPlaneAndName("run-sag-stored", "sagittal", "overlay.png").orElseThrow();
        FakeStorage storage = new FakeStorage();
        byte[] png = png(7);
        storage.store(artifact, png, sha256(png));
        repository.updateArtifactStorage(artifact.id(), "stored", "postgres_bytea", (long) png.length, sha256(png));

        mockMvc(ai, repository, storage, null)
            .perform(get("/api/ai/assets/run-sag-stored/sagittal/overlay.png"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-PFI-Asset-Source", "postgres"))
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
            .andExpect(header().string(HttpHeaders.ETAG, "\"" + sha256(png) + "\""))
            .andExpect(content().bytes(png));

        Mockito.verifyNoInteractions(ai);
    }

    @Test
    void getAssetBackfillsOnceWhenMetadataExistsWithoutPayload() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
        InMemoryStudyRepository repository = repositoryWithArtifact("multi-backfill", "trace-backfill", "run-sag-backfill", "input.png");
        FakeStorage storage = new FakeStorage();
        byte[] png = png(8);
        when(ai.getAsset("run-sag-backfill", "sagittal", "input.png"))
            .thenReturn(ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE).body(png));
        RunAssetSnapshotService snapshotService = new RunAssetSnapshotService(ai, repository, storage, null, 5L * 1024L * 1024L);

        mockMvc(ai, repository, storage, snapshotService)
            .perform(get("/api/ai/assets/run-sag-backfill/sagittal/input.png"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-PFI-Asset-Source", "ai-module-backfill"))
            .andExpect(content().bytes(png));

        RunArtifact artifact = repository.findArtifactByRunPlaneAndName("run-sag-backfill", "sagittal", "input.png").orElseThrow();
        assertEquals("stored", artifact.storageStatus());
    }

    @Test
    void getAssetReturnsStructuredUnavailableWhenBackfillMisses() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
        InMemoryStudyRepository repository = repositoryWithArtifact("multi-missing", "trace-missing", "run-sag-missing", "mask-preview.png");
        FakeStorage storage = new FakeStorage();
        when(ai.getAsset("run-sag-missing", "sagittal", "mask-preview.png"))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset no encontrado."));
        RunAssetSnapshotService snapshotService = new RunAssetSnapshotService(ai, repository, storage, null, 5L * 1024L * 1024L);

        mockMvc(ai, repository, storage, snapshotService)
            .perform(get("/api/ai/assets/run-sag-missing/sagittal/mask-preview.png"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ASSET_CONTENT_UNAVAILABLE"))
            .andExpect(jsonPath("$.runId").value("run-sag-missing"))
            .andExpect(jsonPath("$.plane").value("sagittal"))
            .andExpect(jsonPath("$.assetName").value("mask-preview.png"))
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true));
    }

    private MockMvc mockMvc(AiServiceOperations ai, InMemoryStudyRepository repository, FakeStorage storage, RunAssetSnapshotService snapshotService) {
        AiBackendService service = new AiBackendService(
            ai,
            Mockito.mock(ReviewStoreService.class),
            null,
            null,
            null,
            null,
            null,
            repository,
            storage,
            snapshotService
        );
        return MockMvcBuilders.standaloneSetup(new AiBackendController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private InMemoryStudyRepository repositoryWithArtifact(String multiplanarRunId, String traceId, String planeRunId, String assetName) {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        Instant now = Instant.parse("2026-07-26T13:00:00Z");
        Study study = repository.saveStudy(new Study(UUID.randomUUID().toString(), "CASE-" + multiplanarRunId, "ready", now, now));
        String studyRunId = UUID.randomUUID().toString();
        repository.saveRun(new StudyRun(
            studyRunId,
            study.id(),
            multiplanarRunId,
            traceId,
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "",
            "sha256:sag",
            "",
            planeRunId,
            "",
            Map.of("sagittal", Map.of(assetName, assetName)),
            Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true),
            List.of(new RunArtifact(UUID.randomUUID().toString(), studyRunId, planeRunId, "sagittal", assetName, "image/png", assetName, now)),
            "completed",
            "pending",
            "",
            null,
            "",
            now,
            now
        ));
        return repository;
    }

    private byte[] png(int marker) {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, (byte) marker};
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static class FakeStorage implements RunAssetContentStorage {
        private final Map<String, RunAssetContent> values = new ConcurrentHashMap<>();

        @Override
        public RunAssetContent store(RunArtifact artifact, byte[] content, String sha256) {
            return deleteOrReplace(artifact, content, sha256);
        }

        @Override
        public Optional<RunAssetContent> find(String artifactId) {
            return Optional.ofNullable(values.get(artifactId));
        }

        @Override
        public boolean exists(String artifactId) {
            return values.containsKey(artifactId);
        }

        @Override
        public RunAssetContent deleteOrReplace(RunArtifact artifact, byte[] content, String sha256) {
            RunAssetContent value = new RunAssetContent(artifact.id(), content, sha256, content.length, "postgres_bytea", Instant.now());
            values.put(artifact.id(), value);
            return value;
        }

        @Override
        public RunAssetStorageDiagnostics diagnostics() {
            long bytes = values.values().stream().mapToLong(RunAssetContent::sizeBytes).sum();
            return new RunAssetStorageDiagnostics("postgres_bytea", true, values.size(), bytes, 0);
        }
    }
}
