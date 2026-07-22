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
    void rootMixedDegradedOrUnsafeClinicalFlagsFail() throws Exception {
        assertFails(mutateRoot("effectiveInferenceMode", "mixed"));
        assertFails(mutateRoot("degradedMode", true));
        assertFails(mutateRoot("notClinicalDiagnosis", false));
        assertFails(mutateRoot("humanReviewRequired", false));

        Map<String, Object> response = fixtureMap();
        planes(response).remove("axial");
        assertFails(response);
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
