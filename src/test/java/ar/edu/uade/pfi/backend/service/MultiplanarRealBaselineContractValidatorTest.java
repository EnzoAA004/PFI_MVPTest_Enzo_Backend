package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiplanarRealBaselineContractValidatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MultiplanarRunResponsePresenter presenter = new MultiplanarRunResponsePresenter();
    private final MultiplanarRealBaselineContractValidator validator = new MultiplanarRealBaselineContractValidator();

    @Test
    void strictRealBaselineDualValidResponsePasses() throws Exception {
        assertDoesNotThrow(() -> validator.validate(strictRequest(), presentedFixture()));
    }

    @Test
    void strictRealBaselineSagittalOnlyValidResponsePassesWithAxialUnavailable() throws Exception {
        Map<String, Object> response = fixtureMap();
        response.put("effectiveInferenceMode", "mixed");
        response.put("quality", Map.of(
            "sagittalRunReady", true,
            "axialRunReady", false,
            "dualRunReady", false
        ));
        planes(response).remove("axial");

        assertDoesNotThrow(() -> validator.validate(strictSagittalOnlyRequest(), presenter.present(read(response))));
    }

    @Test
    void sagittalFinalModelVersionArtifactInferenceFallbackAndInputIdAreStrict() throws Exception {
        assertFails(mutateSagittal("modelVersion", "wrong-version"));
        assertFails(mutateSagittal("artifactHash", "wrong-hash"));
        assertFails(mutateSagittal("inferenceMode", "contract"));
        assertFails(mutateSagittal("allowContractFallback", true));
        assertFails(mutateSagittal("inputId", "other-input"));

        Map<String, Object> response = fixtureMap();
        sagittal(response).put("aiOutput", new LinkedHashMap<>(Map.of(
            "inferenceMode", "real_baseline",
            "artifactHash", MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH,
            "realInferenceAvailable", false
        )));
        assertFails(response);
    }

    @Test
    void sagittalSpiderOrientationAndSpacingAreStrictWhenNativeShapeMatches() throws Exception {
        assertDoesNotThrow(() -> validator.validate(strictRequest(), presentedFixture()));
        assertFails(mutateSagittalMetadata("inputShapeCanonical", List.of(17, 512, 512)));
        assertFails(mutateSagittalMetadata("selectedAxis", 0));
        assertFails(mutateSagittalMetadata("selectedSlice", 17));
        assertFails(mutateSagittalMetadata("inPlaneSpacing", List.of(0.7, 0)));
    }

    @Test
    void axialIsValidatedIndependentlyWithoutSagittalShaRules() throws Exception {
        assertDoesNotThrow(() -> validator.validateAxial(strictRequest(), presentedFixture().planes().axial()));
        assertFails(mutateAxial("inferenceMode", "contract"));
        assertFails(mutateAxial("allowContractFallback", true));
        assertFails(mutateAxial("inputId", "other-input"));
        assertDoesNotThrow(() -> {
            Map<String, Object> response = fixtureMap();
            axial(response).put("artifactHash", "not-sagittal-hash");
            validator.validateAxial(strictRequest(), presenter.present(read(response)).planes().axial());
        });
    }

    @Test
    void rootDegradedOrUnsafeClinicalFlagsFailButMixedWorkspaceIsAllowed() throws Exception {
        assertDoesNotThrow(() -> validator.validate(strictRequest(), presenter.present(read(mutateRoot("effectiveInferenceMode", "mixed")))));
        assertFails(mutateRoot("degradedMode", true));
        assertFails(mutateRoot("notClinicalDiagnosis", false));
        assertFails(mutateRoot("humanReviewRequired", false));
    }

    @Test
    void v2SchemaVersionSkipsV1SpecificFieldShapeChecksButStillEnforcesGovernance() {
        MultiplanarRunResponseDto v2Shaped = new MultiplanarRunResponseDto(
            "completed",
            "pfi.multiplanar-run.v2",
            "multi-v2-001",
            "trace-v2-001",
            "CASE-001",
            "sagittal_only",
            "real_baseline",
            "real_baseline",
            new MultiplanarRunResponseDto.PlanesDto(
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-sag-v2-001", "sagittal", "sagittal_spider", "completed", "real_baseline",
                    Map.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of()
                ),
                null
            ),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            true,
            true,
            false
        );

        assertDoesNotThrow(() -> validator.validate(strictSagittalOnlyRequest(), v2Shaped));

        MultiplanarRunResponseDto missingGovernance = new MultiplanarRunResponseDto(
            v2Shaped.status(), v2Shaped.schemaVersion(), v2Shaped.runId(), v2Shaped.traceId(), v2Shaped.caseId(),
            v2Shaped.workspaceMode(), v2Shaped.requestedInferenceMode(), v2Shaped.effectiveInferenceMode(),
            v2Shaped.planes(), v2Shaped.assets(), v2Shaped.threeD(), v2Shaped.quality(), v2Shaped.review(),
            v2Shaped.metadata(), false, true, false
        );
        assertThrows(AiMultiplanarContractViolationException.class, () -> validator.validate(strictSagittalOnlyRequest(), missingGovernance));
    }

    private Map<String, Object> mutateRoot(String key, Object value) throws Exception {
        Map<String, Object> response = fixtureMap();
        response.put(key, value);
        return response;
    }

    private Map<String, Object> mutateSagittal(String key, Object value) throws Exception {
        Map<String, Object> response = fixtureMap();
        sagittal(response).put(key, value);
        return response;
    }

    private Map<String, Object> mutateAxial(String key, Object value) throws Exception {
        Map<String, Object> response = fixtureMap();
        axial(response).put(key, value);
        return response;
    }

    private Map<String, Object> mutateSagittalMetadata(String key, Object value) throws Exception {
        Map<String, Object> response = fixtureMap();
        metadata(sagittal(response)).put(key, value);
        return response;
    }

    private void assertFails(Map<String, Object> response) {
        assertThrows(AiMultiplanarContractViolationException.class, () -> validator.validate(strictRequest(), presenter.present(read(response))));
    }

    private MultiplanarRunRequestDto strictRequest() {
        return new MultiplanarRunRequestDto(
            "CASE-001",
            "inp_sagittal_001",
            "inp_axial_001",
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of("inferenceMode", "real_baseline", "requestedInferenceMode", "real_baseline", "allowContractFallback", false)
        );
    }

    private MultiplanarRunRequestDto strictSagittalOnlyRequest() {
        return new MultiplanarRunRequestDto(
            "CASE-001",
            "inp_sagittal_001",
            null,
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of(
                "inferenceMode", "real_baseline",
                "requestedInferenceMode", "real_baseline",
                "allowContractFallback", false,
                "axialMode", "optional_not_provided"
            )
        );
    }

    private MultiplanarRunResponseDto presentedFixture() throws Exception {
        return presenter.present(read(fixtureMap()));
    }

    private MultiplanarRunResponseDto read(Map<String, Object> response) {
        return objectMapper.convertValue(response, MultiplanarRunResponseDto.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixtureMap() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/contracts/ai-module-multiplanar-real-baseline.json"));
        return objectMapper.readValue(json, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planes(Map<String, Object> response) {
        return (Map<String, Object>) response.get("planes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sagittal(Map<String, Object> response) {
        return (Map<String, Object>) planes(response).get("sagittal");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> axial(Map<String, Object> response) {
        return (Map<String, Object>) planes(response).get("axial");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> plane) {
        return (Map<String, Object>) plane.get("metadata");
    }
}
