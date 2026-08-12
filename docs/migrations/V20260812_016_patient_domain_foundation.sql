-- PATIENT-PR1: longitudinal patient identity foundation.
--
-- This migration is intentionally additive. Existing studies are not reconciled from
-- subject_ref (or from any former DICOM identifier), so every historical row keeps its
-- current data and receives patient_id = NULL.

CREATE TABLE IF NOT EXISTS domain_patients (
    id UUID PRIMARY KEY,
    patient_reference TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_domain_patients_reference_not_blank
        CHECK (btrim(patient_reference) <> '')
);

-- PostgreSQL, rather than only Java, owns normalized uniqueness. The stored display
-- value may preserve its case, while comparisons ignore surrounding spaces and case.
CREATE UNIQUE INDEX IF NOT EXISTS uk_domain_patients_reference_normalized
    ON domain_patients ((lower(btrim(patient_reference))));

ALTER TABLE domain_studies
    ADD COLUMN IF NOT EXISTS patient_id UUID NULL;

DO $$
BEGIN
    ALTER TABLE domain_studies
        ADD CONSTRAINT fk_domain_studies_patient
        FOREIGN KEY (patient_id)
        REFERENCES domain_patients(id)
        ON DELETE RESTRICT;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_domain_studies_patient_id
    ON domain_studies(patient_id);
