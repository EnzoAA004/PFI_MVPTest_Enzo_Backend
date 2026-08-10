package ar.edu.uade.pfi.backend.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import ar.edu.uade.pfi.backend.service.RunReviewService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyReviewAdapterControllerTest {
  @Test
  void legacyPatchPersistsThroughDomainModelAndDoesNotCallReviewStoreUpdate() throws Exception {
    InMemoryStudyRepository repository = new InMemoryStudyRepository();
    StudyRunService studyRunService = new StudyRunService(repository);
    Study study = studyRunService.createStudy("CASE-LEGACY", "created");
    studyRunService.createRunWithId(
        java.util.UUID.randomUUID().toString(),
        study,
        "multi-legacy",
        "trace-legacy",
        "real_baseline",
        "real_baseline",
        "sagittal_spider",
        "",
        "sha256:sag",
        "",
        "run-sag-legacy",
        "",
        Map.of("workspace", "workspace.json"),
        Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true),
        List.of(),
        "completed",
        "pending",
        "",
        null,
        "");
    ReviewStoreService legacyStore = mock(ReviewStoreService.class);
    AiBackendService service =
        new AiBackendService(
            mock(AiServiceOperations.class),
            legacyStore,
            null,
            null,
            null,
            null,
            null,
            repository,
            null,
            null,
            new RunReviewService(repository));
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new AiLegacyReviewController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mockMvc
        .perform(
            patch("/api/ai/review/multi-legacy")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"status":"aceptado","reviewer":"dra-demo","notes":"Aceptado desde endpoint legacy."}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value("multi-legacy"))
        .andExpect(jsonPath("$.status").value("aceptado"))
        .andExpect(jsonPath("$.notes").value("Aceptado desde endpoint legacy."));

    verify(legacyStore, never())
        .updateReview(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    org.junit.jupiter.api.Assertions.assertEquals(
        "accepted",
        repository.findRunByMultiplanarRunId("multi-legacy").orElseThrow().reviewStatus());
  }
}
