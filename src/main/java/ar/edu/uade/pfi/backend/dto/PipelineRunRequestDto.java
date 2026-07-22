package ar.edu.uade.pfi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import java.util.Map;

public record PipelineRunRequestDto(
    @NotBlank String caseId,
    @NotBlank String plane,
    String modelKey,
    String inputPath,
    String inputId,
    Map<String, Object> metadata
) {
    public PipelineRunRequestDto {
        String normalizedPlane = plane == null ? "" : plane.trim().toLowerCase();
        modelKey = normalizeModelKey(modelKey, normalizedPlane);
        inputId = inputId == null ? null : inputId.trim();
        inputPath = normalizeInputPath(inputPath, caseId, metadata);
        metadata = metadata == null ? Map.of("source", "backend-default-contract") : metadata;
    }

    private static String normalizeModelKey(String modelKey, String plane) {
        String value = modelKey == null ? "" : modelKey.trim();
        if (value.isBlank()) {
            return defaultModelKeyForPlane(plane);
        }
        if ("baseline".equals(value)
            || "sagittal".equals(value)
            || "sagittal-final".equals(value)
            || "sagittal-spider-final-v1".equals(value)
            || "sagittal_spider_final_v1".equals(value)
            || "pfi-segmentation-sagittal-v1".equals(value)
            || "pfi_sagittal_v1".equals(value)
            || "sagittal-v1".equals(value)) {
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

    private static String normalizeInputPath(String inputPath, String caseId, Map<String, Object> metadata) {
        if (inputPath != null && !inputPath.isBlank()) {
            return inputPath;
        }
        Object inferenceMode = metadata == null ? null : metadata.get("inferenceMode");
        if (inferenceMode != null && "real_baseline".equals(inferenceMode.toString().trim().toLowerCase(Locale.ROOT))) {
            return "";
        }
        String safeCaseId = caseId == null || caseId.isBlank() ? "case-demo" : caseId.trim();
        return "demo/" + safeCaseId;
    }
}
