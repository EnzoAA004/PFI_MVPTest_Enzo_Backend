package ar.edu.uade.pfi.backend.dto;

public record StudyUploadInputDto(
    String inputId,
    String plane,
    String format,
    long size,
    String description,
    String weighting,
    Integer sliceCount
) {}
