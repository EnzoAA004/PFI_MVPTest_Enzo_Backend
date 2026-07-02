package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.AuditEventDto;
import ar.edu.uade.pfi.backend.dto.AuditEventRequestDto;
import ar.edu.uade.pfi.backend.dto.MeasurementBatchDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSaveDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportResponseDto;
import ar.edu.uade.pfi.backend.dto.ReviewSnapshotDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewStoreService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("pendiente", "aceptado", "observado", "descartado");
    private static final Set<String> ALLOWED_EXPORT_FORMATS = Set.of("json", "csv", "html");
    private final Map<String, ReviewStatusDto> reviews = new ConcurrentHashMap<>();
    private final Map<String, List<MeasurementSaveDto>> measurementsByRunId = new ConcurrentHashMap<>();
    private final List<AuditEventDto> auditTrail = new ArrayList<>();
    private final PostgresReviewStoreService postgres;
    private final ObjectMapper objectMapper;

    public ReviewStoreService(PostgresReviewStoreService postgres, ObjectMapper objectMapper) {
        this.postgres = postgres;
        this.objectMapper = objectMapper;
    }

    public ReviewStatusDto findOrDefault(String runId) {
        return reviews.getOrDefault(runId, new ReviewStatusDto(runId, "pendiente", "", "", Instant.now()));
    }

    public ReviewSnapshotDto snapshot() {
        ReviewSnapshotDto persisted = postgres.enabled() ? postgres.snapshot() : null;
        if (persisted != null) return persisted;
        return new ReviewSnapshotDto(
            reviews.values().stream().sorted(Comparator.comparing(ReviewStatusDto::updatedAt).reversed()).toList(),
            Map.copyOf(measurementsByRunId),
            auditTrail.stream().sorted(Comparator.comparing(AuditEventDto::timestamp).reversed()).limit(100).toList()
        );
    }

    public List<MeasurementSaveDto> findMeasurements(String runId) {
        if (postgres.enabled()) {
            List<MeasurementSaveDto> persisted = postgres.findMeasurements(runId);
            if (!persisted.isEmpty()) return persisted;
        }
        return measurementsByRunId.getOrDefault(runId, List.of());
    }

    public List<MeasurementSaveDto> saveMeasurements(String runId, MeasurementBatchDto request) {
        List<MeasurementSaveDto> next = request.measurements() == null ? List.of() : List.copyOf(request.measurements());
        measurementsByRunId.put(runId, next);
        postgres.saveMeasurements(runId, next);
        appendAudit(new AuditEventRequestDto(
            defaultString(request.reviewer(), "Reviewer"),
            "measurements_updated",
            defaultString(request.detail(), "Mediciones revisadas para runId=" + runId)
        ));
        return next;
    }

    public ReviewExportResponseDto exportReview(String runId, ReviewExportRequestDto request) {
        String format = normalizeFormat(request.format());
        Instant generatedAt = Instant.now();
        ReviewStatusDto review = findOrDefault(runId);
        List<MeasurementSaveDto> requestMeasurements = request.measurements() == null ? List.of() : request.measurements();
        List<MeasurementSaveDto> storedMeasurements = findMeasurements(runId);
        List<MeasurementSaveDto> measurements = requestMeasurements.isEmpty() ? storedMeasurements : requestMeasurements;
        ExportContext context = new ExportContext(
            runId,
            defaultString(request.caseId(), runId),
            defaultString(request.subjectRef(), "deidentified-subject"),
            defaultString(request.studyDate(), "sin fecha"),
            defaultString(request.plane(), "sin plano"),
            defaultString(request.modelKey(), "sin modelo"),
            defaultString(request.modelVersion(), "sin version"),
            defaultString(request.inferenceMode(), "contract"),
            defaultString(request.modelReadiness(), "sin datos"),
            defaultString(request.notes(), review.notes()),
            defaultString(request.reviewer(), review.reviewer()),
            review.status(),
            measurements,
            generatedAt
        );
        String content = switch (format) {
            case "csv" -> toCsv(context);
            case "html" -> toHtml(context);
            default -> toJson(context);
        };
        String mimeType = switch (format) {
            case "csv" -> "text/csv;charset=utf-8";
            case "html" -> "text/html;charset=utf-8";
            default -> "application/json;charset=utf-8";
        };
        String fileName = safeFile(context.caseId()) + "-" + safeFile(runId) + "-informe." + format;
        appendAudit(new AuditEventRequestDto(
            defaultString(context.reviewer(), "Reviewer"),
            "review_export_" + format,
            "Export de-identificado generado para caseId=" + context.caseId() + ", runId=" + runId + ", format=" + format
        ));
        return new ReviewExportResponseDto(
            "ok",
            format,
            fileName,
            mimeType,
            content,
            runId,
            context.caseId(),
            true,
            false,
            true,
            true,
            generatedAt
        );
    }

    public AuditEventDto appendAudit(AuditEventRequestDto request) {
        AuditEventDto event = new AuditEventDto(
            "audit-" + UUID.randomUUID(),
            Instant.now(),
            defaultString(request.reviewer(), "System"),
            defaultString(request.action(), "event"),
            defaultString(request.detail(), "")
        );
        auditTrail.add(event);
        if (auditTrail.size() > 200) auditTrail.remove(0);
        postgres.appendAudit(event);
        return event;
    }

    public List<AuditEventDto> auditTrail() {
        if (postgres.enabled()) {
            List<AuditEventDto> persisted = postgres.findAuditTrail();
            if (!persisted.isEmpty()) return persisted;
        }
        return auditTrail.stream().sorted(Comparator.comparing(AuditEventDto::timestamp).reversed()).limit(100).toList();
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        String normalizedStatus = request.status().trim().toLowerCase();
        if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid review status");
        }
        ReviewStatusDto review = new ReviewStatusDto(
            runId,
            normalizedStatus,
            request.notes() == null ? "" : request.notes(),
            request.reviewer() == null ? "" : request.reviewer(),
            Instant.now()
        );
        reviews.put(runId, review);
        postgres.saveReview(review);
        appendAudit(new AuditEventRequestDto(
            review.reviewer(),
            "review_" + normalizedStatus,
            "Revision " + normalizedStatus + " guardada para runId=" + runId
        ));
        return review;
    }

    private String toJson(ExportContext context) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "report", Map.of(
                    "title", "PFI Lumbar MRI - Resumen academico de revision",
                    "generatedAt", context.generatedAt().toString(),
                    "caseId", context.caseId(),
                    "runId", context.runId(),
                    "reviewStatus", context.reviewStatus()
                ),
                "study", Map.of(
                    "subjectRef", context.subjectRef(),
                    "studyDate", context.studyDate(),
                    "plane", context.plane(),
                    "modelKey", context.modelKey(),
                    "modelVersion", context.modelVersion()
                ),
                "traceability", Map.of(
                    "inferenceMode", context.inferenceMode(),
                    "modelReadiness", context.modelReadiness()
                ),
                "summary", Map.of(
                    "measurementsTotal", context.measurements().size(),
                    "outliers", context.measurements().stream().filter(item -> Boolean.TRUE.equals(item.outlier())).count()
                ),
                "measurements", context.measurements(),
                "governance", Map.of(
                    "scope", "academic/research only",
                    "deidentified", true,
                    "rawImagesIncluded", false,
                    "humanReviewRequired", true,
                    "notClinicalDiagnosis", true
                ),
                "notes", context.notes()
            ));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar JSON de export");
        }
    }

    private String toCsv(ExportContext context) {
        List<String> rows = new ArrayList<>();
        rows.add("Caso;Run;Medicion;Valor;Unidad;Fuente;Estado;Outlier;Confianza");
        for (MeasurementSaveDto measurement : context.measurements()) {
            rows.add(String.join(";",
                csv(context.caseId()),
                csv(context.runId()),
                csv(measurement.label()),
                csv(measurement.value()),
                csv(measurement.unit()),
                csv(measurement.source()),
                csv(measurement.status()),
                csv(Boolean.TRUE.equals(measurement.outlier()) ? "si" : "no"),
                csv(measurement.confidence() == null ? "" : Math.round(measurement.confidence() * 100.0) + "%")
            ));
        }
        return "\ufeff" + String.join("\n", rows);
    }

    private String toHtml(ExportContext context) {
        String rows = context.measurements().stream().map(measurement -> "<tr><td><strong>" + html(measurement.label()) + "</strong></td><td>" + html(measurement.value()) + " " + html(measurement.unit()) + "</td><td>" + html(measurement.source()) + "</td><td>" + html(measurement.status()) + "</td><td>" + (Boolean.TRUE.equals(measurement.outlier()) ? "Si" : "No") + "</td></tr>").reduce("", String::concat);
        return "<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\"><title>PFI Lumbar MRI - " + html(context.caseId()) + "</title><style>body{font-family:Inter,Segoe UI,Arial,sans-serif;margin:32px;color:#102033;background:#f8fafc}.report{background:#fff;border:1px solid #d8e6f4;border-radius:18px;box-shadow:0 18px 50px rgba(15,23,42,.08);padding:28px;max-width:1080px;margin:auto}.eyebrow{text-transform:uppercase;letter-spacing:.08em;color:#64748b;font-size:12px;font-weight:800}h1{margin:6px 0 4px;font-size:28px}.muted{color:#64748b}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:20px 0}.card{border:1px solid #e2e8f0;border-radius:14px;padding:12px;background:#f8fbff}.card strong{display:block;font-size:20px;margin-top:4px}table{border-collapse:collapse;width:100%;margin-top:16px}th{background:#eef4fb;text-align:left;font-size:12px;text-transform:uppercase;color:#475569}td,th{border-bottom:1px solid #e2e8f0;padding:12px;vertical-align:top}.notice{border:1px solid #bae6fd;background:#f0f9ff;border-radius:14px;padding:12px;margin-top:18px}.footer{font-size:12px;color:#64748b;margin-top:20px}@media print{body{background:#fff;margin:0}.report{box-shadow:none;border:0}}</style></head><body><main class=\"report\"><div class=\"eyebrow\">PFI Lumbar MRI Analysis Platform</div><h1>Resumen academico de revision</h1><p class=\"muted\">Caso " + html(context.caseId()) + " · Run " + html(context.runId()) + " · Generado " + html(context.generatedAt()) + "</p><section class=\"grid\"><div class=\"card\">Estado<strong>" + html(context.reviewStatus()) + "</strong></div><div class=\"card\">Modo<strong>" + html(context.inferenceMode()) + "</strong></div><div class=\"card\">Mediciones<strong>" + context.measurements().size() + "</strong></div><div class=\"card\">Readiness<strong>" + html(context.modelReadiness()) + "</strong></div></section><section class=\"notice\"><strong>Alcance:</strong> uso academico/investigacion, datos de-identificados, requiere revision profesional y no constituye diagnostico clinico. No incluye imagenes crudas.</section><h2>Mediciones revisadas</h2><table><thead><tr><th>Medicion</th><th>Valor</th><th>Fuente</th><th>Estado</th><th>Outlier</th></tr></thead><tbody>" + rows + "</tbody></table><h2>Notas</h2><p>" + html(defaultString(context.notes(), "Sin notas registradas.")) + "</p><div class=\"footer\">Subject ref de-identificado: " + html(context.subjectRef()) + " · Study date: " + html(context.studyDate()) + " · Modelo: " + html(context.modelKey()) + "</div></main></body></html>";
    }

    private String normalizeFormat(String value) {
        String normalized = value == null ? "json" : value.trim().toLowerCase();
        if (!ALLOWED_EXPORT_FORMATS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid export format");
        }
        return normalized;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeFile(String value) {
        return defaultString(value, "review").replaceAll("[^a-zA-Z0-9-_]", "-");
    }

    private String csv(Object value) {
        String text = String.valueOf(value == null ? "" : value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private String html(Object value) {
        return String.valueOf(value == null ? "" : value)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private record ExportContext(
        String runId,
        String caseId,
        String subjectRef,
        String studyDate,
        String plane,
        String modelKey,
        String modelVersion,
        String inferenceMode,
        String modelReadiness,
        String notes,
        String reviewer,
        String reviewStatus,
        List<MeasurementSaveDto> measurements,
        Instant generatedAt
    ) {}
}
