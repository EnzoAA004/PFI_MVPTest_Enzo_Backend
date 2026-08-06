package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.error.ApiErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Traduce los codigos de error del endpoint subarticular del modulo de IA
 * ({@code SUBARTICULAR_*}) a un par estable de status HTTP + {@link ApiErrorCode}.
 *
 * <p>Mismo criterio que {@link AiMultiplanarV2ErrorCodeMapper}, y por las mismas razones:
 * el string que manda el modulo de IA nunca se usa para construir el codigo ni el mensaje
 * publico del backend. Solo esta tabla fija lo hace. Un codigo que el modulo agregue
 * manana colapsa a un bucket generico en vez de filtrarse al contrato publico.
 *
 * <p>Va aparte del mapper multiplanar y no adentro porque son dos contratos distintos del
 * modulo de IA: mezclarlos haria que un codigo de un contrato resolviera contra el otro.
 */
final class AiSubarticularErrorCodeMapper {

    private static final Map<String, AiMultiplanarV2ErrorCodeMapper.Mapped> KNOWN_CODES = Map.ofEntries(
        // Codigos genericos del manejador de errores del modulo de IA (code_for_status).
        // Esta ruta los emite antes de llegar al clasificador: el inputId puede no estar
        // registrado, o estar registrado pero no ser una serie axial. Sin estas entradas
        // un "input no encontrado" llegaba al visor como un 502 del que no se puede
        // deducir que el problema es el input y no el modelo.
        Map.entry("NOT_FOUND",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.NOT_FOUND, ApiErrorCode.AI_INPUT_NOT_FOUND)),
        Map.entry("CONFLICT",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.CONFLICT, ApiErrorCode.AI_SUBARTICULAR_INVALID_INPUT)),
        Map.entry("BAD_REQUEST",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_SUBARTICULAR_INVALID_INPUT)),
        Map.entry("VALIDATION_ERROR",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_SUBARTICULAR_INVALID_INPUT)),

        // El .pt no esta configurado o no esta en el entorno: se resuelve montandolo.
        Map.entry("SUBARTICULAR_CHECKPOINT_UNAVAILABLE",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.AI_SUBARTICULAR_UNAVAILABLE)),
        // El artefacto esta pero no es el esperado. No se reintenta: hay que corregir el release.
        Map.entry("SUBARTICULAR_CHECKPOINT_HASH_MISMATCH",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_SUBARTICULAR_CHECKPOINT_INVALID)),
        Map.entry("SUBARTICULAR_CHECKPOINT_INCOMPATIBLE",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_SUBARTICULAR_CHECKPOINT_INVALID)),
        // La coordenada o el corte no sirven. Es culpa del pedido, asi que vuelve como 400.
        Map.entry("SUBARTICULAR_INVALID_INPUT",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_SUBARTICULAR_INVALID_INPUT)),
        Map.entry("SUBARTICULAR_RUNTIME_ERROR",
            new AiMultiplanarV2ErrorCodeMapper.Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_SUBARTICULAR_RUNTIME_ERROR))
    );

    private AiSubarticularErrorCodeMapper() {}

    static AiMultiplanarV2ErrorCodeMapper.Mapped resolve(String upstreamCode) {
        if (upstreamCode == null) return AiMultiplanarV2ErrorCodeMapper.UNKNOWN;
        AiMultiplanarV2ErrorCodeMapper.Mapped mapped = KNOWN_CODES.get(upstreamCode.trim());
        return mapped == null ? AiMultiplanarV2ErrorCodeMapper.UNKNOWN : mapped;
    }
}
