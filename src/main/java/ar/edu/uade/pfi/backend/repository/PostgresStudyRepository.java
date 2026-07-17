package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "pfi.persistence.mode", havingValue = "postgres")
public class PostgresStudyRepository implements StudyRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    @Autowired
    public PostgresStudyRepository(
        ObjectMapper objectMapper,
        @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl
    ) {
        this(objectMapper, databaseUrl, true);
    }

    public PostgresStudyRepository(ObjectMapper objectMapper, String databaseUrl, boolean applyMigrations) {
        this.objectMapper = objectMapper;
        this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
        if (applyMigrations) {
            try (Connection connection = connection()) {
                new SqlMigrationRunner(Path.of("docs", "migrations")).apply(connection);
            } catch (Exception ex) {
                throw new IllegalStateException("Could not initialize PostgreSQL study repository", ex);
            }
        }
    }

    @Override
    public Study saveStudy(Study study) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_studies(id, case_id, status, created_at, updated_at)
            VALUES (?::uuid, ?, ?, ?, ?)
            ON CONFLICT (case_id) DO UPDATE SET
              status = EXCLUDED.status,
              updated_at = EXCLUDED.updated_at
            """)) {
            statement.setString(1, study.id());
            statement.setString(2, study.caseId());
            statement.setString(3, study.status());
            statement.setTimestamp(4, Timestamp.from(study.createdAt()));
            statement.setTimestamp(5, Timestamp.from(study.updatedAt()));
            statement.executeUpdate();
            return study;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save study", ex);
        }
    }

    @Override
    public InputResource saveInput(InputResource input) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_input_resources(id, study_id, plane, input_id, format, size_bytes, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?)
            ON CONFLICT (input_id) DO UPDATE SET
              format = EXCLUDED.format,
              size_bytes = EXCLUDED.size_bytes
            """)) {
            statement.setString(1, input.id());
            statement.setString(2, input.studyId());
            statement.setString(3, input.plane());
            statement.setString(4, input.inputId());
            statement.setString(5, input.format());
            statement.setLong(6, input.size());
            statement.setTimestamp(7, Timestamp.from(input.createdAt()));
            statement.executeUpdate();
            return input;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save input resource", ex);
        }
    }

    @Override
    public StudyRun saveRun(StudyRun run) {
        try (Connection connection = connection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO domain_study_runs(
                    id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                    sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                    sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                    review_status, reviewer, reviewed_at, comments, created_at, updated_at
                )
                VALUES (
                    ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?,
                    ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT (multiplanar_run_id) DO UPDATE SET
                  trace_id = EXCLUDED.trace_id,
                  effective_inference_mode = EXCLUDED.effective_inference_mode,
                  assets = EXCLUDED.assets,
                  metrics_snapshot = EXCLUDED.metrics_snapshot,
                  status = EXCLUDED.status,
                  review_status = EXCLUDED.review_status,
                  reviewer = EXCLUDED.reviewer,
                  reviewed_at = EXCLUDED.reviewed_at,
                  comments = EXCLUDED.comments,
                  updated_at = EXCLUDED.updated_at
                """)) {
                statement.setString(1, run.id());
                statement.setString(2, run.studyId());
                statement.setString(3, run.multiplanarRunId());
                statement.setString(4, run.traceId());
                statement.setString(5, run.requestedInferenceMode());
                statement.setString(6, run.effectiveInferenceMode());
                statement.setString(7, run.sagittalModelKey());
                statement.setString(8, run.axialModelKey());
                statement.setString(9, run.sagittalArtifactHash());
                statement.setString(10, run.axialArtifactHash());
                statement.setString(11, run.sagittalRunId());
                statement.setString(12, run.axialRunId());
                statement.setString(13, objectMapper.writeValueAsString(run.assets()));
                statement.setString(14, objectMapper.writeValueAsString(run.metricsSnapshot()));
                statement.setString(15, run.status());
                statement.setString(16, run.reviewStatus());
                statement.setString(17, run.reviewer());
                statement.setTimestamp(18, run.reviewedAt() == null ? null : Timestamp.from(run.reviewedAt()));
                statement.setString(19, run.comments());
                statement.setTimestamp(20, Timestamp.from(run.createdAt()));
                statement.setTimestamp(21, Timestamp.from(run.updatedAt()));
                statement.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM domain_run_artifacts WHERE study_run_id = ?::uuid")) {
                delete.setString(1, run.id());
                delete.executeUpdate();
            }
            for (RunArtifact artifact : run.artifacts()) saveArtifact(connection, artifact);
            return run;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save study run", ex);
        }
    }

    @Override
    public Optional<Study> findStudyByCaseId(String caseId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, case_id, status, created_at, updated_at FROM domain_studies WHERE case_id = ?
            """)) {
            statement.setString(1, caseId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readStudy(rs));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find study", ex);
        }
    }

    @Override
    public List<InputResource> findInputsByStudyId(String studyId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, plane, input_id, format, size_bytes, created_at
            FROM domain_input_resources WHERE study_id = ?::uuid ORDER BY created_at
            """)) {
            statement.setString(1, studyId);
            try (ResultSet rs = statement.executeQuery()) {
                List<InputResource> inputs = new ArrayList<>();
                while (rs.next()) inputs.add(readInput(rs));
                return inputs;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find inputs", ex);
        }
    }

    @Override
    public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) {
        return findRun("multiplanar_run_id", multiplanarRunId);
    }

    @Override
    public Optional<StudyRun> findRunByTraceId(String traceId) {
        return findRun("trace_id", traceId);
    }

    @Override
    public List<RunArtifact> findArtifactsByRunId(String studyRunId) {
        try (Connection connection = connection()) {
            return findArtifacts(connection, studyRunId);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find artifacts", ex);
        }
    }

    @Override
    public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            StudyRun run;
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE domain_study_runs
                    SET review_status = ?, reviewer = ?, reviewed_at = ?, comments = ?, updated_at = ?
                    WHERE multiplanar_run_id = ?
                    """)) {
                    statement.setString(1, reviewStatus);
                    statement.setString(2, reviewer);
                    statement.setTimestamp(3, Timestamp.from(reviewedAt));
                    statement.setString(4, comments);
                    statement.setTimestamp(5, Timestamp.from(reviewedAt));
                    statement.setString(6, multiplanarRunId);
                    if (statement.executeUpdate() == 0) {
                        throw new IllegalArgumentException("run_not_found");
                    }
                }
                run = findRun(connection, "multiplanar_run_id", multiplanarRunId).orElseThrow();
                replaceCorrections(connection, run.id(), corrections);
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
            return new RunReview(multiplanarRunId, run.traceId(), reviewStatus, reviewer, reviewedAt, comments, findCorrections(connection, run.id()));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save run review", ex);
        }
    }

    @Override
    public Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId) {
        try (Connection connection = connection()) {
            Optional<StudyRun> run = findRun(connection, "multiplanar_run_id", multiplanarRunId);
            if (run.isEmpty()) return Optional.empty();
            StudyRun value = run.get();
            return Optional.of(new RunReview(
                value.multiplanarRunId(),
                value.traceId(),
                value.reviewStatus(),
                value.reviewer(),
                value.reviewedAt(),
                value.comments(),
                findCorrections(connection, value.id())
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find run review", ex);
        }
    }

    @Override
    public DomainAuditEvent saveAuditEvent(DomainAuditEvent event) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_audit_events(id, actor, action, entity_id, trace_id, metadata, created_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?::jsonb, ?)
            """)) {
            statement.setString(1, event.id());
            statement.setString(2, event.actor());
            statement.setString(3, event.action());
            statement.setString(4, event.entityId());
            statement.setString(5, event.traceId());
            statement.setString(6, objectMapper.writeValueAsString(event.metadata()));
            statement.setTimestamp(7, Timestamp.from(event.timestamp()));
            statement.executeUpdate();
            return event;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not save audit event", ex);
        }
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByTraceId(String traceId) {
        return findAuditEvents("trace_id", traceId);
    }

    @Override
    public List<DomainAuditEvent> findAuditEventsByEntityId(String entityId) {
        return findAuditEvents("entity_id", entityId);
    }

    private Optional<StudyRun> findRun(String column, String value) {
        try (Connection connection = connection()) {
            return findRun(connection, column, value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find study run", ex);
        }
    }

    private Optional<StudyRun> findRun(Connection connection, String column, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                   sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                   sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                   review_status, reviewer, reviewed_at, comments, created_at, updated_at
            FROM domain_study_runs WHERE %s = ?
            """.formatted(column))) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readRun(connection, rs));
            }
        }
    }

    private void replaceCorrections(Connection connection, String studyRunId, List<MeasurementCorrection> corrections) throws Exception {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM domain_review_corrections WHERE study_run_id = ?::uuid")) {
            delete.setString(1, studyRunId);
            delete.executeUpdate();
        }
        for (MeasurementCorrection correction : corrections) {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO domain_review_corrections(id, study_run_id, measurement_id, label, before_value, after_value, comment, created_at)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """)) {
                statement.setString(1, correction.id());
                statement.setString(2, studyRunId);
                statement.setString(3, correction.measurementId());
                statement.setString(4, correction.label());
                statement.setString(5, objectMapper.writeValueAsString(correction.beforeValue()));
                statement.setString(6, objectMapper.writeValueAsString(correction.afterValue()));
                statement.setString(7, correction.comment());
                statement.setTimestamp(8, Timestamp.from(correction.createdAt()));
                statement.executeUpdate();
            }
        }
    }

    private List<MeasurementCorrection> findCorrections(Connection connection, String studyRunId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_run_id, measurement_id, label, before_value, after_value, comment, created_at
            FROM domain_review_corrections
            WHERE study_run_id = ?::uuid
            ORDER BY created_at, measurement_id
            """)) {
            statement.setString(1, studyRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<MeasurementCorrection> corrections = new ArrayList<>();
                while (rs.next()) {
                    corrections.add(new MeasurementCorrection(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("study_run_id", UUID.class).toString(),
                        rs.getString("measurement_id"),
                        rs.getString("label"),
                        readJsonMap(rs.getString("before_value")),
                        readJsonMap(rs.getString("after_value")),
                        rs.getString("comment"),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return corrections;
            }
        }
    }

    private void saveArtifact(Connection connection, RunArtifact artifact) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO domain_run_artifacts(id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, artifact.id());
            statement.setString(2, artifact.studyRunId());
            statement.setString(3, artifact.runId());
            statement.setString(4, artifact.plane());
            statement.setString(5, artifact.assetName());
            statement.setString(6, artifact.contentType());
            statement.setString(7, artifact.artifactRef());
            statement.setTimestamp(8, Timestamp.from(artifact.createdAt()));
            statement.executeUpdate();
        }
    }

    private List<DomainAuditEvent> findAuditEvents(String column, String value) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
            SELECT id, actor, action, entity_id, trace_id, metadata, created_at
            FROM domain_audit_events
            WHERE %s = ?
            ORDER BY created_at, action
            """.formatted(column))) {
            statement.setString(1, value);
            try (ResultSet rs = statement.executeQuery()) {
                List<DomainAuditEvent> events = new ArrayList<>();
                while (rs.next()) {
                    events.add(new DomainAuditEvent(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_id"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("created_at").toInstant(),
                        readJsonMap(rs.getString("metadata"))
                    ));
                }
                return events;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Could not find audit events", ex);
        }
    }

    private Study readStudy(ResultSet rs) throws Exception {
        return new Study(
            rs.getObject("id", UUID.class).toString(),
            rs.getString("case_id"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private InputResource readInput(ResultSet rs) throws Exception {
        return new InputResource(
            rs.getObject("id", UUID.class).toString(),
            rs.getObject("study_id", UUID.class).toString(),
            rs.getString("plane"),
            rs.getString("input_id"),
            rs.getString("format"),
            rs.getLong("size_bytes"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private StudyRun readRun(Connection connection, ResultSet rs) throws Exception {
        String id = rs.getObject("id", UUID.class).toString();
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new StudyRun(
            id,
            rs.getObject("study_id", UUID.class).toString(),
            rs.getString("multiplanar_run_id"),
            rs.getString("trace_id"),
            rs.getString("requested_inference_mode"),
            rs.getString("effective_inference_mode"),
            rs.getString("sagittal_model_key"),
            rs.getString("axial_model_key"),
            rs.getString("sagittal_artifact_hash"),
            rs.getString("axial_artifact_hash"),
            rs.getString("sagittal_run_id"),
            rs.getString("axial_run_id"),
            readJsonMap(rs.getString("assets")),
            readJsonMap(rs.getString("metrics_snapshot")),
            findArtifacts(connection, id),
            rs.getString("status"),
            rs.getString("review_status"),
            rs.getString("reviewer"),
            reviewedAt == null ? null : reviewedAt.toInstant(),
            rs.getString("comments"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );
    }

    private List<RunArtifact> findArtifacts(Connection connection, String studyRunId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at
            FROM domain_run_artifacts WHERE study_run_id = ?::uuid ORDER BY plane, asset_name
            """)) {
            statement.setString(1, studyRunId);
            try (ResultSet rs = statement.executeQuery()) {
                List<RunArtifact> artifacts = new ArrayList<>();
                while (rs.next()) {
                    artifacts.add(new RunArtifact(
                        rs.getObject("id", UUID.class).toString(),
                        rs.getObject("study_run_id", UUID.class).toString(),
                        rs.getString("run_id"),
                        rs.getString("plane"),
                        rs.getString("asset_name"),
                        rs.getString("content_type"),
                        rs.getString("artifact_ref"),
                        rs.getTimestamp("created_at").toInstant()
                    ));
                }
                return artifacts;
            }
        }
    }

    private Map<String, Object> readJsonMap(String json) throws Exception {
        if (json == null || json.isBlank()) return Map.of();
        return objectMapper.readValue(json, JSON_MAP);
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
