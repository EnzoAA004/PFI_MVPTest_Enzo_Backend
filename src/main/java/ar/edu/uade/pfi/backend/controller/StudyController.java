package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto.AiOutputStateDto;
import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto.ContourDto;
import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto.LandmarkDto;
import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto.MaskDto;
import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto.MeasurementDto;
import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto.PointDto;
import ar.edu.uade.pfi.backend.dto.StudyReviewResponseDto.SeriesDto;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.StudyWorklistService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/studies")
public class StudyController {
    private final StudyWorklistService studyWorklistService;
    private final ProfessionalAccessAuditService accessAuditService;

    public StudyController(StudyWorklistService studyWorklistService, ProfessionalAccessAuditService accessAuditService) {
        this.studyWorklistService = studyWorklistService;
        this.accessAuditService = accessAuditService;
    }

    @GetMapping
    public Map<String, Object> listStudies(HttpServletRequest request) {
        accessAuditService.record(request, "access_worklist", "Worklist de-identificada consultada");
        return studyWorklistService.listStudies();
    }

    @GetMapping("/{caseId}")
    public Map<String, Object> getStudy(@PathVariable String caseId, HttpServletRequest request) {
        accessAuditService.record(request, "access_study_detail", "Detalle de estudio de-identificado consultado caseId=" + caseId);
        return studyWorklistService.getStudy(caseId);
    }

    @GetMapping("/{caseId}/runs")
    public Map<String, Object> getStudyRuns(@PathVariable String caseId, HttpServletRequest request) {
        accessAuditService.record(request, "access_study_runs", "Corridas de estudio consultadas caseId=" + caseId);
        return studyWorklistService.getStudyRuns(caseId);
    }

    @PostMapping("/demo")
    public Map<String, Object> createDemoStudy(HttpServletRequest request) {
        accessAuditService.record(request, "access_demo_study", "Caso demo generado desde endpoint protegido");
        return studyWorklistService.createDemoStudy();
    }

    @GetMapping("/demo-review")
    public StudyReviewResponseDto demoReview(HttpServletRequest request) {
        accessAuditService.record(request, "access_demo_review_contract", "Contrato demo de review workspace consultado");
        return new StudyReviewResponseDto(
            "STUDY-DEMO-0142",
            "CASE-DEMO-0142",
            "PAT-0087",
            "2026-07-01",
            "MRI",
            "Lumbar Spine",
            "pendiente",
            "sagittal_spider",
            "contract-v1",
            new AiOutputStateDto(
                "ai_output_pending",
                "AI output pending / real inference pending",
                "Contrato preparado para overlayUrl, maskContours, landmarks y measurements.values; la inferencia real se conectará cuando el modelo esté entrenado.",
                false,
                true,
                true
            ),
            demoSeries(),
            demoMasks(),
            demoLandmarks(),
            demoMeasurements(),
            Map.of(
                "dataScope", "academic-deidentified",
                "privacy", "No direct identifiers; synthetic demo data",
                "source", "backend-demo-study-review-contract"
            )
        );
    }

    private List<SeriesDto> demoSeries() {
        return List.of(
            new SeriesDto("series-sag-t2", "Sagittal T2", "sagittal", "T2", 96, 58, null, null, 0.74, "ai_output_pending"),
            new SeriesDto("series-sag-t1", "Sagittal T1", "sagittal", "T1", 96, 58, null, null, 0.62, "reference_only"),
            new SeriesDto("series-ax-t2", "Axial T2 L4-L5", "axial", "T2", 48, 24, null, null, 0.74, "ai_output_pending"),
            new SeriesDto("series-ax-t1", "Axial T1", "axial", "T1", 48, 22, null, null, 0.55, "reference_only")
        );
    }

