package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiWorkspaceQualityV2Dto(
    Integer planeCount,
    Integer maskCount,
    Integer landmarkCount,
    Integer measurementCount,
    Map<String, AiPlaneQualityV2Dto> byPlane) {}
