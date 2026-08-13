package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.dto.CreatePatientRequestDto;
import ar.edu.uade.pfi.backend.dto.PatientDetailDto;
import ar.edu.uade.pfi.backend.dto.PatientStudySummaryDto;
import ar.edu.uade.pfi.backend.dto.PatientSummaryDto;
import ar.edu.uade.pfi.backend.dto.UpdatePatientRequestDto;
import ar.edu.uade.pfi.backend.service.PatientService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patients")
@Tag(name = "Pacientes", description = "Identidades longitudinales de-identificadas.")
public class PatientController {
  private final PatientService patientService;
  private final ProfessionalAccessAuditService accessAuditService;
  private final RoleAuthorizationService authorizationService;

  public PatientController(
      PatientService patientService,
      ProfessionalAccessAuditService accessAuditService,
      RoleAuthorizationService authorizationService) {
    this.patientService = patientService;
    this.accessAuditService = accessAuditService;
    this.authorizationService = authorizationService;
  }

  @Operation(summary = "Crea un paciente de-identificado")
  @ApiResponse(responseCode = "201", description = "Paciente creado.")
  @ApiResponse(
      responseCode = "400",
      description = "Referencia invalida.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Referencia duplicada.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping
  public ResponseEntity<PatientDetailDto> create(
      @RequestBody(required = false) CreatePatientRequestDto request,
      HttpServletRequest httpRequest) {
    authorizationService.requireProfessional(httpRequest, "patients");
    PatientDetailDto created =
        patientService.create(request, actor(httpRequest), traceId(httpRequest));
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @Operation(summary = "Busca pacientes por prefijo de referencia")
  @ApiResponse(responseCode = "200", description = "Pacientes en orden determinista.")
  @ApiResponse(
      responseCode = "400",
      description = "Query o limite invalido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @GetMapping
  public List<PatientSummaryDto> search(
      @RequestParam(required = false, defaultValue = "") String query,
      @RequestParam(required = false) Integer limit,
      HttpServletRequest request) {
    accessAuditService.record(
        request, "access_patient_worklist", "Lista de pacientes de-identificados consultada");
    return patientService.search(query, limit);
  }

  @Operation(summary = "Obtiene un paciente de-identificado")
  @ApiResponse(responseCode = "200", description = "Paciente encontrado.")
  @ApiResponse(
      responseCode = "400",
      description = "UUID invalido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Paciente inexistente.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @GetMapping("/{patientId}")
  public PatientDetailDto get(@PathVariable String patientId, HttpServletRequest request) {
    accessAuditService.record(
        request,
        "access_patient_detail",
        "Detalle de paciente de-identificado consultado patientId=" + patientId);
    return patientService.get(patientId);
  }

  @Operation(summary = "Corrige la referencia de un paciente")
  @ApiResponse(responseCode = "200", description = "Referencia actualizada.")
  @ApiResponse(
      responseCode = "400",
      description = "UUID, payload o referencia invalida.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Paciente inexistente.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "Referencia duplicada.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PatchMapping("/{patientId}")
  public PatientDetailDto update(
      @PathVariable String patientId,
      @RequestBody(required = false) UpdatePatientRequestDto request,
      HttpServletRequest httpRequest) {
    authorizationService.requireProfessional(httpRequest, patientId);
    return patientService.update(patientId, request, actor(httpRequest), traceId(httpRequest));
  }

  @Operation(summary = "Lista los estudios asociados al paciente")
  @ApiResponse(responseCode = "200", description = "Studies ordenados por fecha clinica.")
  @ApiResponse(
      responseCode = "400",
      description = "UUID invalido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Paciente inexistente.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @GetMapping("/{patientId}/studies")
  public List<PatientStudySummaryDto> studies(
      @PathVariable String patientId, HttpServletRequest request) {
    accessAuditService.record(
        request,
        "access_patient_studies",
        "Estudios de paciente de-identificado consultados patientId=" + patientId);
    return patientService.studies(patientId);
  }

  private String actor(HttpServletRequest request) {
    Object claimsValue = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    if (claimsValue instanceof TokenService.Claims claims && !claims.subject().isBlank()) {
      return claims.subject();
    }
    Object actor = request.getAttribute(AuthFilter.ACTOR_ID_ATTRIBUTE);
    return actor instanceof String value && !value.isBlank() ? value : "backend";
  }

  private String traceId(HttpServletRequest request) {
    Object trace = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    if (trace instanceof String value && !value.isBlank()) return value;
    String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
    return header == null || header.isBlank() ? "unavailable" : header;
  }
}
