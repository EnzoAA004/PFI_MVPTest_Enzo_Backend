package ar.edu.uade.pfi.backend.service;

import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.controller.AiMultiplanarController;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyRunDetailDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DegenerativeFindingsPersistenceAndPresentationTest {
  @SuppressWarnings("unchecked")
  @Test
  void persistsAndReopensDegenerativeFindingsAsRootSnapshotWithoutMeasurementsConversion() {
    InMemoryStudyRepository repository = new InMemoryStudyRepository();
    StudyRunService studyRunService = new StudyRunService(repository);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService);
    StudyWorklistService worklist = new StudyWorklistService(repository, false);

    persistence.persistSuccessfulRun(request(), canonicalResponse());

    StudyRunDetailDto run = worklist.getStudyRuns("CASE-DEG-BE").runs().get(0);
    Map<String, Object> canonicalRun = run.canonicalRun();
    Map<String, Object> snapshot = run.metricsSnapshot();

    assertTrue(snapshot.containsKey("degenerativeFindings"));
    assertTrue(canonicalRun.containsKey("degenerativeFindings"));
    Map<String, Object> degenerativeFindings =
        (Map<String, Object>) canonicalRun.get("degenerativeFindings");
    assertEquals("pfi.degenerative-findings.v1", degenerativeFindings.get("schemaVersion"));
    assertEquals(1, ((List<?>) degenerativeFindings.get("findings")).size());
    assertFalse(snapshot.containsKey("measurements"));
    assertFalse(run.measurementsByPlane().containsKey("sagittal"));
    assertFalse(run.measurementsByPlane().containsKey("axial"));
  }

  @Test
  void absentDegenerativeFindingsStaysAbsentAfterPersistenceAndReopen() {
    InMemoryStudyRepository repository = new InMemoryStudyRepository();
    StudyRunService studyRunService = new StudyRunService(repository);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService);
    StudyWorklistService worklist = new StudyWorklistService(repository, false);

    persistence.persistSuccessfulRun(request(), canonicalResponse(Map.of()));

    StudyRunDetailDto run = worklist.getStudyRuns("CASE-DEG-BE").runs().get(0);
    assertFalse(run.metricsSnapshot().containsKey("degenerativeFindings"));
    assertFalse(run.canonicalRun().containsKey("degenerativeFindings"));
    assertTrue(run.measurementsByPlane().isEmpty());
  }

  @SuppressWarnings("unchecked")
  @Test
  void emptyDegenerativeFindingsCollectionIsPreservedAfterPersistenceAndReopen() {
    InMemoryStudyRepository repository = new InMemoryStudyRepository();
    StudyRunService studyRunService = new StudyRunService(repository);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService);
    StudyWorklistService worklist = new StudyWorklistService(repository, false);
    Map<String, Object> emptyFindings =
        Map.of("schemaVersion", "pfi.degenerative-findings.v1", "findings", List.of());

    persistence.persistSuccessfulRun(request(), canonicalResponse(emptyFindings));

    StudyRunDetailDto run = worklist.getStudyRuns("CASE-DEG-BE").runs().get(0);
    Map<String, Object> root = (Map<String, Object>) run.canonicalRun().get("degenerativeFindings");
    assertEquals("pfi.degenerative-findings.v1", root.get("schemaVersion"));
    assertEquals(Collections.emptyList(), root.get("findings"));
    assertTrue(run.measurementsByPlane().isEmpty());
  }

  @Test
  void multiplanarRunEndpointReturnsDegenerativeFindingsRootField() throws Exception {
    AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
    when(ai.runMultiplanar(any())).thenReturn(canonicalResponse());
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai, null, null))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mockMvc
        .perform(
            post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "caseId": "CASE-DEG-BE",
                      "sagittalInputId": "input-sag-deg",
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.degenerativeFindings.schemaVersion").value("pfi.degenerative-findings.v1"))
        .andExpect(
            jsonPath("$.degenerativeFindings.findings[0].findingType")
                .value("central_canal_stenosis"))
        .andExpect(
            jsonPath("$.degenerativeFindings.findings[0].classification.label").value("moderate"))
        .andExpect(jsonPath("$.planes.sagittal.measurements.degenerativeFindings").doesNotExist())
        .andExpect(jsonPath("$", hasKey("degenerativeFindings")));
  }

  private MultiplanarRunRequestDto request() {
    return new MultiplanarRunRequestDto(
        "CASE-DEG-BE",
        "input-sag-deg",
        null,
        null,
        null,
        "sagittal_spider",
        null,
        false,
        Map.of("inferenceMode", "real_baseline"));
  }

  private CanonicalMultiplanarRun canonicalResponse() {
    return canonicalResponse(degenerativeFindings());
  }

  private CanonicalMultiplanarRun canonicalResponse(Map<String, Object> degenerativeFindings) {
    Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
    planes.put(
        "sagittal",
        new CanonicalPlaneRun(
            "run-sag-deg",
            "sagittal",
            "completed",
            "real_baseline",
            false,
            null,
            Map.of("modelKey", "sagittal_spider", "artifactHash", "sha256:sag"),
            Map.of("inputId", "input-sag-deg"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of()));
    return new CanonicalMultiplanarRun(
        "completed",
        "pfi.multiplanar-run.v2",
        "multi-deg-be",
        "trace-deg-be",
        "CASE-DEG-BE",
        "sagittal_only",
        "real_baseline",
        "real_baseline",
        List.of("sagittal"),
        List.of("sagittal"),
        false,
        null,
        Map.of("sagittal", true),
        planes,
        Map.of(),
        Map.of("planeCount", 1),
        degenerativeFindings,
        Map.of("status", "pending"),
        new CanonicalMultiplanarRun.Governance(true, true, true, false));
  }

  private Map<String, Object> degenerativeFindings() {
    return Map.of(
        "schemaVersion",
        "pfi.degenerative-findings.v1",
        "findings",
        List.of(
            Map.of(
                "findingId", "central-canal-l4-l5",
                "findingType", "central_canal_stenosis",
                "anatomy", Map.of("level", "L4-L5"),
                "classification",
                    Map.of(
                        "label",
                        "moderate",
                        "probabilities",
                        Map.of("normal_mild", 0.20, "moderate", 0.60, "severe", 0.20)),
                "evaluation", Map.of("status", "evaluated"),
                "sourceSeries", Map.of("role", "sagittal_t2", "position", 0),
                "localization", Map.of("source", "slice_index", "researchOnly", false),
                "model", Map.of("modelId", "model-a", "modelSha256", "sha256-a"),
                "review", Map.of("required", true, "status", "pending"),
                "notClinicalDiagnosis", true)));
  }
}
