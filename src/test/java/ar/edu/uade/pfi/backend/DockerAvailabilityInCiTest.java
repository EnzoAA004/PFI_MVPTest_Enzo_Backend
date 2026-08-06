package ar.edu.uade.pfi.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

/**
 * Los tests de Postgres corren sobre Testcontainers y estan anotados con
 * {@code @Testcontainers(disabledWithoutDocker = true)}: en una maquina sin Docker se
 * saltean en vez de romper el build. Eso es lo que queremos localmente y es exactamente
 * lo que no queremos en CI.
 *
 * <p>Sin este chequeo, un runner que pierda Docker dejaria de correr las ~52 pruebas de
 * persistencia y el build seguiria en verde: la senal mas peligrosa de todas, porque no
 * se distingue de un build que si las corrio.
 *
 * <p>Este test solo existe donde la variable {@code CI} esta definida, que es lo que
 * hace GitHub Actions. En local no aparece.
 */
@EnabledIfEnvironmentVariable(named = "CI", matches = ".+")
class DockerAvailabilityInCiTest {

    @Test
    void dockerEstaDisponibleParaLosTestsDePersistencia() {
        assertTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker no esta disponible en CI: los tests de Testcontainers se saltearian en silencio "
                + "y el build pasaria sin haber probado la persistencia."
        );
    }
}
