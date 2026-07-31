package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Wire shape of a reviewer annotation.
 *
 * <p>{@code id} and {@code createdAt} are echoed back rather than assigned by the
 * server: the reviewer's client creates annotations locally while reading and only
 * persists when they save, so the identity has to survive that round trip for the
 * client to reconcile what it already has on screen.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReviewerAnnotationDto(
    String id,
    String scope,
    String kind,
    String plane,
    String seriesId,
    Integer sliceIndex,
    String level,
    List<Map<String, Object>> points,
    Double value,
    String unit,
    String text,
    String author,
    String createdAt
) {}
