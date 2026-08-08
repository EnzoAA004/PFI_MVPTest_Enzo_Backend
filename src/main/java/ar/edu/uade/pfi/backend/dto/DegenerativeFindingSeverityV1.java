package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DegenerativeFindingSeverityV1 {
  NORMAL_MILD("normal_mild"),
  MODERATE("moderate"),
  SEVERE("severe");

  private final String wireValue;

  DegenerativeFindingSeverityV1(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static DegenerativeFindingSeverityV1 fromJson(String value) {
    if (value == null) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (DegenerativeFindingSeverityV1 item : values()) {
      if (item.wireValue.equals(normalized)) return item;
    }
    throw new IllegalArgumentException("Unsupported degenerative finding severity");
  }
}
