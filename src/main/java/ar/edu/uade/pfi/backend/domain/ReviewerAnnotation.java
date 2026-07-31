package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A reviewer's own annotation on a run: a measurement they took, a marker they
 * placed, or a note they wrote.
 *
 * <p>Distinct from {@link MeasurementCorrection}, which is always an AI value the
 * reviewer changed and therefore always has a measurementId plus a before/after
 * pair. An annotation has neither — it is geometry and text the reviewer produced,
 * and it may not correspond to any measurement the model emitted.
 *
 * <p>{@code scope} decides where the annotation is drawn, so the invariants that
 * make it locatable are enforced here as well as by the table's CHECK constraints:
 * a slice-scoped annotation without a plane and a slice index cannot be placed on
 * any image, and a level-scoped one without a level cannot be filed under any
 * vertebral level.
 *
 * <p>{@code points} are in the normalised 0..256 basis shared with masks and
 * landmarks — a frame-relative space, not PNG pixels, so the geometry stays aligned
 * regardless of the resolution the slice is rendered at.
 */
public record ReviewerAnnotation(
    String id,
    String studyRunId,
    String scope,
    String kind,
    String plane,
    String seriesId,
    Integer sliceIndex,
    String level,
    List<Map<String, Object>> points,
    Double value,
    String unit,
    String text,
    String author,
    Instant createdAt
) {
    private static final List<String> SCOPES = List.of("study", "level", "slice");
    private static final List<String> KINDS = List.of("measurement", "marker", "note");
    private static final List<String> PLANES = List.of("sagittal", "axial");
    private static final List<String> UNITS = List.of("mm", "px");

    public ReviewerAnnotation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyRunId, "studyRunId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(createdAt, "createdAt");
        require(SCOPES.contains(scope), "scope invalido: " + scope);
        require(KINDS.contains(kind), "kind invalido: " + kind);
        require(plane == null || PLANES.contains(plane), "plane invalido: " + plane);
        require(unit == null || UNITS.contains(unit), "unit invalida: " + unit);
        require(!"slice".equals(scope) || (plane != null && sliceIndex != null),
            "una anotacion de corte necesita plano e indice de corte");
        require(!"level".equals(scope) || level != null,
            "una anotacion de nivel necesita el nivel");
        require(sliceIndex == null || sliceIndex >= 0, "sliceIndex no puede ser negativo");
        points = points == null ? List.of() : List.copyOf(points);
        text = text == null ? "" : text;
        author = author == null ? "" : author;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
