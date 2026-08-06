package ar.edu.uade.pfi.backend.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Pedido de clasificacion subarticular sobre un punto marcado por el profesional.
 *
 * <p>El modelo no localiza el receso por su cuenta: necesita que alguien le diga sobre que
 * coordenada mirar. Por eso el pedido lleva el corte y el punto, y por eso el hallazgo que
 * vuelve se marca como {@code external_coordinate} y {@code researchOnly}.
 *
 * <p><b>Este record no se liga directo desde el controller.</b> El rechazo de campos de mas
 * lo hace {@code AiDegenerativeFindingsController.strictRequest} contra el JsonNode crudo,
 * porque bajo el ObjectMapper que autoconfigura Spring Boot un
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} sobre un record no rechaza nada.
 * Este DTO es el resultado ya validado de ese parseo, y existe para que el resto del
 * controller trabaje sobre tipos y no sobre un arbol JSON.
 */
public record SubarticularPredictionRequestDto(
    @NotBlank String inputId,
    /** Corte axial del que se toma el ROI, tal como lo numera la serie. */
    @NotNull @Min(0) Integer instanceNumber,
    /**
     * Coordenada en pixeles del DICOM, no del visor. La conversion desde la base
     * normalizada del visor es responsabilidad del frontend, que es el unico que conoce
     * el tamano con el que dibujo el corte.
     */
    @NotNull Double x,
    @NotNull Double y,
    /** {@code left} o {@code right}. */
    @NotNull DegenerativeFindingSideV1 side,
    /** Nivel discal, dentro del catalogo de DegenerativeFindingsV1Validator. */
    @NotBlank String level
) {}
