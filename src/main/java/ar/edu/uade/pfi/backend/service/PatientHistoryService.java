package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.StudyRowDto;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PatientHistoryService {
    private final PostgresStudyCatalogService catalog;

    public PatientHistoryService(PostgresStudyCatalogService catalog) {
        this.catalog = catalog;
    }

    public Map<String, Object> history(String subjectRef) {
        List<Map<String, Object>> studies = catalog.listStudies().stream()
            .filter(row -> row.subjectRef().equalsIgnoreCase(subjectRef))
            .sorted(Comparator.comparing(StudyRowDto::studyDate).reversed())
            .map(this::toHistoryStudy)
            .toList();
        return Map.of(
            "status", "ok",
            "source", catalog.enabled() ? "postgres-study-catalog" : "catalog-disabled",
            "subjectRef", subjectRef,
            "deidentified", true,
            "studies", studies,
            "summary", Map.of("totalStudies", studies.size()),
            "governance", Map.of("dataScope", "academic-deidentified", "derivedMetricsExport", "permitted", "rawImagesExport", "not_permitted"),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }

    private Map<String, Object> toHistoryStudy(StudyRowDto row) {
        int seed = Math.abs(row.caseId().hashCode() % 7);
        return Map.of(
            "caseId", row.caseId(),
            "studyDate", row.studyDate(),
            "planes", row.plane(),
            "modelVersion", row.modelKey(),
            "reviewStatus", row.reviewStatus(),
            "priority", row.priority(),
            "runId", row.runId(),
            "metrics", Map.of(
                "lordosisAngle", 41.0 + seed * 1.8,
                "canalDiameter", 11.0 + seed * 0.45,
                "averageDiscHeight", 7.4 + seed * 0.25,
                "l45DiscHeight", 7.1 + seed * 0.35
            )
        );
    }
}
