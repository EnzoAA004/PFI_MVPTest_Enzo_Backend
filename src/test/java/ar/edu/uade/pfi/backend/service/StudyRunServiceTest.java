package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StudyRunServiceTest {
    @Test
    void persistsAndRecoversStudyInputsAndRunTraceability() {
        StudyRunService service = new StudyRunService(
            new InMemoryStudyRepository(),
            Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC)
        );

        Study study = service.createStudy("CASE-DEMO-9001", "created");
        service.createInput(study, "sagittal", "input-sag-9001", "png", 1024);
        service.createInput(study, "axial", "input-ax-9001", "npy", 2048);
        StudyRun run = service.createRun(
            study,
            "multi-9001",
            "trace-9001",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "axial_t2_alkafri",
            "run-sag-9001",
            "run-ax-9001",
            Map.of(
                "sagittal", Map.of("overlay", "overlay.png"),
                "axial", Map.of("maskPreview", "mask-preview.png")
            ),
            "completed"
        );

        Study recoveredStudy = service.findStudyByCaseId("CASE-DEMO-9001").orElseThrow();
        List<InputResource> inputs = service.findInputs(recoveredStudy);
        StudyRun recoveredRun = service.findRunByMultiplanarRunId("multi-9001").orElseThrow();

        assertEquals(study.id(), recoveredStudy.id());
        assertEquals(2, inputs.size());
        assertTrue(inputs.stream().anyMatch(input -> input.plane().equals("sagittal") && input.inputId().equals("input-sag-9001")));
        assertTrue(inputs.stream().anyMatch(input -> input.plane().equals("axial") && input.inputId().equals("input-ax-9001")));
        assertEquals(run.id(), recoveredRun.id());
        assertEquals("trace-9001", recoveredRun.traceId());
        assertEquals("run-sag-9001", recoveredRun.sagittalRunId());
        assertEquals("run-ax-9001", recoveredRun.axialRunId());
        assertEquals("real_baseline", recoveredRun.effectiveInferenceMode());
        assertFalse(recoveredRun.assets().isEmpty());
    }
}
