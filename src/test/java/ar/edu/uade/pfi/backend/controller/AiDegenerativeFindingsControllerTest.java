package ar.edu.uade.pfi.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.dto.AiSubarticularPredictRequestDto;
import ar.edu.uade.pfi.backend.dto.SubarticularPredictionResponseDto;
import ar.edu.uade.pfi.backend.service.AiMultiplanarUpstreamException;
import ar.edu.uade.pfi.backend.service.DegenerativeFindingsV1Validator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * La ruta que le faltaba a P10.6. Verifica que el backend valide lo que puede validar por
 * si solo, que no deje pasar una respuesta que no cumple el contrato clinico, y que un
 * modulo de IA sin checkpoint se traduzca a un 503 con codigo estable en vez de un 500.
 */
class AiDegenerativeFindingsControllerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc(AiServiceOperations ai) {
        return MockMvcBuilders
            .standaloneSetup(new AiDegenerativeFindingsController(ai, new DegenerativeFindingsV1Validator(), null))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private static final String VALID_REQUEST = """
        {"inputId":"input-axial-1","instanceNumber":12,"x":128.5,"y":96.25,"side":"right","level":"L4-L5"}
        """;

    /** El mismo fixture que ejercita el test de cable del cliente. */
    private static String okBody() throws Exception {
        return java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/contracts/ai-module-subarticular-predict.json")
        );
    }

    private SubarticularPredictionResponseDto okResponse() throws Exception {
        return objectMapper.readValue(okBody(), SubarticularPredictionResponseDto.class);
    }

    @Test
    void devuelveLasTresProbabilidadesYNormalizaElPedidoAlContratoDelModuloDeIa() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
        Mockito.when(ai.predictSubarticular(Mockito.any())).thenReturn(okResponse());

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.degenerativeFindings.schemaVersion").value("pfi.degenerative-findings.v1"))
            .andExpect(jsonPath("$.degenerativeFindings.findings[0].classification.probabilities.normal_mild").value(0.30))
            .andExpect(jsonPath("$.degenerativeFindings.findings[0].classification.probabilities.moderate").value(0.62))
            .andExpect(jsonPath("$.degenerativeFindings.findings[0].classification.probabilities.severe").value(0.08))
            .andExpect(jsonPath("$.degenerativeFindings.findings[0].localization.researchOnly").value(true))
            .andExpect(jsonPath("$.humanReviewRequired").value(true));

        ArgumentCaptor<AiSubarticularPredictRequestDto> captor = ArgumentCaptor.forClass(AiSubarticularPredictRequestDto.class);
        Mockito.verify(ai).predictSubarticular(captor.capture());
        // El enum del backend viaja como el valor de cable que espera el modulo de IA.
        org.junit.jupiter.api.Assertions.assertEquals("right", captor.getValue().side());
        org.junit.jupiter.api.Assertions.assertEquals("L4-L5", captor.getValue().level());
    }

    @Test
    void rechazaUnNivelFueraDelCatalogoSinLlamarAlModuloDeIa() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("L4-L5", "L6-L7")))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(ai);
    }

    @Test
    void rechazaUnaCoordenadaNegativaSinLlamarAlModuloDeIa() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("128.5", "-3.0")))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(ai);
    }

    @Test
    void rechazaUnInstanceNumberNegativoSinLlamarAlModuloDeIa() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("\"instanceNumber\":12", "\"instanceNumber\":-1")))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(ai);
    }

    /**
     * El rechazo no depende del ObjectMapper: lo hace el controller contra el JsonNode. Por
     * eso vale probarlo acá y ademas, con el mapper real, en
     * AiDegenerativeFindingsRealObjectMapperTest — que es el que probaria de verdad si
     * alguien algun dia "simplifica" esto ligando directo al DTO.
     */
    @Test
    void rechazaUnCampoDeMasEnElPedido() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("\"level\":\"L4-L5\"", "\"level\":\"L4-L5\",\"radius\":8")))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(ai);
    }

    @Test
    void rechazaUnLadoQueNoEsIzquierdoNiDerecho() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST.replace("\"right\"", "\"anterior\"")))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(ai);
    }

    @Test
    void sinCheckpointDevuelve503YNoUn500() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
        Mockito.when(ai.predictSubarticular(Mockito.any())).thenThrow(new AiMultiplanarUpstreamException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AI_SUBARTICULAR_UNAVAILABLE",
            "El clasificador de hallazgos degenerativos no esta disponible.",
            "ai-trace-1"
        ));

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AI_SUBARTICULAR_UNAVAILABLE"));
    }

    /**
     * Un 200 con un hallazgo que no cumple el contrato no se muestra a medias: probabilidades
     * que no suman 1 al lado de una barra son indistinguibles de unas que si suman.
     */
    @Test
    void unaRespuestaQueNoCumpleElContratoClinicoNoLlegaAlVisor() throws Exception {
        AiServiceOperations ai = Mockito.mock(AiServiceOperations.class);
        String roto = okBody().replace("\"severe\": 0.08", "\"severe\": 0.80");
        Mockito.when(ai.predictSubarticular(Mockito.any()))
            .thenReturn(objectMapper.readValue(roto, SubarticularPredictionResponseDto.class));

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isBadGateway());
    }

    /** El cliente por defecto de la interfaz lanza UnsupportedOperationException: eso es un 503, no un 500. */
    @Test
    void unClienteSinLaOperacionDevuelve503() throws Exception {
        AiServiceOperations ai = new AiServiceOperations() {
            @Override public java.util.Map<String, Object> health() { return java.util.Map.of(); }
            @Override public Object models() { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> verifyModels() { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> warmup() { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> runPipeline(ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto request) { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> getAgentReport(String runId) { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> getAgentReportSummary(String runId) { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> getRecentAgentReports(int limit) { return java.util.Map.of(); }
        };

        mockMvc(ai).perform(post("/api/ai/degenerative-findings/subarticular")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
            .andExpect(status().isServiceUnavailable());
    }
}
