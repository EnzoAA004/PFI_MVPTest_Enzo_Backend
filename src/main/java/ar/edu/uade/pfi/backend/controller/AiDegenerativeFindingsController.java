package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.error.ApiErrorCode;
import ar.edu.uade.pfi.backend.dto.AiSubarticularPredictRequestDto;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingSideV1;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingsV1Dto;
import ar.edu.uade.pfi.backend.dto.SubarticularPredictionRequestDto;
import ar.edu.uade.pfi.backend.dto.SubarticularPredictionResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.DegenerativeFindingsV1Validator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Puente entre el visor y el clasificador subarticular del modulo de IA.
 *
 * <p>El frontend nunca llama a FastAPI: la unica via es Backend -> modulo de IA, y esta
 * ruta es la que faltaba para cerrar ese camino. Sin ella el panel de hallazgos solo podia
 * mostrar lo que ya venia adentro de una corrida multiplanar.
 *
 * <p><b>No persiste como hallazgo, pero si se audita.</b> Son dos cosas distintas y vale
 * separarlas.
 *
 * <p>La prediccion no entra al dominio del estudio: es puntual y {@code researchOnly}, y
 * guardarla como hallazgo exige una tabla propia y una decision de gobernanza clinica
 * sobre que significa un hallazgo asistido en la historia de un paciente.
 *
 * <p>Pero si queda un evento de auditoria con lo que el sistema mostro: la clase elegida,
 * sus probabilidades y el SHA del checkpoint. Sin eso, cuando el revisor acepta o corrige
 * un hallazgo lo unico auditable seria "el medico acepto algo", que no permite reconstruir
 * nada. El evento describe una accion del sistema, no una conclusion sobre el paciente, y
 * no es fuente del informe clinico.
 */
@RestController
@RequestMapping("/api/ai/degenerative-findings")
public class AiDegenerativeFindingsController {
    private final AiServiceOperations aiServiceClient;
    private final DegenerativeFindingsV1Validator validator;
    @Nullable
    private final AuditService auditService;

    public AiDegenerativeFindingsController(AiServiceOperations aiServiceClient) {
        this(aiServiceClient, new DegenerativeFindingsV1Validator(), null);
    }

    @Autowired
    public AiDegenerativeFindingsController(
        AiServiceOperations aiServiceClient,
        DegenerativeFindingsV1Validator validator,
        @Nullable AuditService auditService
    ) {
        this.aiServiceClient = aiServiceClient;
        this.validator = validator;
        this.auditService = auditService;
    }

    private static final Set<String> ALLOWED_FIELDS = Set.of("inputId", "instanceNumber", "x", "y", "side", "level");

    /**
     * No liga directo a {@code @Valid @RequestBody SubarticularPredictionRequestDto}, por el
     * mismo motivo documentado en {@code AuthController.updateProfessionalActivation}: bajo
     * el ObjectMapper que autoconfigura Spring Boot, el
     * {@code @JsonIgnoreProperties(ignoreUnknown = false)} de un record <b>no</b> rechaza
     * campos de mas —Boot deja {@code FAIL_ON_UNKNOWN_PROPERTIES} apagado, y la anotacion
     * sola no alcanza—. Se verifico empiricamente contra el mapper real; con un
     * {@code new ObjectMapper()} a mano el mismo DTO si rechaza, que es como el problema
     * pasa desapercibido en los tests.
     *
     * <p>Importa acá y no es ceremonia: el endpoint del modulo de IA declara
     * {@code extra="forbid"}. Un campo que el backend deja pasar vuelve como un rechazo
     * aguas arriba, dos saltos despues y con un codigo que no explica nada.
     */
    @PostMapping("/subarticular")
    public SubarticularPredictionResponseDto predictSubarticular(@RequestBody JsonNode body) {
        SubarticularPredictionRequestDto request = strictRequest(body);
        String level = request.level().trim();
        requireValidLevel(level);
        requireFiniteCoordinate(request.x(), "x");
        requireFiniteCoordinate(request.y(), "y");

        AiSubarticularPredictRequestDto upstream = new AiSubarticularPredictRequestDto(
            request.inputId().trim(),
            request.instanceNumber(),
            request.x(),
            request.y(),
            request.side().wireValue(),
            level
        );

        SubarticularPredictionResponseDto response;
        try {
            response = aiServiceClient.predictSubarticular(upstream);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.AI_SUBARTICULAR_UNAVAILABLE.publicMessage()
            );
        }

