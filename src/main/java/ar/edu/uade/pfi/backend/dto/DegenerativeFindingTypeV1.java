package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DegenerativeFindingTypeV1 {
  CENTRAL_CANAL_STENOSIS("central_canal_stenosis"),
  NEURAL_FORAMINAL_NARROWING("neural_foraminal_narrowing"),
  SUBARTICULAR_STENOSIS("subarticular_stenosis");

  private final String wireValue;

  DegenerativeFindingTypeV1(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static DegenerativeFindingTypeV1 fromJson(String value) {
    if (value == null) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (DegenerativeFindingTypeV1 item : values()) {
      if (item.wireValue.equals(normalized)) return item;
    }
    throw new IllegalArgumentException("Unsupported degenerative finding type");
  }
}
