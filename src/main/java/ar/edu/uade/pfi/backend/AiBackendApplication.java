package ar.edu.uade.pfi.backend;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiBackendApplication {
    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(AiBackendApplication.class, args);
    }
}
