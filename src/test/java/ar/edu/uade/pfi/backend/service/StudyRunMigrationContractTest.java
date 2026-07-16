package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StudyRunMigrationContractTest {
    @Test
    void migrationDefinesTraceableStudyInputAndRunTablesWithoutBlobColumns() throws Exception {
        String sql = Files.readString(Path.of("docs/migrations/V20260716_005_study_input_run_model.sql")).toLowerCase();

        assertTrue(sql.contains("create table if not exists domain_studies"));
        assertTrue(sql.contains("create table if not exists domain_input_resources"));
        assertTrue(sql.contains("create table if not exists domain_study_runs"));
        assertTrue(sql.contains("multiplanar_run_id text not null unique"));
        assertTrue(sql.contains("trace_id text not null"));
        assertTrue(sql.contains("input_id text not null unique"));
        assertTrue(sql.contains("assets jsonb not null"));
        assertTrue(sql.contains("metrics_snapshot jsonb not null"));
        assertTrue(sql.contains("review_status text not null"));
        assertTrue(sql.contains("'observed'"));
        assertTrue(sql.contains("sagittal_artifact_hash text not null"));
        assertTrue(sql.contains("create table if not exists domain_run_artifacts"));
        assertTrue(sql.contains("create table if not exists domain_review_corrections"));
        assertTrue(sql.contains("before_value jsonb not null"));
        assertTrue(sql.contains("after_value jsonb not null"));
        assertTrue(sql.contains("idx_domain_study_runs_trace_id"));
        assertTrue(sql.contains("idx_domain_run_artifacts_run_plane"));
        assertTrue(sql.contains("idx_domain_review_corrections_run"));
        assertFalse(sql.matches("(?s).*\\b(bytea|blob)\\b.*"));
        assertFalse(sql.contains("image_data"));
        assertFalse(sql.contains("mask_data"));
    }
}
