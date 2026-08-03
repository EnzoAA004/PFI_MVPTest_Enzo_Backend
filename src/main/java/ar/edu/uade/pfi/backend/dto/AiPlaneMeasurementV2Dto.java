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
    /**
     * The two endpoints the measurement was taken between, in the plane's
     * coordinateSpace.
     *
     * <p>It is what gives the number a place. Without it a reviewer sees "37.37 mm"
     * and has no way to know from where to where, so they can neither verify the
     * measurement nor correct it. Empty when the magnitude is not a distance.
     */
    List<java.util.Map<String, Object>> points,
    /**
     * Derived from other structures' geometry rather than from a mask of its own.
     *
     * <p>Segmental angle and listhesis come from the axes of two neighbouring vertebral
     * bodies. They are the two measurements a spine report carries that the model was
     * not trained to produce, so they travel flagged: the viewer shows them on their own
     * layer and the decision to use them stays with the reader.
     */
    Boolean experimental,
    /** Second magnitude when the measurement has one, such as the Meyerding grade. */
    String detail,
    /** Slice the measurement was taken on; without it a value cannot be located in the series. */
    Integer sliceIndex,
    String measurementBasis,
    List<String> linkedLandmarkIds
) {}