    private List<MaskDto> demoMasks() {
        return List.of(
            new MaskDto("mask-vertebral-body", "Vertebral body", "vertebral_body", "#c8b28a", 0.86, true, true, List.of(
                contour("series-sag-t2", 58, p(54, 18), p(66, 20), p(67, 28), p(53, 29)),
                contour("series-sag-t2", 58, p(52, 34), p(65, 35), p(65, 43), p(51, 44))
            )),
            new MaskDto("mask-disc", "Intervertebral disc", "disc", "#2563eb", 0.82, true, true, List.of(
                contour("series-sag-t2", 58, p(48, 48), p(72, 46), p(74, 53), p(49, 55)),
                contour("series-sag-t2", 58, p(45, 63), p(68, 61), p(70, 68), p(46, 70))
            )),
            new MaskDto("mask-canal", "Spinal canal", "spinal_canal", "#16a34a", 0.79, true, true, List.of(
                contour("series-sag-t2", 58, p(62, 16), p(70, 26), p(70, 70), p(61, 76), p(58, 45)),
                contour("series-ax-t2", 24, p(38, 30), p(65, 29), p(70, 54), p(50, 65), p(34, 54))
            )),
            new MaskDto("mask-root-left", "Left nerve root", "nerve_root", "#f59e0b", 0.72, true, true, List.of(
                contour("series-ax-t2", 24, p(24, 55), p(38, 50), p(43, 58), p(30, 66))
            )),
            new MaskDto("mask-foramen-right", "Right foramen", "foramen", "#8b5cf6", 0.70, true, true, List.of(
                contour("series-ax-t2", 24, p(60, 54), p(76, 50), p(79, 62), p(64, 68))
            ))
        );
    }

    private List<LandmarkDto> demoLandmarks() {
        return List.of(
            new LandmarkDto("lm-l4-sup", "L4 superior endplate", "series-sag-t2", 58, 55.8, 47.2, true, "mask-vertebral-body"),
            new LandmarkDto("lm-l4-inf", "L4 inferior endplate", "series-sag-t2", 58, 58.5, 56.1, true, "mask-disc"),
            new LandmarkDto("lm-l5-sup", "L5 superior endplate", "series-sag-t2", 58, 52.2, 62.8, true, "mask-disc"),
            new LandmarkDto("lm-canal-a", "Canal AP anterior", "series-ax-t2", 24, 48.0, 39.0, true, "mask-canal"),
            new LandmarkDto("lm-canal-p", "Canal AP posterior", "series-ax-t2", 24, 49.5, 58.0, true, "mask-canal")
        );
    }

    private List<MeasurementDto> demoMeasurements() {
        return List.of(
            new MeasurementDto("disc-height-l45", "Disc Height", "L4-L5", 13.8, 13.8, null, "mm", "AI", 0.82, "pendiente", false, List.of("lm-l4-inf", "lm-l5-sup")),
            new MeasurementDto("disc-height-l5s1", "Disc Height", "L5-S1", 11.9, 11.9, null, "mm", "AI", 0.78, "pendiente", false, List.of("lm-l5-sup")),
            new MeasurementDto("canal-diameter-l45", "Central Canal Diameter", "L4-L5", 14.2, 14.2, null, "mm", "AI", 0.76, "pendiente", false, List.of("lm-canal-a", "lm-canal-p")),
            new MeasurementDto("foraminal-left-l45", "Left Foraminal Width", "L4-L5", 6.4, 6.4, null, "mm", "AI", 0.71, "pendiente", true, List.of("lm-canal-a")),
            new MeasurementDto("lordosis-angle", "Lordosis Angle", "L1-S1", 41.5, 41.5, null, "deg", "AI", 0.80, "pendiente", false, List.of("lm-l4-sup", "lm-l5-sup"))
        );
    }

    private ContourDto contour(String seriesId, int sliceIndex, PointDto... points) {
        return new ContourDto(seriesId, sliceIndex, List.of(points));
    }

    private PointDto p(double x, double y) {
        return new PointDto(x, y);
    }
}
