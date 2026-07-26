package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneAssetV2Dto(
    String assetName,
    String role,
    String contentType,
    Boolean generated,
    String relativePath
) {}
