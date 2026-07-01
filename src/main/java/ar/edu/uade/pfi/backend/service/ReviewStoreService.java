package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewStoreService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("pendiente", "aceptado", "observado", "descartado");

    private final Map<String, ReviewStatusDto> store = new ConcurrentHashMap<>();

    public ReviewStatusDto findOrDefault(String runId) {
        return store.getOrDefault(runId, new ReviewStatusDto(runId, "pendiente", "", "", Instant.now()));
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        String normalizedStatus = request.status().trim().toLowerCase();
        if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid review status");
        }

        ReviewStatusDto status = new ReviewStatusDto(
            runId,
            normalizedStatus,
            request.notes() == null ? "" : request.notes(),
            request.reviewer() == null ? "" : request.reviewer(),
            Instant.now()
        );
        store.put(runId, status);
        return status;
    }
}
