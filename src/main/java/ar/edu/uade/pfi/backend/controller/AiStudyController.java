package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/studies")
public class AiStudyController {
    private final StudyController studyController;

    public AiStudyController(StudyController studyController) {
        this.studyController = studyController;
    }

    @GetMapping("/demo-review")
    public StudyReviewResponseDto demoReview(HttpServletRequest request) {
        return studyController.demoReview(request);
    }
}
