package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StudyMetadataControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    @SuppressWarnings("unchecked")
    void putMetadataKeepsStatusUsesNullForMissingFieldsAndRejectsUnknownPriority() throws Exception {
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        StudyRunService studyRunService = new StudyRunService(repository);
        studyRunService.upsertStudyMetadata("CASE-READY-META", "ready", null);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StudyController(
                new StudyWorklistService(repository, false),
                studyRunService,
                mock(ProfessionalAccessAuditService.class),
                new RoleAuthorizationService(new AuditService(repository))
            ))
            .setControllerAdvice(new ApiExceptionHandler(new AuditService(repository)))
            .build();

        String response = mockMvc.perform(put("/api/studies/CASE-READY-META/metadata")
                .with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewPriority\":\"alta\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.study.priority").value("alta"))
            .andReturn()
            .getResponse()
            .getContentAsString();

        Map<String, Object> body = objectMapper.readValue(response, Map.class);
        Map<String, Object> study = (Map<String, Object>) body.get("study");
        assertNull(study.get("subjectRef"));
        assertNull(study.get("studyDate"));
        assertNull(study.get("modality"));
        assertNull(study.get("description"));
        org.junit.jupiter.api.Assertions.assertEquals("ready", repository.findStudyByCaseId("CASE-READY-META").orElseThrow().status());

        mockMvc.perform(put("/api/studies/CASE-READY-META/metadata")
                .with(reviewer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewPriority\":\"urgente\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REVIEW_PRIORITY"));
    }

    private static RequestPostProcessor reviewer() {
        return request -> {
            request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims("reviewer-id", "reviewer@example.test", "Reviewer", List.of("REVIEWER")));
            return request;
        };
    }
}
