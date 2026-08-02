package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneMaskV2Dto(
    String id,
    String classKey,
    Integer classId,
    /** Lumbar level of this instance, when it could be determined. */
    String level,
    /** Colour assigned to the instance so neighbouring structures stay distinguishable. */
    String color,
    Double confidence,
    Boolean enabled,
    Boolean editable,
    String coordinateSpace,
    Map<String, Object> geometry
) {}
