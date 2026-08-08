package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiThreeDV2Dto(
    Boolean enabled,
    String status,
    Map<String, String> sourcePlaneRunIds,
    List<String> requiredInputs,
    List<AiPlaneAssetV2Dto> assets,
    Map<String, Object> reconstruction,
    List<String> warnings) {}
