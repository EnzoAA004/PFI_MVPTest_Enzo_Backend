package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DegenerativeFindingSourceSeriesRoleV1 {
  SAGITTAL_T2("sagittal_t2"),
  SAGITTAL_T1("sagittal_t1"),
  AXIAL_T2("axial_t2");

  private final String wireValue;

  DegenerativeFindingSourceSeriesRoleV1(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static DegenerativeFindingSourceSeriesRoleV1 fromJson(String value) {
    if (value == null) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (DegenerativeFindingSourceSeriesRoleV1 item : values()) {
      if (item.wireValue.equals(normalized)) return item;
    }
    throw new IllegalArgumentException("Unsupported source series role");
  }
}
