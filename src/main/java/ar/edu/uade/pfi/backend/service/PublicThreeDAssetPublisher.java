package ar.edu.uade.pfi.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PublicThreeDAssetPublisher {
  private static final String MESH_ASSET = "lumbar-3d-mesh.json";
  private static final String MESH_CONTENT_TYPE = "application/json";

  public Map<String, Object> publish(String multiplanarRunId, Map<String, Object> threeD) {
    if (threeD == null || threeD.isEmpty()) return threeD;
    Map<String, Object> published = copySanitized(threeD);
    boolean enabled = Boolean.TRUE.equals(published.get("enabled"));
    Object assetsValue = threeD.get("assets");
    if (!enabled || !(assetsValue instanceof List<?> assets)) {
      published.put("assets", List.of());
      return published;
    }
    List<Map<String, Object>> publicAssets = new ArrayList<>();
    for (Object value : assets) {
      if (!(value instanceof Map<?, ?> raw)) continue;
      Map<String, Object> asset = copySanitized(raw);
      if (!MESH_ASSET.equals(asset.get("assetName"))) continue;
      if (!MESH_CONTENT_TYPE.equalsIgnoreCase(String.valueOf(asset.get("contentType")))) continue;
      if (!Boolean.TRUE.equals(asset.get("generated"))) continue;
      asset.remove("relativePath");
      asset.put("url", meshUrl(multiplanarRunId));
      publicAssets.add(asset);
    }
    published.put("assets", publicAssets);
    return published;
  }

  public String meshUrl(String multiplanarRunId) {
    return "/api/ai/assets/" + multiplanarRunId + "/workspace/" + MESH_ASSET;
  }

  private Map<String, Object> copySanitized(Map<?, ?> source) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      String key = String.valueOf(entry.getKey());
      if ("relativePath".equals(key)) continue;
      result.put(key, sanitize(entry.getValue()));
    }
    return result;
  }

  private Object sanitize(Object value) {
    if (value instanceof Map<?, ?> map) return copySanitized(map);
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>();
      for (Object item : list) result.add(sanitize(item));
      return result;
    }
    return value;
  }
}
