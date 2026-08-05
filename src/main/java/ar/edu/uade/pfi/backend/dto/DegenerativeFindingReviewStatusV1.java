package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DegenerativeFindingReviewStatusV1 {
    PENDING("pending"),
    ACCEPTED("accepted"),
    OBSERVED("observed"),
    REJECTED("rejected"),
    EDITED("edited");

    private final String wireValue;

    DegenerativeFindingReviewStatusV1(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static DegenerativeFindingReviewStatusV1 fromJson(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DegenerativeFindingReviewStatusV1 item : values()) {
            if (item.wireValue.equals(normalized)) return item;
        }
        throw new IllegalArgumentException("Unsupported degenerative finding review status");
    }
}
