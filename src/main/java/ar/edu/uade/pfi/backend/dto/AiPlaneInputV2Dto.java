package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPlaneInputV2Dto(
    String inputId,
    List<Integer> nativeShape,
    List<Integer> canonicalShape,
    Integer selectedSliceIndex
) {}
