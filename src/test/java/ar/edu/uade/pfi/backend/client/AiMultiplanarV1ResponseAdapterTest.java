package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AiMultiplanarV1ResponseAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiMultiplanarV1ResponseAdapter adapter = new AiMultiplanarV1ResponseAdapter();

    @Test
    void adaptsDualPlaneFixtureToCanonicalModel() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/contracts/ai-module-multiplanar-real-baseline.json"));
        MultiplanarRunResponseDto response = objectMapper.readValue(json, MultiplanarRunResponseDto.class);

        CanonicalMultiplanarRun canonical = adapter.toCanonical(response);

        assertEquals("multiplanar_run_ready", canonical.status());
        assertEquals("multi-contract-001", canonical.multiplanarRunId());
        assertEquals("CASE-001", canonical.caseId());
        assertTrue(canonical.requestedPlanes().containsAll(java.util.List.of("sagittal", "axial")));
        assertTrue(canonical.completedPlanes().containsAll(java.util.List.of("sagittal", "axial")));
        assertTrue(canonical.governance().humanReviewRequired());
        assertTrue(canonical.governance().notClinicalDiagnosis());

        CanonicalPlaneRun sagittal = canonical.sagittal();
        assertEquals("run-sag-001", sagittal.planeRunId());
        assertEquals("cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944", sagittal.model().get("artifactHash"));
        assertEquals(1, sagittal.measurements().size());
    }
}