        requireUsableResponse(response);
        audit(upstream, response);
        return response;
    }

    /** Rechaza cualquier campo fuera del contrato antes de construir el DTO. */
    private SubarticularPredictionRequestDto strictRequest(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud invalida.");
        }
        Iterator<String> fieldNames = body.fieldNames();
        while (fieldNames.hasNext()) {
            if (!ALLOWED_FIELDS.contains(fieldNames.next())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud invalida.");
            }
        }
        String inputId = text(body, "inputId");
        if (inputId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inputId es obligatorio.");
        }
        JsonNode instanceNumber = body.get("instanceNumber");
        if (instanceNumber == null || !instanceNumber.isIntegralNumber() || instanceNumber.asInt() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instanceNumber debe ser un entero no negativo.");
        }
        DegenerativeFindingSideV1 side;
        try {
            side = DegenerativeFindingSideV1.fromJson(text(body, "side"));
        } catch (IllegalArgumentException ex) {
            side = null;
        }
        if (side == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "side debe ser left o right.");
        }
        return new SubarticularPredictionRequestDto(
            inputId,
            instanceNumber.asInt(),
            number(body, "x"),
            number(body, "y"),
            side,
            text(body, "level")
        );
    }

    private String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private double number(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || !node.isNumber()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " debe ser un numero.");
        }
        return node.asDouble();
    }

    /**
     * El nivel se valida acá y no aguas arriba porque es lo unico del pedido que el
     * backend puede juzgar por si solo: el catalogo de niveles ya vive en el validador
     * del contrato.
     *
     * <p>El rango de {@code x}/{@code y} contra el tamano del corte se valida en el
     * modulo de IA, que es el unico que abre el DICOM y conoce sus dimensiones. El
     * backend solo descarta lo que no es un numero: inventar acá un limite que no puede
     * conocer daria falsos rechazos sobre cortes grandes y falsa confianza sobre chicos.
     */
    private void requireValidLevel(String level) {
        if (!DegenerativeFindingsV1Validator.VALID_LEVELS.contains(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "level invalido.");
        }
    }

    private void requireFiniteCoordinate(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " debe ser un numero finito no negativo.");
        }
    }

    /**
     * Un hallazgo mal formado no se muestra a medias. Si el modulo de IA responde 200 con
     * algo que no cumple el contrato, eso es una violacion de contrato aguas arriba y vale
     * un 502, no una pantalla con barras de probabilidad a las que no se les puede creer.
     */
    private void requireUsableResponse(SubarticularPredictionResponseDto response) {
        if (response == null || response.degenerativeFindings() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_INVALID_RESPONSE.publicMessage());
        }
        try {
            validator.validate(response.degenerativeFindings());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_INVALID_RESPONSE.publicMessage());
        }
        if (response.degenerativeFindings().findings() == null || response.degenerativeFindings().findings().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_INVALID_RESPONSE.publicMessage());
        }
    }

    /**
     * Queda registro de que se pidio una clasificacion y sobre que corte, nunca de la
     * coordenada exacta ni del resultado: es una prediccion no persistida y de alcance de
     * investigacion, y dejar su salida en el log de auditoria la convertiria de hecho en
     * un registro clinico por la puerta de atras.
     */
    private void audit(AiSubarticularPredictRequestDto request, SubarticularPredictionResponseDto response) {
        if (auditService == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("inputId", request.inputId());
        metadata.put("instanceNumber", request.instanceNumber());
        metadata.put("level", request.level());
        metadata.put("side", request.side());
        metadata.put("modelId", response.model() == null ? "" : response.model().modelId());
        // El SHA del checkpoint es lo que ata el resultado a un modelo concreto. Sin el,
        // dentro de seis meses no se puede saber cual de dos versiones produjo esto.
        metadata.put("checkpointSha256", response.model() == null ? "" : response.model().checkpointSha256());
        metadata.putAll(predictionEvidence(response));
        metadata.put("researchOnly", true);
        // El evento describe lo que el sistema mostro, no una conclusion sobre el
        // paciente. Queda escrito en el propio evento para que no se lea como otra cosa.
        metadata.put("notClinicalDiagnosis", true);
        auditService.record("backend", "degenerative_findings.subarticular.requested", request.inputId(), null, metadata);
    }

    /**
     * Que se le mostro al profesional: la clase elegida y sus probabilidades.
     *
     * <p><b>Por que se audita el resultado y no solo el pedido.</b> Si el revisor acepta o
     * corrige un hallazgo y despues aparece una discrepancia, "el medico acepto algo" no es
     * auditable; "el sistema mostro moderada con 0,62 y el medico la acepto" si lo es. Es
     * ademas la unica evidencia para medir cuanto se le corrige al modelo.
     *
     * <p>Auditar no es persistir en la historia del paciente: este evento es un registro de
     * lo que hizo el sistema, con su propia retencion, y no es fuente del informe clinico.
     * Esa distincion es la que permite guardarlo sin convertirlo en un diagnostico por la
     * puerta de atras.
     */
    private Map<String, Object> predictionEvidence(SubarticularPredictionResponseDto response) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (response.degenerativeFindings() == null || response.degenerativeFindings().findings() == null) {
            return evidence;
        }
        DegenerativeFindingsV1Dto.DegenerativeFindingV1Dto finding = response.degenerativeFindings().findings().stream()
            .findFirst()
            .orElse(null);
        if (finding == null || finding.classification() == null) return evidence;

        evidence.put("label", finding.classification().label() == null
            ? "" : finding.classification().label().wireValue());
        DegenerativeFindingsV1Dto.Probabilities probabilities = finding.classification().probabilities();
        if (probabilities != null) {
            Map<String, Object> byClass = new LinkedHashMap<>();
            byClass.put("normal_mild", probabilities.normalMild());
            byClass.put("moderate", probabilities.moderate());
            byClass.put("severe", probabilities.severe());
            evidence.put("probabilities", byClass);
        }
        return evidence;
    }
}
