package ar.edu.uade.pfi.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pfi.ai-service")
public record AiServiceProperties(String baseUrl, Integer timeoutSeconds) {
    public int resolvedTimeoutSeconds() {
        return timeoutSeconds == null ? 60 : timeoutSeconds;
    }
}
