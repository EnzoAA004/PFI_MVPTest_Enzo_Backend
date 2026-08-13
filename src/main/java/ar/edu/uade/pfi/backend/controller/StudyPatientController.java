package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.dto.StudyPatientAssignmentRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyPatientAssignmentResponseDto;
import ar.edu.uade.pfi.backend.service.PatientService;
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/studies")
@Tag(name = "Estudios", description = "Asociacion longitudinal de Studies de-identificados.")
public class StudyPatientController {
  private final PatientService patientService;
  private final RoleAuthorizationService authorizationService;

  public StudyPatientController(
      PatientService patientService, RoleAuthorizationService authorizationService) {
    this.patientService = patientService;
    this.authorizationService = authorizationService;
  }

  @Operation(
      summary = "Asocia o reasigna un Study a un Patient",
      description =
          "Requiere expectedPatientId para concurrencia optimista. No permite desasociar.")
  @ApiResponse(responseCode = "200", description = "Asociacion aplicada o ya existente.")
  @ApiResponse(
      responseCode = "400",
      description = "UUID, payload o reason code invalido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "Patient o Study inexistente.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "El estado actual no coincide con expectedPatientId.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PutMapping("/{caseId}/patient")
  public StudyPatientAssignmentResponseDto assign(
      @PathVariable String caseId,
      @RequestBody(required = false) StudyPatientAssignmentRequestDto request,
      HttpServletRequest httpRequest) {
    authorizationService.requireProfessional(httpRequest, caseId);
    return patientService.assignStudy(caseId, request, actor(httpRequest), traceId(httpRequest));
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
