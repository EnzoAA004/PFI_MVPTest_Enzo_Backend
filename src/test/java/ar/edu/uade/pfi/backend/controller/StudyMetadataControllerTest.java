package ar.edu.uade.pfi.backend.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import ar.edu.uade.pfi.backend.service.StudyWorklistService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StudyMetadataControllerTest {
    @Test
    void putMetadataRequiresProfessionalAndUpdatesExistingStudy() throws Exception {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        StudyRunService studyRunService = new StudyRunService(repository);
        studyRunService.upsertStudyMetadata("CASE-META", "created", null);
        AuditService auditService = new AuditService(repository);
        ProfessionalAccessAuditService accessAudit = mock(ProfessionalAccessAuditService.class);
        doNothing().when(accessAudit).record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StudyController(
                new StudyWorklistService(repository, false),
                studyRunService,
                accessAudit,
                new RoleAuthorizationService(auditService)
            ))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();

        mockMvc.perform(put("/api/studies/CASE-META/metadata")
                .with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "subjectRef": "SPIDER-101",
                      "studyDate": "2026-07-26",
                      "modality": "MRI",
                      "description": "RM lumbar sagital T2",
                      "reviewPriority": "medium"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.study.caseId").value("CASE-META"))
            .andExpect(jsonPath("$.study.subjectRef").value("SPIDER-101"))
            .andExpect(jsonPath("$.study.studyDate").value("2026-07-26"))
            .andExpect(jsonPath("$.study.priority").value("media"))
            .andExpect(jsonPath("$.humanReviewRequired").value(true));

        mockMvc.perform(put("/api/studies/CASE-META/metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectRef\":\"SPIDER-101\"}"))
            .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor reviewer() {
        return request -> {
            request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("reviewer-id", "reviewer@example.test", "Reviewer", List.of("REVIEWER")));
            return request;
        };
    }
}
