package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.StudyRowDto;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PostgresStudyCatalogService {
    private final String jdbcUrl;
    private final boolean enabled;

    public PostgresStudyCatalogService(
        @Value("${pfi.persistence.mode:memory}") String persistenceMode,
        @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl
    ) {
        this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
        this.enabled = "postgres".equalsIgnoreCase(persistenceMode) && !this.jdbcUrl.isBlank();
        if (enabled) {
            migrate();
            seedDemoCatalog();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public List<StudyRowDto> listStudies() {
        if (!enabled) return List.of();
        List<StudyRowDto> rows = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT s.case_id, s.subject_ref, s.plane, s.study_date, r.model_key, r.model_status, s.review_status, s.priority, r.run_id
            FROM studies s
            JOIN study_runs r ON r.case_id = s.case_id AND r.primary_run = TRUE
            ORDER BY s.study_date DESC
            """)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StudyRowDto(
                        rs.getString("case_id"),
                        rs.getString("subject_ref"),
                        rs.getString("plane"),
                        rs.getString("study_date"),
                        rs.getString("model_key"),
                        rs.getString("model_status"),
                        rs.getString("review_status"),
                        rs.getString("priority"),
                        rs.getString("run_id")
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public List<Map<String, Object>> runsFor(String caseId) {
        if (!enabled) return List.of();
        List<Map<String, Object>> runs = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT run_id, case_id, plane, model_key, model_status, created_at
            FROM study_runs
            WHERE case_id = ?
            ORDER BY created_at DESC
            """)) {
            statement.setString(1, caseId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    runs.add(Map.of(
                        "runId", rs.getString("run_id"),
                        "caseId", rs.getString("case_id"),
                        "plane", rs.getString("plane"),
                        "modelKey", rs.getString("model_key"),
                        "modelStatus", rs.getString("model_status"),
                        "createdAt", rs.getTimestamp("created_at").toInstant().toString()
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return runs;
    }

    private void migrate() {
        try (Connection connection = connection()) {
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS studies (
                    case_id TEXT PRIMARY KEY,
                    subject_ref TEXT NOT NULL,
                    plane TEXT NOT NULL,
                    study_date TEXT NOT NULL,
                    review_status TEXT NOT NULL DEFAULT 'pendiente',
                    priority TEXT NOT NULL DEFAULT 'media',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
            connection.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS study_runs (
                    run_id TEXT PRIMARY KEY,
                    case_id TEXT NOT NULL REFERENCES studies(case_id) ON DELETE CASCADE,
                    plane TEXT NOT NULL,
                    model_key TEXT NOT NULL,
                    model_status TEXT NOT NULL,
                    primary_run BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        } catch (Exception ignored) {
            // Catalog remains available through demo fallback if Postgres is unavailable.
        }
    }

    private void seedDemoCatalog() {
        upsertStudy("CASE-DEMO-0142", "PAT-0087", "sagittal", "2026-07-01", "pendiente", "media", "89f224fa2fcce967", "sagittal_spider", "AI-ready");
        upsertStudy("CASE-0110", "PAT-0087", "axial", "2026-05-19", "observado", "alta", "run-case-0110", "axial_t2_alkafri", "AI-ready");
        upsertStudy("CASE-0089", "PAT-0214", "sagittal", "2026-04-04", "pendiente", "media", "run-case-0089", "sagittal_spider", "Inference pending");
        upsertStudy("CASE-0061", "PAT-0332", "sagittal", "2026-02-12", "aceptado", "baja", "run-case-0061", "sagittal_spider", "AI-ready");
    }

    private void upsertStudy(String caseId, String subjectRef, String plane, String studyDate, String reviewStatus, String priority, String runId, String modelKey, String modelStatus) {
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO studies(case_id, subject_ref, plane, study_date, review_status, priority)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (case_id) DO UPDATE SET
                  subject_ref = EXCLUDED.subject_ref,
                  plane = EXCLUDED.plane,
                  study_date = EXCLUDED.study_date,
                  priority = EXCLUDED.priority,
                  updated_at = now()
                """)) {
                statement.setString(1, caseId);
                statement.setString(2, subjectRef);
                statement.setString(3, plane);
                statement.setString(4, studyDate);
                statement.setString(5, reviewStatus);
                statement.setString(6, priority);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO study_runs(run_id, case_id, plane, model_key, model_status, primary_run)
                VALUES (?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (run_id) DO UPDATE SET
                  case_id = EXCLUDED.case_id,
                  plane = EXCLUDED.plane,
                  model_key = EXCLUDED.model_key,
                  model_status = EXCLUDED.model_status,
                  primary_run = TRUE
                """)) {
                statement.setString(1, runId);
                statement.setString(2, caseId);
                statement.setString(3, plane);
                statement.setString(4, modelKey);
                statement.setString(5, modelStatus);
                statement.executeUpdate();
            }
        } catch (Exception ignored) {
            // Demo fallback remains available.
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
