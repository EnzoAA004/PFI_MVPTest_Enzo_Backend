package ar.edu.uade.pfi.backend.dto;

public record StudyUploadInputDto(
    String inputId,
    /**
     * Position in {@code seriesFound} of the series this plane is analysed from.
     *
     * <p>A position and not the DICOM SeriesInstanceUID: that UID leads back to the
     * source study in the originating PACS, so it does not leave a de-identified
     * pipeline. The reader still needs to see which of the listed series produced the
     * results — matching by description would mark the wrong one in a study with two
     * series that share it — and an index into this same response says it without
     * carrying anything back to the origin.
     */
    Integer seriesIndex,
    String plane,
    String format,
    long size,
    String description,
    String weighting,
    Integer sliceCount
) {}
