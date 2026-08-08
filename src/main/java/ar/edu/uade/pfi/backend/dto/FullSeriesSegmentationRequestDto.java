package ar.edu.uade.pfi.backend.dto;

/**
 * Public/backend contract for the P10.9 full-series pass.
 *
 * <p>The AI Module only segments series for which a compatible trained segmentation model exists.
 * "full series" means every slice of that supported analyzable series, not every series in the
 * uploaded study.
 */
public record FullSeriesSegmentationRequestDto(
    String caseId, String inputId, String plane, String modelKey) {}
