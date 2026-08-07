package ar.edu.uade.pfi.backend.dto;

/** One independently segmented sagittal source for P10.7. */
public record DiscSegmentationSourceDto(
    String role,
    String inputId,
    String segmentationRunId
) {}
