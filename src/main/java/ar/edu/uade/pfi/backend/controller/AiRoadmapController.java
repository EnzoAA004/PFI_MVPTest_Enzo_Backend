package ar.edu.uade.pfi.backend.controller;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiRoadmapController {
    @GetMapping("/roadmap")
    public Map<String, Object> roadmap() {
        return Map.of(
            "status", "roadmap_ready",
            "currentMode", "contract",
            "completed", List.of("traceability", "readiness", "artifact_verification", "report_index", "human_review"),
            "pending", List.of("real_model_artifact", "quantitative_dataset_evaluation", "professional_validation_round"),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }
}
