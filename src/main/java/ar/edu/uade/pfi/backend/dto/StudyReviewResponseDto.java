package ar.edu.uade.pfi.backend.dto;

import java.util.List;
import java.util.Map;

public record StudyReviewResponseDto(
    String studyId,
    String caseId,
    String patientId,
    String studyDate,
    String modality,
    String bodyRegion,
    String reviewStatus,
    String modelKey,
    String modelVersion,
    AiOutputStateDto aiOutput,
    List<SeriesDto> series,
    List<MaskDto> masks,
    List<LandmarkDto> landmarks,
    List<MeasurementDto> measurements,
    Map<String, Object> metadata
) {
    public record AiOutputStateDto(
        String status,
        String label,
        String description,
        boolean realInferenceAvailable,
        boolean humanReviewRequired,
        boolean notClinicalDiagnosis
    ) {}

    public record SeriesDto(
        String id,
        String name,
        String plane,
        String sequence,
        int sliceCount,
        int selectedSlice,
        String imageUrl,
        String overlayUrl,
        double overlayOpacity,
        String status
    ) {}

    public record MaskDto(
        String id,
        String label,
        String className,
        String color,
        double confidence,
        boolean editable,
        boolean enabled,
        List<ContourDto> contours
    ) {}

    public record ContourDto(
        String seriesId,
        int sliceIndex,
        List<PointDto> points
    ) {}

    public record PointDto(double x, double y) {}

    public record LandmarkDto(
        String id,
        String label,
        String seriesId,
        int sliceIndex,
        double x,
        double y,
        boolean editable,
        String linkedMaskId
    ) {}

    public record MeasurementDto(
        String id,
        String label,
        String level,
        double value,
        double aiValue,
        Double reviewerValue,
        String unit,
        String source,
        double confidence,
        String status,
        boolean outlier,
        List<String> linkedLandmarks
    ) {}
}
