package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.ReviewSnapshotDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.StudyRowDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StudyWorklistService {
    private final ReviewStoreService reviewStoreService;

    public StudyWorklistService(ReviewStoreService reviewStoreService) {
        this.reviewStoreService = reviewStoreService;
    }

    public Map<String, Object> listStudies() {
        List<StudyRowDto> rows = demoRows().stream().map(this::withPersistedReview).toList();
        long pending = rows.stream().filter(row -> "pendiente".equals(row.reviewStatus()) || "observado".equals(row.reviewStatus())).count();
        long completed = rows.stream().filter(row -> "aceptado".equals(row.reviewStatus())).count();
        long flagged = rows.stream().filter(row -> "alta".equals(row.priority()) || "observado".equals(row.reviewStatus())).count();
        return Map.of(
            "status", "ok",
            "source", "backend-demo-worklist",
            "items", rows,
            "summary", Map.of("total", rows.size(), "pending", pending, "completed", completed, "flagged", flagged),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }

    public Map<String, Object> getStudy(String caseId) {
        StudyRowDto row = findStudy(caseId);
        return Map.of(
            "status", "ok",
            "study", row,
            "review", reviewStoreService.findOrDefault(row.runId()),
            "measurements", reviewStoreService.findMeasurements(row.runId()),
            "runs", runsFor(row),
            "auditTrail", reviewStoreService.auditTrail(),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }

    public Map<String, Object> getStudyRuns(String caseId) {
        StudyRowDto row = findStudy(caseId);
        return Map.of(
            "status", "ok",
            "caseId", row.caseId(),
            "runs", runsFor(row),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }

    public Map<String, Object> createDemoStudy() {
        return getStudy("CASE-DEMO-0142");
    }

    private StudyRowDto findStudy(String caseId) {
        return demoRows().stream()
            .filter(item -> item.caseId().equalsIgnoreCase(caseId))
            .findFirst()
            .map(this::withPersistedReview)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
    }

    private List<Map<String, Object>> runsFor(StudyRowDto row) {
        ReviewStatusDto review = reviewStoreService.findOrDefault(row.runId());
        return List.of(Map.of(
            "runId", row.runId(),
            "caseId", row.caseId(),
            "plane", row.plane(),
            "modelKey", row.modelKey(),
            "modelStatus", row.modelStatus(),
            "reviewStatus", review.status(),
            "measurementCount", reviewStoreService.findMeasurements(row.runId()).size()
        ));
    }

    private StudyRowDto withPersistedReview(StudyRowDto row) {
        Optional<ReviewStatusDto> persisted = findPersistedReview(row.runId());
        if (persisted.isEmpty()) return row;
        ReviewStatusDto review = persisted.get();
        return new StudyRowDto(row.caseId(), row.subjectRef(), row.plane(), row.studyDate(), row.modelKey(), row.modelStatus(), review.status(), row.priority(), row.runId());
    }

    private Optional<ReviewStatusDto> findPersistedReview(String runId) {
        ReviewSnapshotDto snapshot = reviewStoreService.snapshot();
        if (snapshot == null || snapshot.reviews() == null) return Optional.empty();
        return snapshot.reviews().stream().filter(review -> runId.equals(review.runId())).findFirst();
    }

    private List<StudyRowDto> demoRows() {
        List<StudyRowDto> rows = new ArrayList<>();
        rows.add(new StudyRowDto("CASE-DEMO-0142", "PAT-0087", "sagittal", "2026-07-01", "sagittal_spider", "AI-ready", "pendiente", "media", "89f224fa2fcce967"));
        rows.add(new StudyRowDto("CASE-0110", "PAT-0087", "axial", "2026-05-19", "axial_t2_alkafri", "AI-ready", "observado", "alta", "run-case-0110"));
        rows.add(new StudyRowDto("CASE-0089", "PAT-0214", "sagittal", "2026-04-04", "sagittal_spider", "Inference pending", "pendiente", "media", "run-case-0089"));
        rows.add(new StudyRowDto("CASE-0061", "PAT-0332", "sagittal", "2026-02-12", "sagittal_spider", "AI-ready", "aceptado", "baja", "run-case-0061"));
        return rows;
    }
}
