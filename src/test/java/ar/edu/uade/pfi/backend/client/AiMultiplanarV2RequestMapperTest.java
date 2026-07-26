package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2RequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiMultiplanarV2RequestMapperTest {
    private final AiMultiplanarV2RequestMapper mapper = new AiMultiplanarV2RequestMapper();

    @Test
    void mapsSagittalOnlyRequestExcludingForbiddenFields() {
        MultiplanarRunRequestDto internal = new MultiplanarRunRequestDto(
            "CASE-001",
            "inp_sagittal_001",
            null,
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of("inferenceMode", "real_baseline", "traceId", "trace-xyz")
        );

        AiMultiplanarV2RequestDto v2 = mapper.toV2Request(internal, "trace-xyz");

        assertEquals("CASE-001", v2.caseId());
        assertEquals("trace-xyz", v2.traceId());
        assertEquals("real_baseline", v2.inferenceMode());
        assertEquals(false, v2.allowContractFallback());
        assertEquals("inp_sagittal_001", v2.planes().sagittal().inputId());
        assertEquals("sagittal_spider", v2.planes().sagittal().modelKey());
        assertNull(v2.planes().axial());
        assertEquals(3, v2.options().sliceWindowRadius());
        assertNull(v2.options().sliceIndex());
    }

    @Test
    void rejectsWhenNoPlaneRequested() {
        MultiplanarRunRequestDto internal = new MultiplanarRunRequestDto(
            "CASE-001", null, null, null, null, "sagittal_spider", "axial_t2_alkafri", false,
            Map.of("inferenceMode", "real_baseline")
        );

        assertThrows(ResponseStatusException.class, () -> mapper.toV2Request(internal, "trace-1"));
    }

    @Test
    void rejectsPathOnlyInputBecauseV2RequiresInputId() {
        MultiplanarRunRequestDto internal = new MultiplanarRunRequestDto(
            "CASE-001", null, null, "some/sagittal/path", null, "sagittal_spider", "axial_t2_alkafri", false,
            Map.of("inferenceMode", "real_baseline")
        );

        assertThrows(ResponseStatusException.class, () -> mapper.toV2Request(internal, "trace-1"));
    }

    @Test
    void rejectsAllowContractFallbackTrue() {
        MultiplanarRunRequestDto internal = new MultiplanarRunRequestDto(
            "CASE-001", "inp_sagittal_001", null, null, null, "sagittal_spider", "axial_t2_alkafri", true,
            Map.of("inferenceMode", "real_baseline")
        );

        assertThrows(ResponseStatusException.class, () -> mapper.toV2Request(internal, "trace-1"));
    }

    @Test
    void rejectsUnknownInferenceMode() {
        MultiplanarRunRequestDto internal = new MultiplanarRunRequestDto(
            "CASE-001", "inp_sagittal_001", null, null, null, "sagittal_spider", "axial_t2_alkafri", false,
            Map.of("inferenceMode", "totally_unknown_mode")
        );

        assertThrows(ResponseStatusException.class, () -> mapper.toV2Request(internal, "trace-1"));
    }

    @Test
    void rejectsMissingModelKey() {
        MultiplanarRunRequestDto internal = new MultiplanarRunRequestDto(
            "CASE-001", "inp_sagittal_001", null, null, null, null, "axial_t2_alkafri", false,
            Map.of("inferenceMode", "real_baseline")
        );

        assertThrows(ResponseStatusException.class, () -> mapper.toV2Request(internal, "trace-1"));
    }

    @Test
    void doesNotSerializeForbiddenFieldsInResultingDto() throws Exception {
        MultiplanarRunRequestDto internal = new MultiplanarRunRequestDto(
            "CASE-001", "inp_sagittal_001", "inp_axial_001", null, null, "sagittal_spider", "axial_t2_alkafri", false,
            Map.of("inferenceMode", "real_baseline", "backendTraceId", "should-not-leak", "correlationId", "should-not-leak")
        );

        AiMultiplanarV2RequestDto v2 = mapper.toV2Request(internal, "trace-xyz");
        String serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(v2);

        assertFalse(serialized.contains("should-not-leak"));
        assertFalse(serialized.contains("studyMetadata"));
        assertFalse(serialized.contains("sagittalInputPath"));
    }
}
