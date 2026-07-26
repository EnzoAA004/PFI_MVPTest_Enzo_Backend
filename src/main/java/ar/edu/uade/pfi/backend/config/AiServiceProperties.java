package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pfi.ai-service")
public record AiServiceProperties(String baseUrl, Integer timeoutSeconds, String multiplanarContractVersion) {
    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:8000" : baseUrl;
    }

    public int resolvedTimeoutSeconds() {
        return timeoutSeconds == null ? 180 : Math.max(timeoutSeconds, 30);
    }

    public AiMultiplanarContractVersion resolvedMultiplanarContractVersion() {
        return AiMultiplanarContractVersion.fromConfigValue(multiplanarContractVersion);
    }

    @PostConstruct
    void validateMultiplanarContractVersion() {
        resolvedMultiplanarContractVersion();
    }
}
