package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiplanarRunResponseDto(
    String status,
    String schemaVersion,
    @JsonAlias("multiplanarRunId")
    String runId,
    String traceId,
    String caseId,
    String workspaceMode,
    String requestedInferenceMode,
    String effectiveInferenceMode,
    PlanesDto planes,
    Map<String, Object> assets,
    Map<String, Object> threeD,
    Map<String, Object> quality,
    Map<String, Object> review,
    Map<String, Object> metadata,
    Boolean humanReviewRequired,
    Boolean notClinicalDiagnosis,
    Boolean degradedMode
) {
    public MultiplanarRunResponseDto(
        String runId,
        String traceId,
        String effectiveInferenceMode,
        PlanesDto planes,
        Map<String, Object> assets,
        Map<String, Object> review
    ) {
        this(null, null, runId, traceId, null, null, null, effectiveInferenceMode, planes, assets, null, null, review, null, true, true, false);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanesDto(
        @JsonAlias("sagital")
        PlaneDto sagittal,
        PlaneDto axial
    ) {
        public PlaneDto sagital() {
            return sagittal;
        }

        @JsonGetter("sagital")
        public PlaneDto legacySagital() {
            return sagittal;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlaneDto(
        String runId,
        String caseId,
        String plane,
        String modelKey,
        String modelVersion,
        String artifactHash,
        String status,
        String inferenceMode,
        String requestedInferenceMode,
        String effectiveInferenceMode,
        Boolean allowContractFallback,
        String inputId,
        Map<String, Object> modelArtifact,
        Map<String, Object> aiOutput,
        List<Map<String, Object>> series,
        List<Map<String, Object>> masks,
        List<Map<String, Object>> findings,
        List<Map<String, Object>> landmarks,
        Map<String, Object> measurements,
        Map<String, Object> evidence,
        Map<String, Object> quality,
        Map<String, Object> assets,
        Map<String, Object> metadata,
        Boolean humanReviewRequired,
        Boolean notClinicalDiagnosis,
        Boolean degradedMode
    ) {
        public PlaneDto(
            String runId,
            String plane,
            String modelKey,
            String status,
            String effectiveInferenceMode,
            Map<String, Object> modelArtifact,
            List<Map<String, Object>> findings,
            List<Map<String, Object>> landmarks,
            Map<String, Object> measurements,
            Map<String, Object> evidence,
            Map<String, Object> assets
        ) {
            this(
                runId,
                null,
                plane,
                modelKey,
                null,
                artifactHashFrom(modelArtifact),
                status,
                effectiveInferenceMode,
                null,
                effectiveInferenceMode,
                null,
                null,
                modelArtifact,
                null,
                null,
                null,
                findings,
                landmarks,
                measurements,
                evidence,
                null,
                assets,
                null,
                true,
                true,
                false
            );
        }

        public String normalizedEffectiveInferenceMode() {
            String explicit = text(effectiveInferenceMode);
            if (!explicit.isBlank()) return explicit;
            String direct = text(inferenceMode);
            if (!direct.isBlank()) return direct;
            String aiOutputMode = textFrom(aiOutput, "inferenceMode");
            if (!aiOutputMode.isBlank()) return aiOutputMode;
            String metadataMode = textFrom(metadata, "inferenceMode");
            return metadataMode.isBlank() ? null : metadataMode;
        }

        private static String artifactHashFrom(Map<String, Object> modelArtifact) {
            if (modelArtifact == null) return null;
            for (String key : List.of("artifactHash", "checkpointHash", "hash", "sha256")) {
                Object value = modelArtifact.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
            return null;
        }

        private static String textFrom(Map<String, Object> map, String key) {
            if (map == null) return "";
            Object value = map.get(key);
            return value == null ? "" : text(String.valueOf(value));
        }

        private static String text(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
