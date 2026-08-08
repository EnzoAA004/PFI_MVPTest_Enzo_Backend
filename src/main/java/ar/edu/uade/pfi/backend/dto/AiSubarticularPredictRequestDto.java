package ar.edu.uade.pfi.backend.dto;

/**
 * Cuerpo exacto que espera {@code POST /degenerative-findings/subarticular/predict} del modulo de
 * IA.
 *
 * <p>Ese endpoint declara {@code extra="forbid"}: un campo de mas y rechaza el pedido entero. Por
 * eso este DTO es aparte de {@link SubarticularPredictionRequestDto} —el que recibe el backend— y
 * no se reusa: lo que el frontend puede mandarle al backend y lo que el backend puede mandarle al
 * modulo de IA son dos contratos distintos, y hacerlos uno solo ata cualquier campo nuevo de la API
 * publica a un rechazo aguas arriba.
 *
 * <p>{@code side} viaja como string y no como enum para que el valor de cable quede a la vista: el
 * modulo de IA espera {@code left}/{@code right} en minuscula.
 */
public record AiSubarticularPredictRequestDto(
    String inputId, int instanceNumber, double x, double y, String side, String level) {}
