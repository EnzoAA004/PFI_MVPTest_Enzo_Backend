package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneMaskV2Dto(
    String id,
    String classKey,
    Double confidence,
    Boolean enabled,
    Boolean editable,
    String coordinateSpace,
    Map<String, Object> geometry
) {}
