package ar.edu.uade.pfi.backend.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AiMultiplanarV2ResponseContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesRealBaselineSagittalOnlyFixture() throws Exception {
        try (InputStream json = getClass().getResourceAsStream("/contracts/ai-module-multiplanar-v2-real-baseline.json")) {
            AiMultiplanarV2ResponseDto response = objectMapper.readValue(json, AiMultiplanarV2ResponseDto.class);

            assertEquals("completed", response.status());
            assertEquals("pfi.multiplanar-run.v2", response.schemaVersion());
            assertEquals("multi-v2-001", response.multiplanarRunId());
            assertEquals("sagittal_only", response.workspaceMode());
            assertEquals("real_baseline", response.requestedInferenceMode());
            assertEquals("real_baseline", response.effectiveInferenceMode());
            assertEquals(false, response.synthetic());
            assertNull(response.fallbackReason());

            assertTrue(response.planes().sagittal() != null);
            assertNull(response.planes().axial());
            var sagittal = response.planes().sagittal();
            assertEquals("cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944", sagittal.model().artifactHash());
            assertEquals(java.util.List.of(352, 384, 17), sagittal.input().nativeShape());
            assertEquals(java.util.List.of(352, 384, 17), sagittal.input().canonicalShape());
            assertEquals(256, sagittal.coordinateSpace().width());
            assertEquals(256, sagittal.coordinateSpace().height());
            assertEquals(3, sagittal.masks().size());
            assertEquals(3, sagittal.landmarks().size());
            assertEquals(9, sagittal.measurements().size());
            assertEquals(4, sagittal.assets().size());
            assertTrue(sagittal.assets().stream().anyMatch(asset -> "input.png".equals(asset.assetName())));
            assertTrue(sagittal.assets().stream().anyMatch(asset -> "mask.npy".equals(asset.assetName())));

            assertEquals(true, response.governance().humanReviewRequired());
            assertEquals(true, response.governance().notClinicalDiagnosis());
            assertEquals(true, response.governance().deidentified());
            assertEquals(false, response.governance().diagnosisGenerated());
            assertEquals(java.util.List.of("sagittal"), response.requestedPlanes());
            assertEquals(java.util.List.of("sagittal"), response.completedPlanes());
            assertEquals(true, response.review().required());
            assertEquals("pending", response.review().status());
        }
    }

    @Test
    void rejectsUnknownFieldsOnCriticalRootDto() {
        String jsonWithUnknownField = """
            {"status":"completed","unexpectedLegacyField":"boom"}
            """;
        assertThrows(JsonMappingException.class, () -> objectMapper.readValue(jsonWithUnknownField, AiMultiplanarV2ResponseDto.class));
    }

    @Test
    void rejectsLegacyAliasesOnPlanesDto() {
        String jsonWithLegacyAlias = """
            {"sagital": {"plane": "sagittal"}}
            """;
        assertThrows(JsonMappingException.class, () -> objectMapper.readValue(jsonWithLegacyAlias, AiMultiplanarV2PlanesDto.class));
    }

    @Test
    void requestDtoSerializesOnlyTypedContractFields() throws Exception {
        AiMultiplanarV2RequestDto request = new AiMultiplanarV2RequestDto(
            "CASE-001",
            "trace-001",
            "real_baseline",
            false,
            new AiMultiplanarV2PlanesRequestDto(new AiPlaneExecutionV2RequestDto("inp_sagittal_001", "sagittal_spider"), null),
            new AiMultiplanarV2OptionsDto(null, null, 3, null)
        );

        String serialized = objectMapper.writeValueAsString(request);

        assertFalse(serialized.contains("studyMetadata"));
        assertFalse(serialized.contains("subjectRef"));
        assertFalse(serialized.contains("sagittalInputPath"));
        assertFalse(serialized.contains("backendTraceId"));
        assertFalse(serialized.contains("correlationId"));
        assertTrue(serialized.contains("\"caseId\":\"CASE-001\""));
        assertTrue(serialized.contains("\"traceId\":\"trace-001\""));
        assertTrue(serialized.contains("\"inputId\":\"inp_sagittal_001\""));
    }
}
