package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.service.SagittalRealBaselineContractValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/models")
public class AiModelSyncController {
    private final AiServiceOperations aiServiceClient;
    private final RoleAuthorizationService authorizationService;
    private final SagittalRealBaselineContractValidator sagittalContractValidator;

    public AiModelSyncController(AiServiceOperations aiServiceClient) {
        this(aiServiceClient, null, null);
    }

    public AiModelSyncController(AiServiceOperations aiServiceClient, RoleAuthorizationService authorizationService) {
        this(aiServiceClient, authorizationService, null);
    }

    @Autowired
    public AiModelSyncController(
        AiServiceOperations aiServiceClient,
        RoleAuthorizationService authorizationService,
        SagittalRealBaselineContractValidator sagittalContractValidator
    ) {
        this.aiServiceClient = aiServiceClient;
        this.authorizationService = authorizationService;
        this.sagittalContractValidator = sagittalContractValidator;
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam(defaultValue = "false") boolean force, HttpServletRequest request) {
        if (authorizationService != null) {
            authorizationService.requireAdmin(request, "models.sync");
        }
        Map<String, Object> response = aiServiceClient.syncModels(force);
        if (sagittalContractValidator != null) {
            sagittalContractValidator.validateSagittalSync(response);
            response.put("sagittalReadyForRealInference", true);
        }
        response.putIfAbsent("proxiedByBackend", true);
        response.putIfAbsent("humanReviewRequired", true);
        response.putIfAbsent("notClinicalDiagnosis", true);
        return response;
    }
}
