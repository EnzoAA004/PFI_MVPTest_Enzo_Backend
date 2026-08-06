package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

public record InputResource(
    String id,
    String studyId,
    String plane,
    String inputId,
    String format,
    long size,
    /** Series description as the study declared it: {@code t2_tse_sag_384}. */
    String description,
    /** {@code t1}, {@code t2} or {@code unknown}. */
    String weighting,
    int sliceCount,
    /** Series whose slices do not share a plane: a localizer. Not a volume. */
    boolean multiplanar,
    /** A console screenshot or reformat rather than acquired image data. */
    boolean derived,
    /**
     * Whether this series could be the input of an inference run.
     *
     * <p>False covers three reasons that look the same on screen and are not: there is
     * no model for its weighting (axial T1), it is not a single-plane volume (a
     * localizer), or it is not acquired data (a console capture).
     */
    boolean analyzable,
    Instant createdAt
) {
    /** Pre-BE-015 shape: the two analysed planes, before the study kept every series. */
    public InputResource(String id, String studyId, String plane, String inputId, String format, long size, Instant createdAt) {
        this(id, studyId, plane, inputId, format, size, "", "", 0, false, false, true, createdAt);
    }

    public InputResource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyId, "studyId");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(inputId, "inputId");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(createdAt, "createdAt");
        if (description == null) description = "";
        if (weighting == null) weighting = "";
        if (size < 0) throw new IllegalArgumentException("size must be non-negative");
        if (sliceCount < 0) throw new IllegalArgumentException("sliceCount must be non-negative");
    }
}
