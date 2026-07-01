package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

public record AiModelDto(
    String key,
    String name,
    String version,
    Map<String, Object> metadata
) {}
