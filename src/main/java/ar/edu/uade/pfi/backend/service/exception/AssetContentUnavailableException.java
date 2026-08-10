package ar.edu.uade.pfi.backend.service.exception;

public class AssetContentUnavailableException extends RuntimeException {
  private final String runId;
  private final String plane;
  private final String assetName;

  public AssetContentUnavailableException(String runId, String plane, String assetName) {
    super(
        "Asset durable no disponible. Reejecute la corrida si el AI Module ya descarto el output temporal.");
    this.runId = runId;
    this.plane = plane;
    this.assetName = assetName;
  }

  public String runId() {
    return runId;
  }

  public String plane() {
    return plane;
  }

  public String assetName() {
    return assetName;
  }
}
