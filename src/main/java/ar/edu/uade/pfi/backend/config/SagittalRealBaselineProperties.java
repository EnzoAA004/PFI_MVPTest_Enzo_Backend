package ar.edu.uade.pfi.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pfi.sagittal.expected")
public record SagittalRealBaselineProperties(
    @NotBlank String modelKey,
    @NotBlank String modelVersion,
    @NotBlank String modelSha256,
    @NotBlank String releaseId,
    @NotBlank String releaseContentSha256,
    @NotBlank String releaseManifestSha256
) {
}
