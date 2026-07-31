package ar.edu.uade.pfi.backend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The invariants that keep an unplaceable annotation out of the database. They live
 * in the record and not only in the table's CHECK constraints so that the in-memory
 * repository — which the tests and the demo mode use — rejects the same payloads the
 * Postgres one would.
 */
class ReviewerAnnotationTest {
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");

    private ReviewerAnnotation annotation(String scope, String plane, Integer sliceIndex, String level) {
        return new ReviewerAnnotation(
            "id-1", "run-1", scope, "note", plane, "series-1", sliceIndex, level,
            List.of(Map.of("x", 1.0, "y", 2.0)), null, null, "texto", "Revisor", NOW
        );
    }

    @Test
    void sliceScopedAnnotationRequiresPlaneAndSliceIndex() {
        assertThatThrownBy(() -> annotation("slice", null, 7, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("plano");
        assertThatThrownBy(() -> annotation("slice", "sagittal", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("corte");
    }

    @Test
    void levelScopedAnnotationRequiresLevel() {
        assertThatThrownBy(() -> annotation("level", null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nivel");
        assertThat(annotation("level", null, null, "L4-L5").level()).isEqualTo("L4-L5");
    }

    @Test
    void studyScopedAnnotationNeedsNoPlacement() {
        assertThat(annotation("study", null, null, null).scope()).isEqualTo("study");
    }

    @Test
    void rejectsUnknownScopeKindPlaneAndUnit() {
        assertThatThrownBy(() -> annotation("series", null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewerAnnotation(
            "id", "run", "study", "diagnosis", null, null, null, null, List.of(), null, null, "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> annotation("study", "coronal", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewerAnnotation(
            "id", "run", "study", "measurement", null, null, null, null, List.of(), 4.0, "cm", "", "", NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeSliceIndexIsRejected() {
        assertThatThrownBy(() -> annotation("slice", "sagittal", -1, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pointsAndTextDefaultInsteadOfBeingNull() {
        ReviewerAnnotation annotation = new ReviewerAnnotation(
            "id", "run", "study", "note", null, null, null, null, null, null, null, null, null, NOW
        );
        assertThat(annotation.points()).isEmpty();
        assertThat(annotation.text()).isEmpty();
        assertThat(annotation.author()).isEmpty();
    }

    @Test
    void pointsAreDefensivelyCopied() {
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        points.add(Map.of("x", 1.0, "y", 2.0));
        ReviewerAnnotation annotation = new ReviewerAnnotation(
            "id", "run", "study", "measurement", null, null, null, null, points, 3.0, "mm", "", "", NOW
        );
        points.clear();
        assertThat(annotation.points()).hasSize(1);
    }
}
