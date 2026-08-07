package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.StudyRun;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persists the P10.7 prediction as an immutable extension of an existing run snapshot.
 *
 * <p>Reviewer decisions remain outside this payload. The AI prediction is written once;
 * an identical retry is idempotent and a different second prediction conflicts instead
 * of silently rewriting what the professional originally reviewed.
 */
@Service
public class DiscDegenerativeFindingsPersistenceService {
    public static final String SCHEMA_VERSION = "pfi.disc-degenerative-findings.v1";
    public static final String FROZEN_MODEL_SHA256 = "16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293";

    private static final Set<String> VALID_TASKS = Set.of(
        "pfirrmann_grade",
        "modic_change",
        "upper_endplate_change",
        "lower_endplate_change",
        "spondylolisthesis",
        "disc_herniation",
        "disc_narrowing",
        "disc_bulging"
    );
    private static final Set<String> VALID_LEVELS = Set.of("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");
    private static final Set<String> VALID_DEPLOYMENT = Set.of("supported_internal", "experimental", "not_product_supported");

    private final StudyRunService studyRunService;

    public DiscDegenerativeFindingsPersistenceService(StudyRunService studyRunService) {
        this.studyRunService = studyRunService;
    }

    public Map<String, Object> persistImmutable(String multiplanarRunId, Map<String, Object> upstream) {
        String runId = requirePublicText(multiplanarRunId, "multiplanarRunId");
        StudyRun run = studyRunService.findRunByMultiplanarRunId(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Corrida no encontrada."));

        Map<String, Object> disc = validatedDiscEnvelope(upstream);
        Map<String, Object> current = new LinkedHashMap<>(run.metricsSnapshot() == null ? Map.of() : run.metricsSnapshot());
        Object previous = current.get("discDegenerativeFindings");
        if (previous != null) {
            if (previous.equals(disc)) {
                return current;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La prediccion P10.7 de esta corrida es inmutable.");
        }

        current.put("discDegenerativeFindings", disc);
        current.put("discDegenerativeGovernance", Map.of(
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true,
            "autonomousDiagnosis", false,
            "predictionImmutable", true,
            "reviewStoredSeparately", true
        ));
        studyRunService.updateRunMetricsSnapshot(run.multiplanarRunId(), current);
        return current;
    }

    private Map<String, Object> validatedDiscEnvelope(Map<String, Object> upstream) {
        if (upstream == null
            || !Boolean.TRUE.equals(upstream.get("humanReviewRequired"))
            || !Boolean.TRUE.equals(upstream.get("notClinicalDiagnosis"))
            || !Boolean.FALSE.equals(upstream.get("autonomousDiagnosis"))) {
            throw invalidUpstream();
        }
        Object raw = upstream.get("discDegenerativeFindings");
        if (!(raw instanceof Map<?, ?> rawMap)) throw invalidUpstream();
        Map<String, Object> disc = stringMap(rawMap);
        if (!SCHEMA_VERSION.equals(disc.get("schemaVersion"))) throw invalidUpstream();
        Object findingsRaw = disc.get("findings");
        if (!(findingsRaw instanceof List<?> findings) || findings.isEmpty()) throw invalidUpstream();

        for (Object item : findings) {
            if (!(item instanceof Map<?, ?> findingRaw)) throw invalidUpstream();
            validateFinding(stringMap(findingRaw));
        }
        return deepReadOnlyCopy(disc);
    }

    private void validateFinding(Map<String, Object> finding) {
        requireUpstreamText(finding.get("findingId"));
        String type = requireUpstreamText(finding.get("findingType"));
        if (!VALID_TASKS.contains(type)) throw invalidUpstream();

        Map<String, Object> anatomy = requireMap(finding.get("anatomy"));
        if (!VALID_LEVELS.contains(requireUpstreamText(anatomy.get("level")))) throw invalidUpstream();

        Map<String, Object> classification = requireMap(finding.get("classification"));
        requireUpstreamText(classification.get("label"));
        Object probabilitiesRaw = classification.get("probabilities");
        if (!(probabilitiesRaw instanceof Map<?, ?> probabilities) || probabilities.isEmpty()) throw invalidUpstream();
        double sum = 0.0d;
        for (Object value : probabilities.values()) {
            if (!(value instanceof Number number)) throw invalidUpstream();
            double probability = number.doubleValue();
            if (!Double.isFinite(probability) || probability < 0.0d || probability > 1.0d) throw invalidUpstream();
            sum += probability;
        }
        if (Math.abs(sum - 1.0d) > 0.01d) throw invalidUpstream();

        Map<String, Object> evidence = requireMap(finding.get("evidence"));
        if (!VALID_DEPLOYMENT.contains(requireUpstreamText(evidence.get("deploymentStatus")))) throw invalidUpstream();

        Map<String, Object> localization = requireMap(finding.get("localization"));
        if (!"segmentation_derived_disc_level".equals(localization.get("source"))) throw invalidUpstream();
        if (!Boolean.TRUE.equals(localization.get("researchOnly"))) throw invalidUpstream();
        if (!Boolean.FALSE.equals(localization.get("automaticAnatomicalLocalizationValidated"))) throw invalidUpstream();

        Map<String, Object> model = requireMap(finding.get("model"));
        if (!FROZEN_MODEL_SHA256.equals(model.get("modelSha256"))) throw invalidUpstream();

        Map<String, Object> review = requireMap(finding.get("review"));
        if (!Boolean.TRUE.equals(review.get("required"))) throw invalidUpstream();
        if (!"pending".equals(review.get("status"))) throw invalidUpstream();
        if (!Boolean.TRUE.equals(finding.get("notClinicalDiagnosis"))) throw invalidUpstream();
    }

    private Map<String, Object> requireMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalidUpstream();
        return stringMap(map);
    }

    private String requirePublicText(Object value, String field) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " es obligatorio.");
        return text;
    }

    private String requireUpstreamText(Object value) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isBlank()) throw invalidUpstream();
        return text;
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalidUpstream();
            result.put(key, entry.getValue());
        }
        return result;
    }

    /** Read-only deep copy that deliberately preserves JSON null values such as anatomy.side. */
    private Map<String, Object> deepReadOnlyCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepReadOnlyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private Object deepReadOnlyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepReadOnlyCopy(stringMap(map));
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(deepReadOnlyValue(item));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private ResponseStatusException invalidUpstream() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "El modulo de IA devolvio un contrato P10.7 invalido.");
    }
}
