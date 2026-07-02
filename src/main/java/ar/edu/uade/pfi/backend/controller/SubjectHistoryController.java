package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.service.PatientHistoryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subjects")
public class SubjectHistoryController {
    private final PatientHistoryService patientHistoryService;

    public SubjectHistoryController(PatientHistoryService patientHistoryService) {
        this.patientHistoryService = patientHistoryService;
    }

    @GetMapping("/{subjectRef}/history")
    public Map<String, Object> history(@PathVariable String subjectRef) {
        return patientHistoryService.history(subjectRef);
    }
}
