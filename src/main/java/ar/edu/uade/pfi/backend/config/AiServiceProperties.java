package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pfi.ai-service")
public record AiServiceProperties(String baseUrl, Integer timeoutSeconds, String multiplanarContractVersion, Long studyUploadMaxBytes) {
    public AiServiceProperties(String baseUrl, Integer timeoutSeconds, String multiplanarContractVersion) {
        this(baseUrl, timeoutSeconds, multiplanarContractVersion, null);
    }

    public String resolvedBaseUrl() {
        return baseUrl == null || baseUrl.isBlank() ? "http://localhost:8000" : baseUrl;
    }

    public int resolvedTimeoutSeconds() {
        return timeoutSeconds == null ? 180 : Math.max(timeoutSeconds, 30);
    }

    public AiMultiplanarContractVersion resolvedMultiplanarContractVersion() {
        return AiMultiplanarContractVersion.fromConfigValue(multiplanarContractVersion);
    }

    public long resolvedStudyUploadMaxBytes() {
        return studyUploadMaxBytes == null || studyUploadMaxBytes <= 0 ? 200L * 1024L * 1024L : studyUploadMaxBytes;
    }

    @PostConstruct
    void validateMultiplanarContractVersion() {
        resolvedMultiplanarContractVersion();
    }
}
