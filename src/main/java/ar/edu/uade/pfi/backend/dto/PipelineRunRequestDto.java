package ar.edu.uade.pfi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record PipelineRunRequestDto(
    @NotBlank String caseId,
    @NotBlank String plane,
    String modelKey,
    String inputPath,
    Map<String, Object> metadata
) {
    public PipelineRunRequestDto {
        String normalizedPlane = plane == null ? "" : plane.trim().toLowerCase();
        modelKey = normalizeModelKey(modelKey, normalizedPlane);
        inputPath = normalizeInputPath(inputPath, caseId);
        metadata = metadata == null ? Map.of("source", "backend-default-contract") : metadata;
    }

    private static String normalizeModelKey(String modelKey, String plane) {
        String value = modelKey == null ? "" : modelKey.trim();
        if (value.isBlank()) {
            return defaultModelKeyForPlane(plane);
        }
        if ("pfi-segmentation-sagittal-v1".equals(value) || "pfi_sagittal_v1".equals(value) || "sagittal-v1".equals(value)) {
            return "sagittal_spider";
        }
        if ("pfi-segmentation-axial-v1".equals(value) || "pfi_axial_v1".equals(value) || "axial-v1".equals(value)) {
            return "axial_t2_alkafri";
        }
        return value;
    }

    private static String defaultModelKeyForPlane(String plane) {
        return "axial".equals(plane) ? "axial_t2_alkafri" : "sagittal_spider";
    }

    private static String normalizeInputPath(String inputPath, String caseId) {
        if (inputPath != null && !inputPath.isBlank()) {
            return inputPath;
        }
        String safeCaseId = caseId == null || caseId.isBlank() ? "case-demo" : caseId.trim();
        return "demo/" + safeCaseId;
    }
}
