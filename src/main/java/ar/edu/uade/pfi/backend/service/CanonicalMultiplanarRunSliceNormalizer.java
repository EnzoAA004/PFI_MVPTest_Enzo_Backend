package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CanonicalMultiplanarRunSliceNormalizer {
    private final VolumeSliceCatalogService volumeSliceCatalogService;

    public CanonicalMultiplanarRunSliceNormalizer() {
        this(new VolumeSliceCatalogService());
    }

    CanonicalMultiplanarRunSliceNormalizer(VolumeSliceCatalogService volumeSliceCatalogService) {
        this.volumeSliceCatalogService = volumeSliceCatalogService;
    }

    public CanonicalMultiplanarRun normalize(CanonicalMultiplanarRun run) {
        if (run == null) return null;
        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        for (Map.Entry<String, CanonicalPlaneRun> entry : run.planes().entrySet()) {
            planes.put(entry.getKey(), normalizePlane(entry.getValue()));
        }
        return new CanonicalMultiplanarRun(
            run.status(),
            run.schemaVersion(),
            run.multiplanarRunId(),
            run.traceId(),
            run.caseId(),
            run.workspaceMode(),
            run.requestedInferenceMode(),
            run.effectiveInferenceMode(),
            run.requestedPlanes(),
            run.completedPlanes(),
            run.synthetic(),
            run.fallbackReason(),
            run.readiness(),
            planes,
            run.threeD(),
            run.quality(),
            run.review(),
            run.governance()
        );
    }

    private CanonicalPlaneRun normalizePlane(CanonicalPlaneRun plane) {
        if (plane == null) return null;
        Map<String, Object> input = volumeSliceCatalogService.normalizePlaneInput(
            plane.input(),
            plane.planeRunId(),
            plane.plane(),
            List.of(),
            plane.measurements(),
            plane.landmarks()
        );
        return new CanonicalPlaneRun(
            plane.planeRunId(),
            plane.plane(),
            plane.status(),
            plane.effectiveInferenceMode(),
            plane.synthetic(),
            plane.fallbackReason(),
            plane.model(),
            input,
            plane.coordinateSpace(),
            plane.series(),
            plane.assets(),
            plane.masks(),
            plane.landmarks(),
            plane.measurements(),
            plane.quality()
        );
    }
}
