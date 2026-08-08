package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiStructuredErrorV2Dto(
    String status,
    String schemaVersion,
    String code,
    String message,
    String traceId,
    String caseId,
    List<String> requestedPlanes,
    Map<String, Object> details,
    AiGovernanceV2Dto governance) {}
