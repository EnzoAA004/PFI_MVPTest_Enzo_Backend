package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.AiGovernanceV2Dto;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2ResponseDto;
import ar.edu.uade.pfi.backend.dto.AiPlaneRunV2Dto;
import ar.edu.uade.pfi.backend.service.VolumeSliceCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiMultiplanarV2ResponseAdapter {
    private final ObjectMapper objectMapper;
    private final VolumeSliceCatalogService volumeSliceCatalogService = new VolumeSliceCatalogService();

    public AiMultiplanarV2ResponseAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CanonicalMultiplanarRun toCanonical(AiMultiplanarV2ResponseDto response) {
        if (response == null) return null;
        AiPlaneRunV2Dto sagittal = response.planes() == null ? null : response.planes().sagittal();
        AiPlaneRunV2Dto axial = response.planes() == null ? null : response.planes().axial();

        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        if (sagittal != null) planes.put("sagittal", toPlane(sagittal));
        if (axial != null) planes.put("axial", toPlane(axial));

        List<String> requested = response.requestedPlanes() != null ? response.requestedPlanes() : List.copyOf(planes.keySet());
        List<String> completed = response.completedPlanes() != null ? response.completedPlanes() : List.copyOf(planes.keySet());

        AiGovernanceV2Dto governanceDto = response.governance();
        return new CanonicalMultiplanarRun(
            text(response.status()),
            text(response.schemaVersion()),
            text(response.runId()),
            text(response.traceId()),
            text(response.caseId()),
            text(response.workspaceMode()),
            text(response.requestedInferenceMode()),
            text(response.effectiveInferenceMode()),
            requested,
            completed,
            Boolean.TRUE.equals(response.synthetic()),
            response.fallbackReason(),
            asMap(response.readiness()),
            planes,
            asMap(response.threeD()),
            asMap(response.quality()),
            asMap(response.review()),
            new CanonicalMultiplanarRun.Governance(
                governanceDto != null && Boolean.TRUE.equals(governanceDto.humanReviewRequired()),
                governanceDto != null && Boolean.TRUE.equals(governanceDto.notClinicalDiagnosis()),
                governanceDto != null && Boolean.TRUE.equals(governanceDto.deidentified()),
                governanceDto != null && Boolean.TRUE.equals(governanceDto.diagnosisGenerated())
            )
        );
    }

    private CanonicalPlaneRun toPlane(AiPlaneRunV2Dto plane) {
        List<Map<String, Object>> assets = plane.assets() == null ? List.of() : asMapList(plane.assets());
        Map<String, Object> input = volumeSliceCatalogService.normalizePlaneInput(asMap(plane.input()), text(plane.runId()), text(plane.plane()));
        return new CanonicalPlaneRun(
            text(plane.runId()),
            text(plane.plane()),
            text(plane.status()),
            text(plane.effectiveInferenceMode()),
            Boolean.TRUE.equals(plane.synthetic()),
            plane.fallbackReason(),
            asMap(plane.model()),
            input,
            asMap(plane.coordinateSpace()),
            asMapList(plane.series()),
            assets,
            asMapList(plane.masks()),
            asMapList(plane.landmarks()),
            asMapList(plane.measurements()),
            asMap(plane.quality())
        );
    }

    private <T> Map<String, Object> asMap(T value) {
        if (value == null) return Map.of();
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private <T> List<Map<String, Object>> asMapList(List<T> values) {
        if (values == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (T value : values) {
            result.add(asMap(value));
        }
        return result;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
