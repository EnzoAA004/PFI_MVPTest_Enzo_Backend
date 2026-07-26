package ar.edu.uade.pfi.backend.domain;

public record RunAssetStorageDiagnostics(
    String mode,
    boolean available,
    long storedAssetCount,
    long storedBytes,
    long missingPayloadCount
) {
}
