-- BE-015: el estudio guarda todas sus series, no solo las dos que analiza la IA.
--
-- Un estudio de RM lumbar trae sagital T1 y T2, axial T1 y T2, a veces coronales, el
-- localizer y las capturas de la consola. La IA corre sobre dos; el medico lee todas.
-- Hasta aca la ingesta descartaba las otras a los segundos de subir el zip, asi que la
-- pantalla de revision no podia mostrarlas ni aunque quisiera.
--
-- Aditiva: todas las columnas nuevas tienen default, y el CHECK se ensancha en vez de
-- restringirse, asi que las filas existentes siguen siendo validas sin backfill.

-- `plane` describia lo que un modelo puede inferir. Ahora describe donde esta la serie
-- en el paciente, que es una pregunta distinta: hay coronales que ningun modelo toca, y
-- el localizer no tiene un plano unico que declarar.
ALTER TABLE domain_input_resources
    DROP CONSTRAINT IF EXISTS domain_input_resources_plane_check;

ALTER TABLE domain_input_resources
    ADD CONSTRAINT domain_input_resources_plane_check
    CHECK (plane IN ('sagittal', 'axial', 'coronal', 'unknown'));

ALTER TABLE domain_input_resources
    ADD COLUMN IF NOT EXISTS description TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS weighting TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS slice_count INTEGER NOT NULL DEFAULT 0 CHECK (slice_count >= 0),
    -- Serie cuyos cortes no comparten plano: un localizer. No es un volumen.
    ADD COLUMN IF NOT EXISTS multiplanar BOOLEAN NOT NULL DEFAULT FALSE,
    -- Captura de consola o reformateo, no imagen adquirida.
    ADD COLUMN IF NOT EXISTS derived BOOLEAN NOT NULL DEFAULT FALSE,
    -- Si la serie puede ser entrada de una corrida. False cubre tres motivos que en
    -- pantalla se parecen y no son lo mismo: no hay modelo para su ponderacion (axial
    -- T1), no es un volumen de un plano (localizer), o no es dato adquirido (captura).
    --
    -- El default es TRUE y no FALSE porque las filas que ya existen son exactamente las
    -- dos series por estudio que se mandaron a inferir.
    ADD COLUMN IF NOT EXISTS analyzable BOOLEAN NOT NULL DEFAULT TRUE;

-- El visor pide las series de un estudio para poblar el selector de cada viewport.
CREATE INDEX IF NOT EXISTS idx_domain_input_resources_study
    ON domain_input_resources(study_id);
