package ar.edu.uade.pfi.backend.repository;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.ReviewerAnnotation;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
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
  private static final TypeReference<List<Map<String, Object>>> JSON_MAP_LIST =
      new TypeReference<>() {};

  private final String jdbcUrl;
  private final ObjectMapper objectMapper;

  @Autowired
  public PostgresStudyRepository(
      ObjectMapper objectMapper,
      @Value("${pfi.database.url:${DATABASE_URL:}}") String databaseUrl) {
    this(objectMapper, databaseUrl, true);
  }

  public PostgresStudyRepository(
      ObjectMapper objectMapper, String databaseUrl, boolean applyMigrations) {
    this.objectMapper = objectMapper;
    this.jdbcUrl = toJdbcUrl(databaseUrl == null ? "" : databaseUrl.trim());
    if (applyMigrations) {
      try (Connection connection = connection()) {
        new SqlMigrationRunner(SqlMigrationRunner.DEFAULT_MIGRATIONS_DIRECTORY).apply(connection);
      } catch (Exception ex) {
        throw new IllegalStateException("Could not initialize PostgreSQL study repository", ex);
      }
    }
  }

  @Override
  public Study saveStudy(Study study) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO domain_studies(
                id, case_id, status, patient_id, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            )
            VALUES (?::uuid, ?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (case_id) DO UPDATE SET
              status = EXCLUDED.status,
              patient_id = COALESCE(EXCLUDED.patient_id, domain_studies.patient_id),
              subject_ref = EXCLUDED.subject_ref,
              study_date = EXCLUDED.study_date,
              modality = EXCLUDED.modality,
              description = EXCLUDED.description,
              review_priority = EXCLUDED.review_priority,
              updated_at = EXCLUDED.updated_at
            """)) {
      statement.setString(1, study.id());
      statement.setString(2, study.caseId());
      statement.setString(3, study.status());
      statement.setString(4, study.patientId());
      statement.setString(5, study.subjectRef());
      statement.setDate(6, study.studyDate() == null ? null : Date.valueOf(study.studyDate()));
      statement.setString(7, study.modality());
      statement.setString(8, study.description());
      statement.setString(9, study.reviewPriority());
      statement.setTimestamp(10, Timestamp.from(study.createdAt()));
      statement.setTimestamp(11, Timestamp.from(study.updatedAt()));
      statement.executeUpdate();
      return study;
    } catch (Exception ex) {
      throw new IllegalStateException("Could not save study", ex);
    }
  }

  @Override
  public InputResource saveInput(InputResource input) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            INSERT INTO domain_input_resources(
                id, study_id, plane, input_id, format, size_bytes,
                description, weighting, slice_count, multiplanar, derived, analyzable, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (input_id) DO UPDATE SET
              format = EXCLUDED.format,
              size_bytes = EXCLUDED.size_bytes,
              description = EXCLUDED.description,
              weighting = EXCLUDED.weighting,
              slice_count = EXCLUDED.slice_count,
              multiplanar = EXCLUDED.multiplanar,
              derived = EXCLUDED.derived,
              analyzable = EXCLUDED.analyzable
            """)) {
      statement.setString(1, input.id());
      statement.setString(2, input.studyId());
      statement.setString(3, input.plane());
      statement.setString(4, input.inputId());
      statement.setString(5, input.format());
      statement.setLong(6, input.size());
      statement.setString(7, input.description());
      statement.setString(8, input.weighting());
      statement.setInt(9, input.sliceCount());
      statement.setBoolean(10, input.multiplanar());
      statement.setBoolean(11, input.derived());
      statement.setBoolean(12, input.analyzable());
      statement.setTimestamp(13, Timestamp.from(input.createdAt()));
      statement.executeUpdate();
      return input;
    } catch (Exception ex) {
      throw new IllegalStateException("Could not save input resource", ex);
    }
  }

  @Override
  public StudyRun saveRun(StudyRun run) {
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      try {
        String persistedStudyRunId = upsertRun(connection, run);
        List<RunArtifact> persistedArtifacts =
            normalizeArtifacts(run.artifacts(), persistedStudyRunId);
        try (PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM domain_run_artifacts WHERE study_run_id = ?::uuid")) {
          delete.setString(1, persistedStudyRunId);
          delete.executeUpdate();
        }
        for (RunArtifact artifact : persistedArtifacts) saveArtifact(connection, artifact);
        connection.commit();
        return withPersistedId(run, persistedStudyRunId, persistedArtifacts);
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not save study run", ex);
    }
  }

  private String upsertRun(Connection connection, StudyRun run) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
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
              requested_inference_mode = EXCLUDED.requested_inference_mode,
              effective_inference_mode = EXCLUDED.effective_inference_mode,
              sagittal_model_key = EXCLUDED.sagittal_model_key,
              axial_model_key = EXCLUDED.axial_model_key,
              sagittal_artifact_hash = EXCLUDED.sagittal_artifact_hash,
              axial_artifact_hash = EXCLUDED.axial_artifact_hash,
              sagittal_run_id = EXCLUDED.sagittal_run_id,
              axial_run_id = EXCLUDED.axial_run_id,
              assets = EXCLUDED.assets,
              metrics_snapshot = EXCLUDED.metrics_snapshot,
              status = EXCLUDED.status,
              review_status = EXCLUDED.review_status,
              reviewer = EXCLUDED.reviewer,
              reviewed_at = EXCLUDED.reviewed_at,
              comments = EXCLUDED.comments,
              updated_at = EXCLUDED.updated_at
            RETURNING id
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
      statement.setTimestamp(
          18, run.reviewedAt() == null ? null : Timestamp.from(run.reviewedAt()));
      statement.setString(19, run.comments());
      statement.setTimestamp(20, Timestamp.from(run.createdAt()));
      statement.setTimestamp(21, Timestamp.from(run.updatedAt()));
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) throw new IllegalStateException("Could not obtain persisted study run id");
        return rs.getObject("id", UUID.class).toString();
      }
    }
  }

  private List<RunArtifact> normalizeArtifacts(
      List<RunArtifact> artifacts, String persistedStudyRunId) {
    if (artifacts == null || artifacts.isEmpty()) return List.of();
    List<RunArtifact> normalized = new ArrayList<>();
    for (RunArtifact artifact : artifacts) {
      normalized.add(
          new RunArtifact(
              artifact.id(),
              persistedStudyRunId,
              artifact.runId(),
              artifact.plane(),
              artifact.assetName(),
              artifact.contentType(),
              artifact.artifactRef(),
              artifact.createdAt(),
              artifact.storageStatus(),
              artifact.storageKind(),
              artifact.sizeBytes(),
              artifact.sha256()));
    }
    return normalized;
  }

  private StudyRun withPersistedId(
      StudyRun run, String persistedStudyRunId, List<RunArtifact> artifacts) {
    return new StudyRun(
        persistedStudyRunId,
        run.studyId(),
        run.multiplanarRunId(),
        run.traceId(),
        run.requestedInferenceMode(),
        run.effectiveInferenceMode(),
        run.sagittalModelKey(),
        run.axialModelKey(),
        run.sagittalArtifactHash(),
        run.axialArtifactHash(),
        run.sagittalRunId(),
        run.axialRunId(),
        run.assets(),
        run.metricsSnapshot(),
        artifacts,
        run.status(),
        run.reviewStatus(),
        run.reviewer(),
        run.reviewedAt(),
        run.comments(),
        run.createdAt(),
        run.updatedAt());
  }

  @Override
  public List<Study> findAllStudies() {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, case_id, status, patient_id, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            FROM domain_studies
            ORDER BY updated_at DESC, created_at DESC
            """)) {
      try (ResultSet rs = statement.executeQuery()) {
        List<Study> studies = new ArrayList<>();
        while (rs.next()) studies.add(readStudy(rs));
        return studies;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not list studies", ex);
    }
  }

  @Override
  public Optional<Study> findStudyByCaseId(String caseId) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, case_id, status, patient_id, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            FROM domain_studies
            WHERE case_id = ?
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
  public List<Study> findStudiesBySubjectRef(String subjectRef) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, case_id, status, patient_id, subject_ref, study_date, modality, description, review_priority, created_at, updated_at
            FROM domain_studies
            WHERE subject_ref IS NOT NULL AND lower(subject_ref) = lower(?)
            ORDER BY study_date DESC NULLS LAST, created_at DESC
            """)) {
      statement.setString(1, subjectRef);
      try (ResultSet rs = statement.executeQuery()) {
        List<Study> studies = new ArrayList<>();
        while (rs.next()) studies.add(readStudy(rs));
        return studies;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find studies by subject ref", ex);
    }
  }

  @Override
  public List<StudyRun> findRunsByStudyId(String studyId) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                   sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                   sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                   review_status, reviewer, reviewed_at, comments, created_at, updated_at
            FROM domain_study_runs
            WHERE study_id = ?::uuid
            ORDER BY created_at DESC, updated_at DESC
            """)) {
      statement.setString(1, studyId);
      try (ResultSet rs = statement.executeQuery()) {
        List<StudyRun> runs = new ArrayList<>();
        while (rs.next()) runs.add(readRun(connection, rs));
        return runs;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not list study runs", ex);
    }
  }

  @Override
  public Optional<StudyRun> findLatestRunByStudyId(String studyId) {
    List<StudyRun> runs = findRunsByStudyId(studyId);
    if (runs.isEmpty()) return Optional.empty();
    return Optional.of(runs.get(0));
  }

  @Override
  public List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId) {
    try (Connection connection = connection()) {
      return findCorrections(connection, studyRunId);
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find measurement corrections", ex);
    }
  }

  @Override
  public List<ReviewerAnnotation> replaceAnnotations(
      String multiplanarRunId, List<ReviewerAnnotation> annotations) {
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      try {
        String studyRunId = studyRunIdFor(connection, multiplanarRunId);
        try (PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM domain_reviewer_annotations WHERE study_run_id = ?::uuid")) {
          delete.setString(1, studyRunId);
          delete.executeUpdate();
        }
        for (ReviewerAnnotation annotation : annotations) {
          insertAnnotation(connection, studyRunId, annotation);
        }
        connection.commit();
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
      return List.copyOf(annotations);
    } catch (Exception ex) {
      throw new IllegalStateException("Could not replace reviewer annotations", ex);
    }
  }

  @Override
  public List<ReviewerAnnotation> findAnnotationsByRunId(String multiplanarRunId) {
    try (Connection connection = connection()) {
      String studyRunId = studyRunIdFor(connection, multiplanarRunId);
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
                SELECT id, study_run_id, scope, kind, measurement_kind, plane, series_id, slice_index, level,
                       points, value, unit, text, author, created_at
                FROM domain_reviewer_annotations
                WHERE study_run_id = ?::uuid
                ORDER BY created_at, id
                """)) {
        statement.setString(1, studyRunId);
        try (ResultSet rs = statement.executeQuery()) {
          List<ReviewerAnnotation> annotations = new ArrayList<>();
          while (rs.next()) {
            annotations.add(readAnnotation(rs));
          }
          return annotations;
        }
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find reviewer annotations", ex);
    }
  }

  private String studyRunIdFor(Connection connection, String multiplanarRunId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT id FROM domain_study_runs WHERE multiplanar_run_id = ?")) {
      statement.setString(1, multiplanarRunId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next())
          throw new IllegalArgumentException("Corrida no encontrada: " + multiplanarRunId);
        return rs.getObject("id", UUID.class).toString();
      }
    }
  }

  private void insertAnnotation(
      Connection connection, String studyRunId, ReviewerAnnotation annotation) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO domain_reviewer_annotations(
                id, study_run_id, scope, kind, measurement_kind, plane, series_id, slice_index, level,
                points, value, unit, text, author, created_at)
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, annotation.id());
      statement.setString(2, studyRunId);
      statement.setString(3, annotation.scope());
      statement.setString(4, annotation.kind());
      statement.setString(5, annotation.measurementKind());
      statement.setString(6, annotation.plane());
      statement.setString(7, annotation.seriesId());
      if (annotation.sliceIndex() == null) statement.setNull(8, java.sql.Types.INTEGER);
      else statement.setInt(8, annotation.sliceIndex());
      statement.setString(9, annotation.level());
      statement.setString(10, objectMapper.writeValueAsString(annotation.points()));
      if (annotation.value() == null) statement.setNull(11, java.sql.Types.DOUBLE);
      else statement.setDouble(11, annotation.value());
      statement.setString(12, annotation.unit());
      statement.setString(13, annotation.text());
      statement.setString(14, annotation.author());
      statement.setTimestamp(15, Timestamp.from(annotation.createdAt()));
      statement.executeUpdate();
    }
  }

  private ReviewerAnnotation readAnnotation(ResultSet rs) throws Exception {
    int sliceIndex = rs.getInt("slice_index");
    boolean sliceIndexNull = rs.wasNull();
    double value = rs.getDouble("value");
    boolean valueNull = rs.wasNull();
    return new ReviewerAnnotation(
        rs.getObject("id", UUID.class).toString(),
        rs.getObject("study_run_id", UUID.class).toString(),
        rs.getString("scope"),
        rs.getString("kind"),
        rs.getString("measurement_kind"),
        rs.getString("plane"),
        rs.getString("series_id"),
        sliceIndexNull ? null : sliceIndex,
        rs.getString("level"),
        readJsonMapList(rs.getString("points")),
        valueNull ? null : value,
        rs.getString("unit"),
        rs.getString("text"),
        rs.getString("author"),
        rs.getTimestamp("created_at").toInstant());
  }

  private List<Map<String, Object>> readJsonMapList(String json) throws Exception {
    if (json == null || json.isBlank()) return List.of();
    return objectMapper.readValue(json, JSON_MAP_LIST);
  }

  @Override
  public List<DomainAuditEvent> findAuditEventsByStudyId(String studyId) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT DISTINCT event.id, event.actor, event.action, event.entity_id, event.trace_id, event.metadata, event.created_at
            FROM domain_audit_events event
            LEFT JOIN domain_study_runs run
              ON event.entity_id = run.multiplanar_run_id OR event.trace_id = run.trace_id
            WHERE run.study_id = ?::uuid OR event.entity_id = ?
            ORDER BY event.created_at, event.action
            """)) {
      statement.setString(1, studyId);
      statement.setString(2, studyId);
      try (ResultSet rs = statement.executeQuery()) {
        List<DomainAuditEvent> events = new ArrayList<>();
        while (rs.next()) events.add(readAuditEvent(rs));
        return events;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find study audit events", ex);
    }
  }

  @Override
  public List<InputResource> findInputsByStudyId(String studyId) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, study_id, plane, input_id, format, size_bytes,
                   description, weighting, slice_count, multiplanar, derived, analyzable, created_at
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
  public RunReview saveReview(
      String multiplanarRunId,
      String reviewStatus,
      String reviewer,
      Instant reviewedAt,
      String comments,
      List<MeasurementCorrection> corrections) {
    return saveReview(
        multiplanarRunId, reviewStatus, reviewer, reviewedAt, comments, corrections, null);
  }

  @Override
  public RunReview saveReview(
      String multiplanarRunId,
      String reviewStatus,
      String reviewer,
      Instant reviewedAt,
      String comments,
      List<MeasurementCorrection> corrections,
      DomainAuditEvent auditEvent) {
    try (Connection connection = connection()) {
      connection.setAutoCommit(false);
      StudyRun run;
      Instant updatedAt = reviewedAt == null ? Instant.now() : reviewedAt;
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                    UPDATE domain_study_runs
                    SET review_status = ?, reviewer = ?, reviewed_at = ?, comments = ?, updated_at = ?
                    WHERE multiplanar_run_id = ?
                    """)) {
          statement.setString(1, reviewStatus);
          statement.setString(2, reviewer);
          statement.setTimestamp(3, reviewedAt == null ? null : Timestamp.from(reviewedAt));
          statement.setString(4, comments);
          statement.setTimestamp(5, Timestamp.from(updatedAt));
          statement.setString(6, multiplanarRunId);
          if (statement.executeUpdate() == 0) {
            throw new IllegalArgumentException("run_not_found");
          }
        }
        run = findRun(connection, "multiplanar_run_id", multiplanarRunId).orElseThrow();
        replaceCorrections(connection, run.id(), corrections);
        if (auditEvent != null) saveAuditEvent(connection, auditEvent);
        connection.commit();
      } catch (Exception ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(true);
      }
      return new RunReview(
          multiplanarRunId,
          run.traceId(),
          reviewStatus,
          reviewer,
          reviewedAt,
          comments,
          findCorrections(connection, run.id()));
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
      return Optional.of(
          new RunReview(
              value.multiplanarRunId(),
              value.traceId(),
              value.reviewStatus(),
              value.reviewer(),
              value.reviewedAt(),
              value.comments(),
              findCorrections(connection, value.id())));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find run review", ex);
    }
  }

  @Override
  public DomainAuditEvent saveAuditEvent(DomainAuditEvent event) {
    try (Connection connection = connection()) {
      saveAuditEvent(connection, event);
      return event;
    } catch (Exception ex) {
      throw new IllegalStateException("Could not save audit event", ex);
    }
  }

  private void saveAuditEvent(Connection connection, DomainAuditEvent event) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
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

  private Optional<StudyRun> findRun(Connection connection, String column, String value)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                   sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                   sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                   review_status, reviewer, reviewed_at, comments, created_at, updated_at
            FROM domain_study_runs WHERE %s = ?
            """
                .formatted(column))) {
      statement.setString(1, value);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) return Optional.empty();
        return Optional.of(readRun(connection, rs));
      }
    }
  }

  private void replaceCorrections(
      Connection connection, String studyRunId, List<MeasurementCorrection> corrections)
      throws Exception {
    try (PreparedStatement delete =
        connection.prepareStatement(
            "DELETE FROM domain_review_corrections WHERE study_run_id = ?::uuid")) {
      delete.setString(1, studyRunId);
      delete.executeUpdate();
    }
    for (MeasurementCorrection correction : corrections) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              """
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

  private List<MeasurementCorrection> findCorrections(Connection connection, String studyRunId)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT id, study_run_id, measurement_id, label, before_value, after_value, comment, created_at
            FROM domain_review_corrections
            WHERE study_run_id = ?::uuid
            ORDER BY created_at, measurement_id
            """)) {
      statement.setString(1, studyRunId);
      try (ResultSet rs = statement.executeQuery()) {
        List<MeasurementCorrection> corrections = new ArrayList<>();
        while (rs.next()) {
          corrections.add(
              new MeasurementCorrection(
                  rs.getObject("id", UUID.class).toString(),
                  rs.getObject("study_run_id", UUID.class).toString(),
                  rs.getString("measurement_id"),
                  rs.getString("label"),
                  readJsonMap(rs.getString("before_value")),
                  readJsonMap(rs.getString("after_value")),
                  rs.getString("comment"),
                  rs.getTimestamp("created_at").toInstant()));
        }
        return corrections;
      }
    }
  }

  private void saveArtifact(Connection connection, RunArtifact artifact) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO domain_run_artifacts(
                id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                storage_status, storage_kind, size_bytes, sha256
            )
            VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, artifact.id());
      statement.setString(2, artifact.studyRunId());
      statement.setString(3, artifact.runId());
      statement.setString(4, artifact.plane());
      statement.setString(5, artifact.assetName());
      statement.setString(6, artifact.contentType());
      statement.setString(7, artifact.artifactRef());
      statement.setTimestamp(8, Timestamp.from(artifact.createdAt()));
      statement.setString(9, artifact.storageStatus());
      statement.setString(10, artifact.storageKind());
      if (artifact.sizeBytes() == null) statement.setObject(11, null);
      else statement.setLong(11, artifact.sizeBytes());
      statement.setString(12, artifact.sha256());
      statement.executeUpdate();
    }
  }

  @Override
  public Optional<RunArtifact> findArtifactByRunPlaneAndName(
      String runId, String plane, String assetName) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                   storage_status, storage_kind, size_bytes, sha256
            FROM domain_run_artifacts
            WHERE run_id = ? AND plane = ? AND asset_name = ?
            ORDER BY created_at DESC
            LIMIT 1
            """)) {
      statement.setString(1, runId);
      statement.setString(2, plane);
      statement.setString(3, assetName);
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? Optional.of(readArtifact(rs)) : Optional.empty();
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find run artifact", ex);
    }
  }

  @Override
  public RunArtifact updateArtifactStorage(
      String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            UPDATE domain_run_artifacts
            SET storage_status = ?, storage_kind = ?, size_bytes = ?, sha256 = ?
            WHERE id = ?::uuid
            RETURNING id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                      storage_status, storage_kind, size_bytes, sha256
            """)) {
      statement.setString(1, storageStatus);
      statement.setString(2, storageKind);
      if (sizeBytes == null) statement.setObject(3, null);
      else statement.setLong(3, sizeBytes);
      statement.setString(4, sha256);
      statement.setString(5, artifactId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("Artifact not found");
        return readArtifact(rs);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not update run artifact storage", ex);
    }
  }

  @Override
  public StudyRun updateRunMetricsSnapshot(
      String multiplanarRunId, Map<String, Object> metricsSnapshot) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            UPDATE domain_study_runs
            SET metrics_snapshot = ?::jsonb, updated_at = now()
            WHERE multiplanar_run_id = ?
            RETURNING id, study_id, multiplanar_run_id, trace_id, requested_inference_mode, effective_inference_mode,
                      sagittal_model_key, axial_model_key, sagittal_artifact_hash, axial_artifact_hash,
                      sagittal_run_id, axial_run_id, assets, metrics_snapshot, status,
                      review_status, reviewer, reviewed_at, comments, created_at, updated_at
            """)) {
      statement.setString(1, objectMapper.writeValueAsString(metricsSnapshot));
      statement.setString(2, multiplanarRunId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("Run not found");
        return readRun(connection, rs);
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not update run metrics snapshot", ex);
    }
  }

  private List<DomainAuditEvent> findAuditEvents(String column, String value) {
    try (Connection connection = connection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
            SELECT id, actor, action, entity_id, trace_id, metadata, created_at
            FROM domain_audit_events
            WHERE %s = ?
            ORDER BY created_at, action
            """
                    .formatted(column))) {
      statement.setString(1, value);
      try (ResultSet rs = statement.executeQuery()) {
        List<DomainAuditEvent> events = new ArrayList<>();
        while (rs.next()) events.add(readAuditEvent(rs));
        return events;
      }
    } catch (Exception ex) {
      throw new IllegalStateException("Could not find audit events", ex);
    }
  }

  private Study readStudy(ResultSet rs) throws Exception {
    Date studyDate = rs.getDate("study_date");
    UUID patientId = rs.getObject("patient_id", UUID.class);
    return new Study(
        rs.getObject("id", UUID.class).toString(),
        rs.getString("case_id"),
        rs.getString("status"),
        patientId == null ? null : patientId.toString(),
        rs.getString("subject_ref"),
        studyDate == null ? null : studyDate.toLocalDate(),
        rs.getString("modality"),
        rs.getString("description"),
        rs.getString("review_priority"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }

  private DomainAuditEvent readAuditEvent(ResultSet rs) throws Exception {
    return new DomainAuditEvent(
        rs.getObject("id", UUID.class).toString(),
        rs.getString("actor"),
        rs.getString("action"),
        rs.getString("entity_id"),
        rs.getString("trace_id"),
        rs.getTimestamp("created_at").toInstant(),
        readJsonMap(rs.getString("metadata")));
  }

  private InputResource readInput(ResultSet rs) throws Exception {
    return new InputResource(
        rs.getObject("id", UUID.class).toString(),
        rs.getObject("study_id", UUID.class).toString(),
        rs.getString("plane"),
        rs.getString("input_id"),
        rs.getString("format"),
        rs.getLong("size_bytes"),
        rs.getString("description"),
        rs.getString("weighting"),
        rs.getInt("slice_count"),
        rs.getBoolean("multiplanar"),
        rs.getBoolean("derived"),
        rs.getBoolean("analyzable"),
        rs.getTimestamp("created_at").toInstant());
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
        rs.getTimestamp("updated_at").toInstant());
  }

  private List<RunArtifact> findArtifacts(Connection connection, String studyRunId)
      throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT id, study_run_id, run_id, plane, asset_name, content_type, artifact_ref, created_at,
                   storage_status, storage_kind, size_bytes, sha256
            FROM domain_run_artifacts WHERE study_run_id = ?::uuid ORDER BY plane, asset_name
            """)) {
      statement.setString(1, studyRunId);
      try (ResultSet rs = statement.executeQuery()) {
        List<RunArtifact> artifacts = new ArrayList<>();
        while (rs.next()) {
          artifacts.add(readArtifact(rs));
        }
        return artifacts;
      }
    }
  }

  private RunArtifact readArtifact(ResultSet rs) throws Exception {
    long sizeBytes = rs.getLong("size_bytes");
    return new RunArtifact(
        rs.getObject("id", UUID.class).toString(),
        rs.getObject("study_run_id", UUID.class).toString(),
        rs.getString("run_id"),
        rs.getString("plane"),
        rs.getString("asset_name"),
        rs.getString("content_type"),
        rs.getString("artifact_ref"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getString("storage_status"),
        rs.getString("storage_kind"),
        rs.wasNull() ? null : sizeBytes,
        rs.getString("sha256"));
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
    if (!databaseUrl.startsWith("postgres://") && !databaseUrl.startsWith("postgresql://"))
      return databaseUrl;
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
      if (!password.isBlank())
        query += "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
      int port = uri.getPort() > 0 ? uri.getPort() : 5432;
      return "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath() + "?" + query;
    } catch (Exception ex) {
      return databaseUrl;
    }
  }
}
