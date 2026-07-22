package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.SagittalRealBaselineProperties;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PipelineRunRequestNormalizer {
    private final SagittalRealBaselineProperties expected;

    public PipelineRunRequestNormalizer(SagittalRealBaselineProperties expected) {
        this.expected = expected;
    }

    public boolean isRealBaselineRequested(PipelineRunRequestDto request) {
        return "real_baseline".equals(normalized(metadata(request).get("inferenceMode")));
    }

    public boolean isStrictRealBaseline(PipelineRunRequestDto request) {
        return isRealBaselineRequested(request) && Boolean.FALSE.equals(booleanValue(metadata(request).get("allowContractFallback")));
    }

    public PipelineRunRequestDto normalizePipelineRequest(PipelineRunRequestDto request) {
        Map<String, Object> metadata = new LinkedHashMap<>(metadata(request));
        if (!isRealBaselineRequested(request)) {
            return new PipelineRunRequestDto(request.caseId(), request.plane(), request.modelKey(), request.inputPath(), request.inputId(), metadata);
        }

        metadata.put("inferenceMode", "real_baseline");
        metadata.putIfAbsent("requestedInferenceMode", "real_baseline");
        metadata.putIfAbsent("allowContractFallback", false);
        boolean strict = Boolean.FALSE.equals(booleanValue(metadata.get("allowContractFallback")));
        String plane = normalized(request.plane());
        String inputPath = request.inputPath() == null ? "" : request.inputPath().trim();
        String inputId = request.inputId() == null ? "" : request.inputId().trim();

        if (strict) {
            if (!"sagittal".equals(plane)) {
                throw badRequest("real_baseline estricto solo esta habilitado para plane=sagittal.");
            }
            if (inputId.startsWith("demo/")) {
                throw badRequest("inputId no puede ser demo en real_baseline estricto.");
            }
            if (!inputPath.isBlank() && inputPath.startsWith("demo/")) {
                throw badRequest("inputPath demo no esta permitido para real_baseline estricto.");
            }
            if (inputId.isBlank() && inputPath.isBlank()) {
                throw badRequest("inputId o inputPath es obligatorio para real_baseline estricto.");
            }
            if (!inputId.isBlank() && !inputPath.isBlank()) {
                throw badRequest("Enviar solamente inputId o inputPath, no ambos.");
            }
            return new PipelineRunRequestDto(
                request.caseId(),
                "sagittal",
                expected.modelKey(),
                inputPath.isBlank() ? null : inputPath,
                inputId.isBlank() ? null : inputId,
                metadata
            );
        }

        return new PipelineRunRequestDto(request.caseId(), request.plane(), request.modelKey(), request.inputPath(), request.inputId(), metadata);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private Map<String, Object> metadata(PipelineRunRequestDto request) {
        return request.metadata() == null ? Map.of() : request.metadata();
    }

    private String normalized(Object value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String normalized = normalized(value);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return null;
    }
}
