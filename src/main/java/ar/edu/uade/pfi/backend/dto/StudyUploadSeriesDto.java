package ar.edu.uade.pfi.backend.dto;

public record StudyUploadSeriesDto(
    String plane,
    String description,
    String weighting,
    Integer sliceCount
) {}
