package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiThreeDV2Dto(
    Boolean enabled,
    String status,
    Map<String, String> sourcePlaneRunIds,
    List<String> requiredInputs
) {}
