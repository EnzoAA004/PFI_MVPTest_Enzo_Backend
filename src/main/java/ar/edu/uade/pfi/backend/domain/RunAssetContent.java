package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

public record RunAssetContent(
    String artifactId,
    byte[] content,
    String sha256,
    long sizeBytes,
    String storageKind,
    Instant storedAt
) {
    public RunAssetContent {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(storageKind, "storageKind");
        Objects.requireNonNull(storedAt, "storedAt");
        if (content.length == 0 || sizeBytes <= 0) {
            throw new IllegalArgumentException("asset content cannot be empty");
        }
    }
}
