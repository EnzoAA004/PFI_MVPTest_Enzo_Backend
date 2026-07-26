package ar.edu.uade.pfi.backend.config;

import java.util.Locale;

public enum AiMultiplanarContractVersion {
    V1,
    V2;

    public static final AiMultiplanarContractVersion DEFAULT = V1;

    public static AiMultiplanarContractVersion fromConfigValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return DEFAULT;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "v1" -> V1;
            case "v2" -> V2;
            default -> throw new IllegalStateException(
                "Valor invalido para pfi.ai-service.multiplanar-contract-version: '" + rawValue
                    + "'. Valores permitidos: v1, v2."
            );
        };
    }
}
