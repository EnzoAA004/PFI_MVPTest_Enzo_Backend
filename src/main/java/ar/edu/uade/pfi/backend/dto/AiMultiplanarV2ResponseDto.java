package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiMultiplanarV2ResponseDto(
    String status,
    String schemaVersion,
    String multiplanarRunId,
    String traceId,
    String caseId,
    String workspaceMode,
    String requestedInferenceMode,
    String effectiveInferenceMode,
    Boolean synthetic,
    String fallbackReason,
    List<String> requestedPlanes,
    List<String> completedPlanes,
    AiMultiplanarV2PlanesDto planes,
    AiThreeDV2Dto threeD,
    AiWorkspaceQualityV2Dto quality,
    AiReviewPolicyV2Dto review,
    AiGovernanceV2Dto governance,
    AiReadinessV2Dto readiness
) {}
