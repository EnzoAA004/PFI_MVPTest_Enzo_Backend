package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.util.ResponseNormalizer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Exceptions;

@RestController
@RequestMapping("/api/ai/pipeline")
public class AiContractController {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
        new ParameterizedTypeReference<>() {};

    private final WebClient aiWebClient;
    private final Duration timeout;

    public AiContractController(WebClient aiWebClient, AiServiceProperties properties) {
        this.aiWebClient = aiWebClient;
        this.timeout = Duration.ofSeconds(properties.resolvedTimeoutSeconds());
    }

    @GetMapping("/schema")
    public Map<String, Object> pipelineSchema() {
        try {
            Map<String, Object> response = aiWebClient.get()
                .uri("/pipeline/schema")
                .retrieve()
                .bodyToMono(MAP_RESPONSE)
                .block(timeout);
            Map<String, Object> normalized = ResponseNormalizer.normalizeMap(response);
            normalized.put("proxiedByBackend", true);
            normalized.put("aiModuleAvailable", true);
            normalized.put("humanReviewRequired", true);
            normalized.put("notClinicalDiagnosis", true);
            return normalized;
        } catch (RuntimeException ex) {
            return fallbackSchema(ex);
        }
    }

    @GetMapping("/schema/verify")
    public Map<String, Object> pipelineSchemaVerification() {
        try {
            Map<String, Object> response = aiWebClient.get()
                .uri("/pipeline/schema/verify")
                .retrieve()
                .bodyToMono(MAP_RESPONSE)
                .block(timeout);
            Map<String, Object> normalized = ResponseNormalizer.normalizeMap(response);
            normalized.put("proxiedByBackend", true);
            normalized.put("aiModuleAvailable", true);
            normalized.put("humanReviewRequired", true);
            normalized.put("notClinicalDiagnosis", true);
            return normalized;
        } catch (RuntimeException ex) {
            return fallbackVerification(ex);
        }
    }

    private Map<String, Object> fallbackSchema(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        String message = unwrapped.getMessage() == null ? "unknown error" : compactMessage(unwrapped.getMessage());
        return Map.ofEntries(
            Map.entry("schemaVersion", "visual-review-contract-v1"),
            Map.entry("status", "degraded_fallback"),
            Map.entry("purpose", "Contrato minimo servido por backend cuando el AI Module no esta disponible."),
            Map.entry("generatedBy", "pfi-backend.ai-contract-fallback"),
            Map.entry("schemaHash", "backend-fallback-visual-review-contract-v1"),
            Map.entry("proxiedByBackend", true),
            Map.entry("aiModuleAvailable", false),
            Map.entry("degradedMode", true),
            Map.entry("humanReviewRequired", true),
            Map.entry("notClinicalDiagnosis", true),
            Map.entry("message", "AI Module is not available: " + message),
            Map.entry("rootFields", Map.ofEntries(
                Map.entry("runId", "Identificador estable de la corrida tecnica."),
                Map.entry("caseId", "Identificador de caso de-identificado."),
                Map.entry("patientId", "Referencia de-identificada del sujeto."),
                Map.entry("studyDate", "Fecha del estudio usada en la demo."),
                Map.entry("plane", "Plano principal solicitado: sagittal o axial."),
                Map.entry("modelKey", "Clave del modelo registrado."),
                Map.entry("series", "Lista de series disponibles para el viewer."),
                Map.entry("masks", "Mascaras editables con contornos por serie y slice."),
                Map.entry("landmarks", "Puntos de referencia derivados del contrato visual."),
                Map.entry("measurementValues", "Mediciones normalizadas para IA vs Reviewer."),
                Map.entry("aiOutput", "Estado explicable de la salida IA/contrato."),
                Map.entry("modelArtifact", "Estado del artifact .pt esperado para inferencia real."),
                Map.entry("quality", "Resumen cuantitativo del contrato visual."),
                Map.entry("metadata", "Trazabilidad extendida de frontend/backend/AI Module."),
                Map.entry("review", "Estado de revision profesional cuando backend lo adjunta.")
            )),
            Map.entry("aiOutput", Map.of(
                "status", "contract_ready|real_inference|degraded",
                "inferenceMode", "contract|mock|real",
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true
            )),
            Map.entry("quality", Map.of(
                "maskCount", "number",
                "landmarkCount", "number",
                "measurementCount", "number",
                "measurementsDerivedFromContours", "boolean"
            )),
            Map.entry("guarantees", List.of(
                "El contrato siempre declara humanReviewRequired=true.",
                "El contrato siempre declara notClinicalDiagnosis=true.",
                "Las mediciones son editables por Reviewer.",
                "La inferencia real no se declara disponible si falta el artifact .pt.",
                "El fallback backend conserva la forma principal del contrato si el AI Module no responde."
            ))
        );
    }

    private Map<String, Object> fallbackVerification(RuntimeException ex) {
        Throwable unwrapped = Exceptions.unwrap(ex);
        String message = unwrapped.getMessage() == null ? "unknown error" : compactMessage(unwrapped.getMessage());
        return Map.ofEntries(
            Map.entry("schemaVersion", "visual-review-contract-v1"),
            Map.entry("schemaHash", "backend-fallback-visual-review-contract-v1"),
            Map.entry("recomputedHash", "unavailable"),
            Map.entry("hashValid", false),
            Map.entry("governanceValid", true),
            Map.entry("valid", false),
            Map.entry("generatedBy", "pfi-backend.ai-contract-fallback"),
            Map.entry("proxiedByBackend", true),
            Map.entry("aiModuleAvailable", false),
            Map.entry("degradedMode", true),
            Map.entry("humanReviewRequired", true),
            Map.entry("notClinicalDiagnosis", true),
            Map.entry("missingRootFields", List.of()),
            Map.entry("message", "AI Module contract verification is not available: " + message)
        );
    }

    private String compactMessage(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "..." : oneLine;
    }
}
