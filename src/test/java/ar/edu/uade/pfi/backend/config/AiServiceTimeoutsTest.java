package ar.edu.uade.pfi.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Preguntarle al modulo de IA si esta vivo y pedirle que segmente un volumen son dos cosas
 * distintas y merecen dos paciencias distintas.
 *
 * <p>Compartian techo: un {@code /health} esperaba los mismos tres minutos que una corrida
 * multiplanar, asi que un modulo colgado retenia un hilo del pool por cada request de un
 * endpoint cuyo unico proposito es responder rapido o no responder.
 */
class AiServiceTimeoutsTest {

    private AiServiceProperties withTimeout(Integer seconds) {
        return new AiServiceProperties("http://ai:8000", seconds, "v2");
    }

    @Test
    void elDiagnosticoNoEsperaLoMismoQueLaInferencia() {
        AiServiceProperties properties = withTimeout(180);

        assertEquals(180, properties.resolvedTimeoutSeconds());
        assertEquals(10, properties.resolvedDiagnosticTimeoutSeconds());
    }

    @Test
    void porDefectoTampocoLoComparten() {
        AiServiceProperties properties = withTimeout(null);

        assertEquals(180, properties.resolvedTimeoutSeconds());
        assertTrue(properties.resolvedDiagnosticTimeoutSeconds() < properties.resolvedTimeoutSeconds());
    }

    /**
     * Si alguien configura un techo total mas corto que el de diagnostico, manda el total:
     * el de diagnostico acota, nunca amplia.
     */
    @Test
    void elDiagnosticoNuncaSuperaAlTechoGeneral() {
        // 30 es el piso que impone resolvedTimeoutSeconds.
        AiServiceProperties properties = withTimeout(5);

        assertEquals(30, properties.resolvedTimeoutSeconds());
        assertTrue(properties.resolvedDiagnosticTimeoutSeconds() <= properties.resolvedTimeoutSeconds());
    }
}
