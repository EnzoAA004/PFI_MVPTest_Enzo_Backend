package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReviewStoreService {
    private final Map<String, ReviewStatusDto> store = new HashMap<>();

    public ReviewStatusDto findOrDefault(String runId) {
        return store.getOrDefault(runId, new ReviewStatusDto(runId, "pendiente", "", Instant.now()));
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        ReviewStatusDto status = new ReviewStatusDto(runId, request.status(), request.notes(), Instant.now());
        store.put(runId, status);
        return status;
    }
}
