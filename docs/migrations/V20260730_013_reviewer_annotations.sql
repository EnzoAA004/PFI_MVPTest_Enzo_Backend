-- Anotaciones del revisor sobre una corrida.
--
-- Van en su propia tabla y no dentro de domain_review_corrections: una corrección
-- es siempre un valor de la IA que el revisor cambia (measurement_id, before, after),
-- mientras que una anotación es geometría y texto propios del revisor, que puede no
-- corresponder a ninguna medición. Guardarlas juntas obligaría a dejar la mitad de
-- las columnas vacías en cada fila y a inventar un measurement_id que no existe.
--
-- El alcance es explícito y está restringido por CHECK: es lo que decide dónde se
-- dibuja la anotación, y una fila con scope 'slice' sin corte no se puede ubicar en
-- ninguna imagen. La base de datos rechaza esa combinación en vez de dejar que
-- llegue al visor.
CREATE TABLE IF NOT EXISTS domain_reviewer_annotations (
    id UUID PRIMARY KEY,
    study_run_id UUID NOT NULL REFERENCES domain_study_runs(id) ON DELETE CASCADE,
    scope TEXT NOT NULL,
    kind TEXT NOT NULL,
    plane TEXT,
    series_id TEXT,
    slice_index INTEGER,
    level TEXT,
    -- Puntos en la base normalizada 0..256, la misma de máscaras y landmarks.
    points JSONB NOT NULL DEFAULT '[]'::jsonb,
    value DOUBLE PRECISION,
    -- 'mm' solo si la corrida informó escala física; 'px' cuando no.
    unit TEXT,
    text TEXT NOT NULL DEFAULT '',
    author TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT domain_reviewer_annotations_scope_check
        CHECK (scope IN ('study', 'level', 'slice')),
    CONSTRAINT domain_reviewer_annotations_kind_check
        CHECK (kind IN ('measurement', 'marker', 'note')),
    CONSTRAINT domain_reviewer_annotations_plane_check
        CHECK (plane IS NULL OR plane IN ('sagittal', 'axial')),
    CONSTRAINT domain_reviewer_annotations_unit_check
        CHECK (unit IS NULL OR unit IN ('mm', 'px')),
    -- Una anotación de corte sin plano ni corte no se puede ubicar; una de nivel
    -- sin nivel tampoco.
    CONSTRAINT domain_reviewer_annotations_slice_scope_check
        CHECK (scope <> 'slice' OR (plane IS NOT NULL AND slice_index IS NOT NULL)),
    CONSTRAINT domain_reviewer_annotations_level_scope_check
        CHECK (scope <> 'level' OR level IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_domain_reviewer_annotations_run
    ON domain_reviewer_annotations(study_run_id);
