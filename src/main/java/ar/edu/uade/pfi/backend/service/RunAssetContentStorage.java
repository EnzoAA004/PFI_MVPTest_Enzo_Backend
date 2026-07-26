package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.RunAssetContent;
import ar.edu.uade.pfi.backend.domain.RunAssetStorageDiagnostics;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import java.util.Optional;

public interface RunAssetContentStorage {
    RunAssetContent store(RunArtifact artifact, byte[] content, String sha256);

    Optional<RunAssetContent> find(String artifactId);

    boolean exists(String artifactId);

    RunAssetContent deleteOrReplace(RunArtifact artifact, byte[] content, String sha256);

    RunAssetStorageDiagnostics diagnostics();
}
