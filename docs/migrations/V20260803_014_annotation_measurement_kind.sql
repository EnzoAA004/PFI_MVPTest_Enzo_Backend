-- Con qué herramienta se tomó una anotación de tipo medición.
--
-- Va en su propia columna y no dentro de `kind` porque son dos preguntas distintas:
-- `kind` dice si la anotación es una medición, una marca o una nota, y su CHECK es lo
-- que impide que llegue cualquier cosa; esto dice con qué se midió.
--
-- Sin este dato la figura se pierde al recargar: un ángulo son cuatro puntos y una
-- listesis son tres, así que sin saber el tipo el visor los redibujaría como una
-- distancia entre los dos primeros — una medición que el médico nunca tomó.
ALTER TABLE domain_reviewer_annotations
    ADD COLUMN IF NOT EXISTS measurement_kind TEXT;

ALTER TABLE domain_reviewer_annotations
    DROP CONSTRAINT IF EXISTS domain_reviewer_annotations_measurement_kind_check;

ALTER TABLE domain_reviewer_annotations
    ADD CONSTRAINT domain_reviewer_annotations_measurement_kind_check
        CHECK (measurement_kind IS NULL
               OR measurement_kind IN ('distance', 'angle', 'listhesis', 'roi', 'probe'));

-- Una medición sin tipo solo puede existir entre las que se guardaron antes de que la
-- columna existiera: todas eran distancias, que era la única herramienta que había.
UPDATE domain_reviewer_annotations
   SET measurement_kind = 'distance'
 WHERE kind = 'measurement' AND measurement_kind IS NULL;
