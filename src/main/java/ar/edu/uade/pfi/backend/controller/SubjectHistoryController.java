package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.service.PatientHistoryService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subjects")
public class SubjectHistoryController {
    private final PatientHistoryService patientHistoryService;
    private final ProfessionalAccessAuditService accessAuditService;

    public SubjectHistoryController(PatientHistoryService patientHistoryService, ProfessionalAccessAuditService accessAuditService) {
        this.patientHistoryService = patientHistoryService;
        this.accessAuditService = accessAuditService;
    }

    @GetMapping("/{subjectRef}/history")
    public Map<String, Object> history(@PathVariable String subjectRef, HttpServletRequest request) {
        accessAuditService.record(request, "access_subject_history", "Historial longitudinal de-identificado consultado subjectRef=" + subjectRef);
        return patientHistoryService.history(subjectRef);
    }
}
