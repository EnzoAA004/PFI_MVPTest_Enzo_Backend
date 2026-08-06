package ar.edu.uade.pfi.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.DegenerativeFindingsV1Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El endpoint del modulo de IA declara {@code extra="forbid"}: un campo de mas y rechaza el
 * pedido entero. Si el backend lo dejara pasar, el cliente se enteraria dos saltos despues
 * y con un codigo que no dice nada.
 *
 * <p>Va contra el ObjectMapper real que administra Spring Boot y no contra uno armado a
 * mano en un {@code standaloneSetup}, por el mismo motivo que
 * {@code ProfessionalActivationRealObjectMapperTest}: el mapper de standalone no es el que
 * usa la aplicacion, asi que probar el rechazo ahi no prueba nada sobre produccion.
 *
 * <p>{@code AuthFilter} queda afuera de la rebanada: necesita Postgres y tokens, y no tiene
 * nada que ver con si el DTO rechaza un campo desconocido.
 */
@WebMvcTest(
    controllers = AiDegenerativeFindingsController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuthFilter.class)
)
@Import(DegenerativeFindingsV1Validator.class)
class AiDegenerativeFindingsRealObjectMapperTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiServiceOperations aiServiceClient;

    @MockBean
    private AuditService auditService;

    private static final String VALID_REQUEST = """
        {"inputId":"input-axial-1","instanceNumber":12,"x":128.5,"y":96.25,"side":"right","level":"L4-L5"}
        """;

    @Test
    void rechazaUnCampoDeMasSinLlamarAlModuloDeIa() throws Exception {
        mockMvc.perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("\"level\":\"L4-L5\"", "\"level\":\"L4-L5\",\"radius\":8")))
            .andExpect(status().isBadRequest());

        verify(aiServiceClient, never()).predictSubarticular(any());
    }

    /** El mismo cuerpo sin el campo de mas tiene que pasar la deserializacion. */
    @Test
    void aceptaElPedidoExacto() throws Exception {
        mockMvc.perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().is(502));

        // Llego al cliente: el 502 sale de que el mock devuelve null, no de la deserializacion.
        verify(aiServiceClient).predictSubarticular(any());
    }
}
