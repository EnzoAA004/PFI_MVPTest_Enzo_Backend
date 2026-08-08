package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Respuesta del clasificador subarticular. El mismo record se usa para deserializar lo que manda el
 * modulo de IA y para responderle al frontend: el backend no transforma nada acá, solo valida y
 * reexpone, y partirlo en dos DTOs identicos seria ceremonia sin contenido.
 *
 * <p><b>Sobre la estrictitud de Jackson.</b> El sobre es {@code ignoreUnknown = true} y el
 * contenido clinico no. La distincion es deliberada: {@code degenerativeFindings} es el contrato
 * versionado que el revisor lee y que {@code DegenerativeFindingsV1Validator} verifica campo por
 * campo, asi que ahi un campo desconocido es una senal real de que el contrato cambio. El sobre, en
 * cambio, transporta metadata operativa que el modulo de IA va a seguir enriqueciendo (device,
 * tiempos, avisos), y romper una corrida entera porque apareció una clave nueva ahi es exactamente
 * lo que ya paso con {@code discLevels}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubarticularPredictionResponseDto(
    DegenerativeFindingsV1Dto degenerativeFindings,
    Model model,
    Boolean humanReviewRequired,
    Boolean notClinicalDiagnosis,
    Boolean autonomousDiagnosis,
    List<String> warnings) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Model(String modelId, String checkpointSha256, String device) {}
}
