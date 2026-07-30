package ar.edu.uade.pfi.backend.service;

import java.util.Set;
import java.util.regex.Pattern;

final class AssetNamePolicy {
    static final String PNG_CONTENT_TYPE = "image/png";
    static final String JSON_CONTENT_TYPE = "application/json";
    static final Set<String> LEGACY_PNG_ASSETS = Set.of("input.png", "overlay.png", "mask-preview.png");
    static final String MESH_ASSET = "lumbar-3d-mesh.json";
    private static final Pattern SLICE_PREVIEW = Pattern.compile("^slice-\\d{3}\\.png$");
    private static final Pattern SLICE_OVERLAY = Pattern.compile("^slice-\\d{3}-overlay\\.png$");

    private AssetNamePolicy() {}

    static boolean isAllowedPublicAsset(String assetName, String plane, String contentType) {
        if (!isSafeBasename(assetName)) return false;
        if (MESH_ASSET.equals(assetName)) {
            return "workspace".equals(plane) && startsWithContentType(contentType, JSON_CONTENT_TYPE);
        }
        if ("workspace".equals(plane)) return false;
        return isPngAsset(assetName) && startsWithContentType(contentType, PNG_CONTENT_TYPE);
    }

    static boolean isPngAsset(String assetName) {
        return LEGACY_PNG_ASSETS.contains(assetName) || isSlicePreview(assetName) || isSliceOverlay(assetName);
    }

    static boolean isSlicePreview(String assetName) {
        return assetName != null && SLICE_PREVIEW.matcher(assetName).matches();
    }

    static boolean isSliceOverlay(String assetName) {
        return assetName != null && SLICE_OVERLAY.matcher(assetName).matches();
    }

    static boolean isSafeBasename(String assetName) {
        if (assetName == null || assetName.isBlank()) return false;
        if (assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) return false;
        String lower = assetName.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("^[a-z][a-z0-9+.-]*:.*")) return false;
        if (lower.matches("^[a-z]:.*")) return false;
        return assetName.equals(org.springframework.util.StringUtils.getFilename(assetName));
    }

    private static boolean startsWithContentType(String actual, String expected) {
        return actual != null && actual.toLowerCase(java.util.Locale.ROOT).startsWith(expected);
    }
}
