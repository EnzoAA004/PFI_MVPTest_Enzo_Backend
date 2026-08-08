package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DegenerativeFindingLocalizationSourceV1 {
  SLICE_INDEX("slice_index"),
  EXTERNAL_COORDINATE("external_coordinate"),
  MODEL_GENERATED_ROI("model_generated_roi"),
  NOT_AVAILABLE("not_available");

  private final String wireValue;

  DegenerativeFindingLocalizationSourceV1(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static DegenerativeFindingLocalizationSourceV1 fromJson(String value) {
    if (value == null) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (DegenerativeFindingLocalizationSourceV1 item : values()) {
      if (item.wireValue.equals(normalized)) return item;
    }
    throw new IllegalArgumentException("Unsupported degenerative finding localization source");
  }
}
