package ar.edu.uade.pfi.backend.dto;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Un campo desconocido en un payload del cliente se rechaza, y se rechaza con el
 * ObjectMapper que usa la aplicacion.
 *
 * <p><b>Por que existe este test.</b> ~25 DTOs de este repo llevan
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} como control deliberado, y varios
 * javadocs afirman que asi frenan payloads que intentan colar {@code roles},
 * {@code admin} o {@code password}. Se verifico empiricamente que <b>eso no era cierto</b>:
 * Spring Boot deja {@code FAIL_ON_UNKNOWN_PROPERTIES} apagado, y la anotacion sola no
 * fuerza el fallo —solo declina optar por ignorar—. La proteccion existia en el papel.
 *
 * <p>Con un {@code new ObjectMapper()} armado a mano el mismo DTO si rechaza, que es
 * exactamente como el problema pasaba desapercibido: un test de rechazo escrito sobre un
 * mapper propio pasa y no prueba nada sobre produccion. Por eso este test usa
 * {@code @JsonTest}, que trae el mapper autoconfigurado real.
 *
 * <p>La lista de campos peligrosos incluye los que pide el handoff de P10.7 §9.4:
 * ademas de los de escalada de privilegios, {@code checkpointPath}, {@code patientId} y
 * los UIDs DICOM, que nunca deben viajar en un request publico.
 */
@JsonTest
class UnknownFieldRejectionAuditTest {

    @Autowired
    private ObjectMapper mapper;

    /** Campos que un cliente podria intentar colar. Ninguno debe pasar. */
    private static final String[] DANGEROUS = {
        "roles", "admin", "authorities", "permissions", "verified", "approved",
        "passwordHash", "checkpointPath", "patientId", "PatientName",
        "StudyInstanceUID", "SeriesInstanceUID", "SOPInstanceUID",
    };

    @Test
    void elMapperDeLaAplicacionFallaAnteCamposDesconocidos() {
        // La bandera que hace que ignoreUnknown = false signifique algo. Si alguien la
        // apaga, los ~25 DTOs vuelven a estar desprotegidos en silencio.
        assertTrue(
            mapper.getDeserializationConfig().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
            "FAIL_ON_UNKNOWN_PROPERTIES esta apagado: ignoreUnknown = false no rechaza nada"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "roles", "admin", "authorities", "permissions", "verified", "approved", "passwordHash",
    })
    void elLoginRechazaCualquierCampoDeMas(String field) {
        String payload = """
            {"email":"doc@example.com","password":"Demo1234!","%s":"x"}
            """.formatted(field);

        assertThrows(UnrecognizedPropertyException.class,
            () -> mapper.readValue(payload, AuthDtos.LoginRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"roles", "admin", "approved", "verified", "licenseNumber2"})
    void elRegistroRechazaCualquierCampoDeMas(String field) {
        String payload = """
            {"fullName":"Ana Gomez","email":"doc@example.com","password":"Demo1234!","%s":"x"}
            """.formatted(field);

        assertThrows(UnrecognizedPropertyException.class,
            () -> mapper.readValue(payload, AuthDtos.RegisterRequest.class));
    }

    @Test
    void laActualizacionDeAjustesNoAceptaCamposDeMas() {
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(
            "{\"twoFactorEnabled\":true,\"roles\":[\"ADMIN\"]}", AuthDtos.SettingsRequest.class));
    }

    @Test
    void laAprobacionDeProfesionalesNoAceptaCamposDeMas() {
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(
            "{\"email\":\"doc@example.com\",\"approved\":true,\"admin\":true}",
            AuthDtos.ApprovalRequest.class));
    }

    @Test
    void elRefrescoDeSesionNoAceptaCamposDeMas() {
        assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue(
            "{\"refreshToken\":\"abc\",\"roles\":[\"ADMIN\"]}", AuthDtos.RefreshRequest.class));
    }

    /**
     * P10.7 §7: el request publico no debe llevar rutas de checkpoint, identificadores de
     * paciente ni UIDs DICOM. Se verifica sobre el DTO del pedido subarticular, que es el
     * request publico mas nuevo.
     */
    @ParameterizedTest
    @ValueSource(strings = {"checkpointPath", "patientId", "PatientName", "StudyInstanceUID", "SeriesInstanceUID"})
    void unPedidoDeClasificacionNoAceptaRutasNiIdentificadores(String field) {
        String payload = """
            {"inputId":"inp_1","instanceNumber":7,"x":10.0,"y":20.0,"side":"right","level":"L4-L5","%s":"x"}
            """.formatted(field);

        assertThrows(UnrecognizedPropertyException.class,
            () -> mapper.readValue(payload, SubarticularPredictionRequestDto.class));
    }

    /**
     * El payload exacto sigue entrando. Un rechazo que ademas rompe el caso bueno no es
     * una proteccion, es una caida.
     */
    @Test
    void losPayloadsExactosSiguenSiendoValidos() {
        assertDoesNotThrow(() -> {
            mapper.readValue("{\"email\":\"doc@example.com\",\"password\":\"Demo1234!\"}", AuthDtos.LoginRequest.class);
            mapper.readValue("{\"twoFactorEnabled\":true,\"onboardingCompleted\":false}", AuthDtos.SettingsRequest.class);
            mapper.readValue(
                "{\"inputId\":\"inp_1\",\"instanceNumber\":7,\"x\":10.0,\"y\":20.0,\"side\":\"right\",\"level\":\"L4-L5\"}",
                SubarticularPredictionRequestDto.class);
        });
    }

    /**
     * Lo que tiene que tolerar campos nuevos los declara, y sigue tolerando.
     *
     * <p>El sobre de una respuesta del modulo de IA transporta metadata operativa que va a
     * seguir creciendo. Romper una corrida entera porque aparecio una clave nueva ahi es
     * lo que ya paso con {@code discLevels}. La bandera global no puede volver a
     * provocarlo: {@code ignoreUnknown = true} manda por encima de ella.
     */
    @Test
    void lasRespuestasQueDeclaranTolerarCamposNuevosSiguenTolerandolos() {
        assertDoesNotThrow(() -> mapper.readValue("""
            {
              "degenerativeFindings": null,
              "humanReviewRequired": true,
              "campoQueElModuloDeIaAgregoManana": 42
            }
            """, SubarticularPredictionResponseDto.class));

        assertDoesNotThrow(() -> mapper.readValue("""
            {"status":"error","code":"X","message":"m","traceId":"t","campoNuevo":1}
            """, AiStructuredErrorV2Dto.class));
    }
}
