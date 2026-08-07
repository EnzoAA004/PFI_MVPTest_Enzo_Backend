package ar.edu.uade.pfi.backend.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public projection of a P10.7 disc-degenerative-findings envelope.
 *
 * <p>The frozen checkpoint's raw prediction carries {@code classification.probabilities}
 * per finding. That is needed internally -- upstream contract validation
 * ({@code DiscDegenerativeFindingsPersistenceService}), traceability, and audit -- and it
 * stays in what gets persisted. It was never meant to reach a Frontend consumer: a
 * probability array reads as a confidence score to present, and this product's contract
 * is {@code classification.label} plus mandatory human review, not a number to eyeball.
 *
 * <p>Used by both the live {@code POST /api/ai/v2/product/disc-degenerative-findings}
 * response and the persisted {@code GET /api/studies/{caseId}/runs} snapshot, so the two
 * paths cannot silently diverge on what they strip.
 */
public final class DiscDegenerativeFindingsPublicProjection {
    private DiscDegenerativeFindingsPublicProjection() {}

    /**
     * Returns a deep copy of {@code discDegenerativeFindings} with every
     * {@code classification.probabilities} entry removed. Never mutates {@code source} --
     * the persisted/internal object keeps its probabilities intact for validation,
     * traceability, and audit.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitize(Map<String, Object> source) {
        if (source == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>(source);
        Object findingsRaw = copy.get("findings");
        if (findingsRaw instanceof List<?> findings) {
            List<Object> sanitizedFindings = new ArrayList<>();
            for (Object item : findings) {
                sanitizedFindings.add(item instanceof Map<?, ?> finding
                    ? sanitizeFinding((Map<String, Object>) finding)
                    : item);
            }
            copy.put("findings", sanitizedFindings);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeFinding(Map<String, Object> finding) {
        Map<String, Object> copy = new LinkedHashMap<>(finding);
        Object classificationRaw = copy.get("classification");
        if (classificationRaw instanceof Map<?, ?> classification) {
            Map<String, Object> sanitizedClassification = new LinkedHashMap<>((Map<String, Object>) classification);
            sanitizedClassification.remove("probabilities");
            copy.put("classification", sanitizedClassification);
        }
        return copy;
    }
}
