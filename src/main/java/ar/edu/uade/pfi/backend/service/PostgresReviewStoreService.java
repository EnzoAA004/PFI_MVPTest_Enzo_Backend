package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.AuditEventDto;
import ar.edu.uade.pfi.backend.dto.AuditEventRequestDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSaveDto;
import ar.edu.uade.pfi.backend.dto.ReviewSnapshotDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PostgresReviewStoreService {
    private final ObjectMapper objectMapper;
    private final String jdbcUrl;
    private final boolean enabled;

    public PostgresReviewStoreService(
        ObjectMapper objectMapper,
        @Value("${pfi.persistence.mode:memory}") String persistenceMode,
        @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl
    ) {
        this.objectMapper = objectMapper;
        this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
        this.enabled = "postgres".equalsIgnoreCase(persistenceMode) && !this.jdbcUrl.isBlank();
        if (enabled) {
            migrate();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public ReviewSnapshotDto snapshot() {
        if (!enabled) return null;
        return new ReviewSnapshotDto(findReviews(), findAllMeasurements(), findAuditTrail());
    }

    public ReviewStatusDto saveReview(ReviewStatusDto review) {
        if (!enabled) return review;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO review_statuses(run_id, status, notes, reviewer, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (run_id) DO UPDATE SET
              status = EXCLUDED.status,
              notes = EXCLUDED.notes,
              reviewer = EXCLUDED.reviewer,
              updated_at = EXCLUDED.updated_at
            """)) {
            statement.setString(1, review.runId());
            statement.setString(2, review.status());
            statement.setString(3, review.notes());
            statement.setString(4, review.reviewer());
            statement.setTimestamp(5, Timestamp.from(review.updatedAt()));
            statement.executeUpdate();
        } catch (Exception ignored) {
            // In-memory fallback remains the source of truth if Postgres is unavailable.
        }
        return review;
    }

    public List<MeasurementSaveDto> findMeasurements(String runId) {
        if (!enabled) return List.of();
        List<MeasurementSaveDto> measurements = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT measurement_id, payload FROM measurement_reviews WHERE run_id = ? ORDER BY updated_at DESC
            """)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    measurements.add(readMeasurement(resultSet.getString("payload")));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return measurements;
    }

    public List<MeasurementSaveDto> saveMeasurements(String runId, List<MeasurementSaveDto> measurements) {
        if (!enabled) return measurements;
        try (Connection connection = connection()) {
            for (MeasurementSaveDto measurement : measurements) {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO measurement_reviews(run_id, measurement_id, label, payload, updated_at)
                    VALUES (?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (run_id, measurement_id) DO UPDATE SET
                      label = EXCLUDED.label,
                      payload = EXCLUDED.payload,
                      updated_at = EXCLUDED.updated_at
                    """)) {
                    statement.setString(1, runId);
                    statement.setString(2, measurement.id());
                    statement.setString(3, measurement.label());
                    statement.setString(4, objectMapper.writeValueAsString(measurement));
                    statement.setTimestamp(5, Timestamp.from(Instant.now()));
                    statement.executeUpdate();
                }
            }
        } catch (Exception ignored) {
            // In-memory fallback remains active.
        }
        return measurements;
    }

    public AuditEventDto appendAudit(AuditEventDto event) {
        if (!enabled) return event;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO audit_events(id, reviewer, action, detail, created_at)
            VALUES (?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, event.id());
            statement.setString(2, event.reviewer());
            statement.setString(3, event.action());
            statement.setString(4, event.detail());
            statement.setTimestamp(5, Timestamp.from(event.timestamp()));
            statement.executeUpdate();
        } catch (Exception ignored) {
            // In-memory fallback remains active.
        }
        return event;
    }

    public List<AuditEventDto> findAuditTrail() {
        if (!enabled) return List.of();
        List<AuditEventDto> events = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, reviewer, action, detail, created_at FROM audit_events ORDER BY created_at DESC LIMIT 100
            """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    events.add(new AuditEventDto(
                        resultSet.getString("id"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getString("reviewer"),
                        resultSet.getString("action"),
                        resultSet.getString("detail")
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return events;
    }

    private List<ReviewStatusDto> findReviews() {
        List<ReviewStatusDto> reviews = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT run_id, status, notes, reviewer, updated_at FROM review_statuses ORDER BY updated_at DESC
            """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    reviews.add(new ReviewStatusDto(
                        resultSet.getString("run_id"),
                        resultSet.getString("status"),
                        resultSet.getString("notes"),
                        resultSet.getString("reviewer"),
                        resultSet.getTimestamp("updated_at").toInstant()
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return reviews;
    }

    private Map<String, List<MeasurementSaveDto>> findAllMeasurements() {
        Map<String, List<MeasurementSaveDto>> grouped = new HashMap<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT run_id, payload FROM measurement_reviews ORDER BY updated_at DESC
            """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    grouped.computeIfAbsent(resultSet.getString("run_id"), ignored -> new ArrayList<>())
                        .add(readMeasurement(resultSet.getString("payload")));
                }
            }
        } catch (Exception ignored) {
            return Map.of();
        }
        return grouped;
    }

    private MeasurementSaveDto readMeasurement(String payload) throws Exception {
        return objectMapper.readValue(payload, MeasurementSaveDto.class);
    }

    private void migrate() {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS review_statuses (
                    run_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL,
                    notes TEXT NOT NULL DEFAULT '',
                    reviewer TEXT NOT NULL DEFAULT '',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS measurement_reviews (
                    run_id TEXT NOT NULL,
                    measurement_id TEXT NOT NULL,
                    label TEXT NOT NULL,
                    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (run_id, measurement_id)
                )
                """);
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS audit_events (
                    id TEXT PRIMARY KEY,
                    reviewer TEXT NOT NULL DEFAULT 'System',
                    action TEXT NOT NULL,
                    detail TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        } catch (Exception ignored) {
            // Service stays available with in-memory fallback.
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String toJdbcUrl(String databaseUrl) {
        if (databaseUrl.isBlank()) return "";
        if (databaseUrl.startsWith("jdbc:postgresql://")) return databaseUrl;
        if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://")) return databaseUrl;
        try {
            URI uri = URI.create(databaseUrl);
            String userInfo = uri.getUserInfo();
            String user = "";
            String password = "";
            if (userInfo != null) {
                String[] parts = userInfo.split(":", 2);
                user = parts.length > 0 ? parts[0] : "";
                password = parts.length > 1 ? parts[1] : "";
            }
            String query = "sslmode=require";
            if (!user.isBlank()) query += "&user=" + URLEncoder.encode(user, StandardCharsets.UTF_8);
            if (!password.isBlank()) query += "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query;
        } catch (Exception ex) {
            return databaseUrl;
        }
    }
}
