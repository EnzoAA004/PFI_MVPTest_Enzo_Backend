package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StudyMetadataDto(
    String subjectRef,
    LocalDate studyDate,
    String modality,
    String description,
    String reviewPriority
) {}
