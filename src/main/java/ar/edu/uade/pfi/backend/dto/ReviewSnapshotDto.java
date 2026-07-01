package ar.edu.uade.pfi.backend.dto;

import java.util.List;
import java.util.Map;

public record ReviewSnapshotDto(
    List<ReviewStatusDto> reviews,
    Map<String, List<MeasurementSaveDto>> measurementsByRunId,
    List<AuditEventDto> auditTrail
) {}
