package ar.edu.uade.pfi.backend.dto;

import java.util.List;

/**
 * Backend wrapper for the P10.7 product call.
 *
 * <p>{@code multiplanarRunId} is backend-only and is never sent to the AI Module. It identifies the
 * immutable run snapshot that will receive the P10.7 prediction.
 */
public record DiscDegenerativeProductRequestDto(
    String multiplanarRunId, String caseId, List<DiscSegmentationSourceDto> sources) {}
