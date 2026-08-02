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
    @Bean
    public WebClient aiWebClient(AiServiceProperties properties) {
        int timeoutSeconds = properties.resolvedTimeoutSeconds();
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutSeconds * 1000)
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
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
            if (traceId == null || traceId.isBlank()) {
                return Mono.just(request);
            }
            return Mono.just(ClientRequest.from(request)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .build());
        });
    }
}
