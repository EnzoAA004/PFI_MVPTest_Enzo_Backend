package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSliceAssetV2Dto(
    String assetName,
    String role,
    String contentType,
    Boolean generated,
    String url
) {}
