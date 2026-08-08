package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import java.util.List;

public record StudyRowDto(
    String caseId,
    String subjectRef,
    String studyDate,
    String status,
    List<String> planes,
    String primaryPlane,
    String latestRunId,
    String modelKey,
    String modelStatus,
    String reviewStatus,
    String priority,
    String createdAt,
    String updatedAt,
    String dataOrigin) {
  public StudyRowDto(
      String caseId,
      String subjectRef,
      String plane,
      String studyDate,
      String modelKey,
      String modelStatus,
      String reviewStatus,
      String priority,
      String runId) {
    this(
        caseId,
        subjectRef,
        studyDate,
        "ready",
        plane == null || plane.isBlank() ? List.of() : List.of(plane),
        plane,
        runId,
        modelKey,
        modelStatus,
        reviewStatus,
        priority,
        null,
        null,
        "demo");
  }

  @JsonGetter("plane")
  public String plane() {
    return primaryPlane;
  }

  @JsonGetter("runId")
  public String runId() {
    return latestRunId;
  }
}
