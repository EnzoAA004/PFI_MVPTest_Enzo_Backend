package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.dto.ReviewerAnnotationDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads and replaces the reviewer's annotations for a run.
 *
 * <p>The reviewer's annotations are their own clinical work product, so nothing
 * here derives, completes or reinterprets them: a payload that cannot be placed on
 * an image (slice scope without a slice, an unknown scope, a bad timestamp) is
 * rejected with 400 rather than coerced into something storable. The domain record
 * enforces the same invariants, which is what keeps a malformed annotation out of
 * the database even if it arrives through another path.
 */
@Service
public class ReviewerAnnotationService {
    private final StudyRepository repository;

    public ReviewerAnnotationService(StudyRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<ReviewerAnnotationDto> find(String multiplanarRunId) {
        return repository.findAnnotationsByRunId(multiplanarRunId).stream().map(this::toDto).toList();
    }

    public List<ReviewerAnnotationDto> replace(String multiplanarRunId, List<ReviewerAnnotationDto> annotations) {
        List<ReviewerAnnotationDto> incoming = annotations == null ? List.of() : annotations;
        List<ReviewerAnnotation> domain = incoming.stream()
            .map(dto -> toDomain(multiplanarRunId, dto))
            .toList();
        try {
            return repository.replaceAnnotations(multiplanarRunId, domain).stream().map(this::toDto).toList();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Corrida no encontrada.");
        }
    }

    private ReviewerAnnotation toDomain(String multiplanarRunId, ReviewerAnnotationDto dto) {
        try {
            return new ReviewerAnnotation(
                identifier(dto.id()),
                multiplanarRunId,
                dto.scope(),
                dto.kind(),
                dto.plane(),
                dto.seriesId(),
                dto.sliceIndex(),
                dto.level(),
                dto.points(),
                dto.value(),
                dto.unit(),
                dto.text(),
                dto.author(),
                createdAt(dto.createdAt())
            );
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anotacion invalida: " + ex.getMessage());
        }
    }

    /**
     * Keeps the client's id when it is a UUID, mints one otherwise.
     *
     * <p>The reading room creates annotations locally (ids like {@code measure-<ts>})
     * and only persists on save, so the first round trip legitimately carries a
     * non-UUID id. Minting silently in that case is safe — the client reconciles
     * against the response — while forcing the client to pre-generate UUIDs would
     * break the local-first flow for no gain.
     */
    private String identifier(String id) {
        if (id == null || id.isBlank()) return UUID.randomUUID().toString();
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException ex) {
            return UUID.randomUUID().toString();
        }
    }

    private Instant createdAt(String value) {
        if (value == null || value.isBlank()) return Instant.now();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "createdAt no es una fecha ISO-8601.");
        }
    }

    private ReviewerAnnotationDto toDto(ReviewerAnnotation annotation) {
        return new ReviewerAnnotationDto(
            annotation.id(),
            annotation.scope(),
            annotation.kind(),
            annotation.plane(),
            annotation.seriesId(),
            annotation.sliceIndex(),
            annotation.level(),
            annotation.points(),
            annotation.value(),
            annotation.unit(),
            annotation.text(),
            annotation.author(),
            annotation.createdAt().toString()
        );
    }
}
