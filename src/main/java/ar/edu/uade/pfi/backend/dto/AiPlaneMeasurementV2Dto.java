package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneMeasurementV2Dto(
    String id,
    String labelKey,
    Double value,
    String unit,
    Double confidence,
    String source,
    String status,
    String plane,
    String level,
    /**
     * What the measurement describes: a vertebral level or the study as a whole.
     *
     * <p>Separates "no level could be assigned" from "no level applies". Without it
     * both arrive as a null level and the reading panel files them under the same
     * heading, which in the second case blames the AI for a failure it did not have:
     * the canal area has no level because the mask runs the length of the spine.
     */
    String levelScope,
    /** Slice the measurement was taken on; without it a value cannot be located in the series. */
    Integer sliceIndex,
    String measurementBasis,
    List<String> linkedLandmarkIds
) {}
