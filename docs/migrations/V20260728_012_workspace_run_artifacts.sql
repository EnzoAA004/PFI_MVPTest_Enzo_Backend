ALTER TABLE domain_run_artifacts
    DROP CONSTRAINT IF EXISTS domain_run_artifacts_plane_check;

ALTER TABLE domain_run_artifacts
    ADD CONSTRAINT domain_run_artifacts_plane_check
    CHECK (plane IN ('sagittal', 'axial', 'workspace'));
