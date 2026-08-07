package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.DiscDegenerativeFindingsV1Dto;
import ar.edu.uade.pfi.backend.dto.DiscDegenerativeMultitaskRequestDto;
import ar.edu.uade.pfi.backend.service.DiscDegenerativeFindingsV1Validator;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai/degenerative-findings/disc-multitask")
public class AiDiscDegenerativeFindingsController {
    private static final List<String> LEVEL_ORDER = List.of("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");
    private static final Set<String> LEVELS = Set.copyOf(LEVEL_ORDER);

    private final AiServiceOperations aiServiceClient;
    private final StudyRunService studyRunService;
    private final RoleAuthorizationService authorizationService;
    private final DiscDegenerativeFindingsV1Validator validator;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiDiscDegenerativeFindingsController(
        AiServiceOperations aiServiceClient,
        StudyRunService studyRunService,
        RoleAuthorizationService authorizationService,
        DiscDegenerativeFindingsV1Validator validator,
        ObjectMapper objectMapper
    ) {
        this.aiServiceClient = aiServiceClient;
        this.studyRunService = studyRunService;
        this.authorizationService = authorizationService;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/predict")
    public DiscDegenerativeFindingsV1Dto predict(@Valid @RequestBody DiscDegenerativeMultitaskRequestDto request, HttpServletRequest httpRequest) {
        authorizationService.requireProfessional(httpRequest, request.multiplanarRunId());
        StudyRun run = studyRunService.findRunByMultiplanarRunId(request.multiplanarRunId().trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run no encontrado."));
        List<String> levels = normalizeLevels(request.levels());
        Map<String, Object> runtimeInputs = runtimeInputs(run.metricsSnapshot());
        requireValidatedRuntimeInputs(runtimeInputs);

        Map<String, Object> aiRequest = new LinkedHashMap<>();
        aiRequest.put("caseId", text(run.metricsSnapshot().get("caseId")));
        aiRequest.put("levels", levels.stream().map(level -> levelRequest(level, runtimeInputs)).toList());

        DiscDegenerativeFindingsV1Dto response = aiServiceClient.predictDiscDegenerative(aiRequest);
        validator.validate(response);
        persistDiscFindings(run, response);
        return response;
    }

    private List<String> normalizeLevels(List<String> requested) {
        List<String> levels = requested == null || requested.isEmpty() ? LEVEL_ORDER : requested;
        for (String level : levels) {
            if (!LEVELS.contains(level)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Nivel lumbar P10.7 invalido.");
            }
        }
        return levels;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runtimeInputs(Map<String, Object> snapshot) {
        Object value = snapshot.get("discDegenerativeRuntimeInputs");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            return copy;
        }
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "La corrida no contiene entradas P10.7 con paridad de preprocesamiento validada."
        );
    }

    private void requireValidatedRuntimeInputs(Map<String, Object> runtimeInputs) {
        if (!Boolean.TRUE.equals(runtimeInputs.get("preprocessingParityValidated"))
            || !Boolean.TRUE.equals(runtimeInputs.get("automaticDiscLocalizationValidated"))) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "P10.7 requiere localizacion discal y paridad de preprocesamiento validadas."
            );
        }
    }

    private Map<String, Object> levelRequest(String level, Map<String, Object> runtimeInputs) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("level", level);
        item.put("sourceSeries", runtimeInputs.getOrDefault("sourceSeries", List.of()));
        item.put("localization", runtimeInputs.getOrDefault("localization", Map.of(
            "source", "segmentation_derived_disc_level",
            "researchOnly", true,
            "automaticAnatomicalLocalizationValidated", false
        )));
        return item;
    }

    private void persistDiscFindings(StudyRun run, DiscDegenerativeFindingsV1Dto response) {
        Map<String, Object> updated = new LinkedHashMap<>(run.metricsSnapshot());
        updated.put("discDegenerativeFindings", objectMapper.convertValue(
            response.discDegenerativeFindings(),
            new TypeReference<Map<String, Object>>() {}
        ));
        updated.put("discDegenerativeFindingsOriginal", objectMapper.convertValue(
            response.discDegenerativeFindings(),
            new TypeReference<Map<String, Object>>() {}
        ));
        studyRunService.updateRunMetricsSnapshot(run.multiplanarRunId(), updated);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
