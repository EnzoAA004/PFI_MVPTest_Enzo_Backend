package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "pfi.ai-service")
public record AiServiceProperties(
    String baseUrl,
    Integer timeoutSeconds,
    String multiplanarContractVersion,
    Long studyUploadMaxBytes) {
  /**
   * Constructor canonico, marcado para el enlace de configuracion.
   *
   * <p>Sin la marca, el arranque falla: un record con dos constructores deja a Spring sin saber
   * cual usar para enlazar las propiedades, busca uno por defecto, no lo encuentra y no levanta el
   * contexto. El otro constructor existe solo por comodidad de las pruebas, que no necesitan
   * declarar el techo de subida.
   */
  @ConstructorBinding
  public AiServiceProperties(
      String baseUrl,
      Integer timeoutSeconds,
      String multiplanarContractVersion,
      Long studyUploadMaxBytes) {
    this.baseUrl = baseUrl;
    this.timeoutSeconds = timeoutSeconds;
    this.multiplanarContractVersion = multiplanarContractVersion;
    this.studyUploadMaxBytes = studyUploadMaxBytes;
  }

  public AiServiceProperties(
      String baseUrl, Integer timeoutSeconds, String multiplanarContractVersion) {
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
    return studyUploadMaxBytes == null || studyUploadMaxBytes <= 0
        ? 200L * 1024L * 1024L
        : studyUploadMaxBytes;
  }

  @PostConstruct
  void validateMultiplanarContractVersion() {
    resolvedMultiplanarContractVersion();
  }
}
