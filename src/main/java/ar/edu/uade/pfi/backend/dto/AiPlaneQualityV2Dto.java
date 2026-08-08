package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneQualityV2Dto(
    Integer maskCount,
    Integer landmarkCount,
    Integer measurementCount,
    Double meanConfidence,
    Double meanForegroundConfidence,
    Double foregroundRatio,
    /**
     * How many slices of the series have a persisted preview. May be lower than the series' slice
     * count: a run made before the preview catalog existed only kept the inferred slice's image,
     * and the viewer needs the difference to avoid promising a frame that was never written.
     */
    Integer slicePreviewCount,
    /**
     * Volume geometry in patient space: origin, direction, spacing, slice axis and frame of
     * reference.
     *
     * <p>Carried opaquely. It is what would let a viewer place one plane's slice on the other;
     * without it a reference line between sagittal and axial would be an invented coordinate. The
     * frame of reference travels with it because two studies have perfectly well-formed geometries
     * that are not comparable to each other.
     */
    java.util.Map<String, Object> volumeGeometry,
    /**
     * Raw per-slice pixel metadata: count, width, height, dtype, byteOrder, min, max. Carried
     * opaquely — it is what lets the viewer window real intensities instead of filtering brightness
     * over an already-windowed 8-bit PNG.
     */
    java.util.Map<String, Object> slicePixels,
    /**
     * Craniocaudal extent of each disc space: {@code [{level, worldTop, worldBottom}]}.
     *
     * <p>Carried opaquely, and only the sagittal plane publishes it — that is the plane where the
     * disc spaces are seen whole and can be counted up from the lumbosacral junction. The AI module
     * uses it to name the level of the axial slice, which the axial run cannot do on its own: its
     * model segments nothing countable, so every axial measurement used to arrive with no level and
     * land under "could not be assigned".
     */
    List<java.util.Map<String, Object>> discLevels,
    List<String> warnings) {}
