package ar.edu.uade.pfi.backend.dto;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import java.util.List;

public record StudyDetailResponseDto(
    String status,
    StudyRowDto study,
    List<InputResource> inputs,
    List<StudyRunDetailDto> runs,
    List<DomainAuditEvent> auditTrail,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis) {}
