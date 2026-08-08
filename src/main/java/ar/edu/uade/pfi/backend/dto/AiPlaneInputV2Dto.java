package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneInputV2Dto(
    String inputId,
    String format,
    Long sizeBytes,
    List<Integer> nativeShape,
    List<Integer> canonicalShape,
    String orientationTransform,
    List<Double> spacingXyzMm,
    List<Double> canonicalAxisSpacingMm,
    Integer selectedSliceIndex,
    Integer sliceCount,
    Integer selectedAxis,
    List<Double> inPlaneSpacingMm) {}
