package ar.edu.uade.pfi.backend.dto;

public record StudyUploadSeriesDto(
    /**
     * Identifier of the stored series, so the reader can display it.
     *
     * <p>Every series of the study is kept now, not only the two the models run on. A
     * lumbar study carries sagittal T1 and T2, axial T1 and T2, sometimes coronals and
     * a localizer; the AI reads two of them and the doctor reads all of them. This is
     * what lets the viewer ask for the slices of the other five.
     */
    String inputId,
    String plane,
    String description,
    String weighting,
    Integer sliceCount,
    /** Series whose slices do not share a plane: a localizer. Not a volume. */
    boolean multiplanar,
    /** A console screenshot or reformat rather than acquired image data. */
    boolean derived,
    /**
     * Whether this series could be the input of an inference run.
     *
     * <p>False covers three different reasons that look the same on screen and are
     * not: there is no model for its weighting (axial T1), it is not a single-plane
     * volume (a localizer), or it is not acquired data (a console capture).
     */
    boolean analyzable
) {}
