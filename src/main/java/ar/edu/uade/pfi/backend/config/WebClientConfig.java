package ar.edu.uade.pfi.backend.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {
  /**
   * Techo para abrir la conexion TCP, independiente del techo de respuesta.
   *
   * <p>Antes se usaba el mismo valor para las dos cosas: 180 segundos esperando el apreton de
   * manos. Un connect que tarda mas de unos segundos no va a completarse —el servicio no esta, o la
   * red no llega—, y estirarlo solo retiene un hilo del pool sin cambiar el desenlace. Cuanto tarda
   * la inferencia no tiene nada que ver con cuanto tarda en abrirse un socket.
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  @Bean
  public WebClient aiWebClient(AiServiceProperties properties) {
    // El techo de respuesta queda en el mas largo de todos —una corrida multiplanar—,
    // y cada llamada lo acota hacia abajo segun lo que pide. Ver AiServiceClient.
    int timeoutSeconds = properties.resolvedTimeoutSeconds();
    HttpClient httpClient =
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
            .responseTimeout(Duration.ofSeconds(timeoutSeconds));

    return WebClient.builder()
        /*
         * Spring's default codec limit is 256 KB, and a raw slice is larger than
         * that: 384x384 int16 is 294 KB. The limit turned every raw-slice request
         * into a 502 while the smaller PNG previews went through, which read like
         * an AI Module outage rather than a buffer ceiling. 16 MB covers a slice
         * with room to spare and still bounds a runaway response.
         */
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .baseUrl(properties.resolvedBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .filter(traceIdPropagationFilter())
        .build();
  }

  private ExchangeFilterFunction traceIdPropagationFilter() {
    return ExchangeFilterFunction.ofRequestProcessor(
        request -> {
          String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
          if (traceId == null || traceId.isBlank()) {
            return Mono.just(request);
          }
          return Mono.just(
              ClientRequest.from(request).header(TraceIdFilter.TRACE_ID_HEADER, traceId).build());
        });
  }
}
