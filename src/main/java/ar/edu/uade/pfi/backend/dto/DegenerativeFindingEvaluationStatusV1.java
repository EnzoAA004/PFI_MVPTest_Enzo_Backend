package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DegenerativeFindingEvaluationStatusV1 {
    EVALUATED("evaluated"),
    NOT_EVALUATED("not_evaluated"),
    UNSUPPORTED("unsupported"),
    FAILED("failed");

    private final String wireValue;

    DegenerativeFindingEvaluationStatusV1(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static DegenerativeFindingEvaluationStatusV1 fromJson(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DegenerativeFindingEvaluationStatusV1 item : values()) {
            if (item.wireValue.equals(normalized)) return item;
        }
        throw new IllegalArgumentException("Unsupported degenerative finding evaluation status");
    }
}
