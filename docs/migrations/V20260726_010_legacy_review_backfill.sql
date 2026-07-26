CREATE TABLE IF NOT EXISTS domain_legacy_review_backfill_runs (
    id TEXT PRIMARY KEY,
    migrated_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    unmatched_count INTEGER NOT NULL DEFAULT 0,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
DECLARE
    has_legacy BOOLEAN;
    migrated_count INTEGER := 0;
    skipped_count INTEGER := 0;
    unmatched_count INTEGER := 0;
BEGIN
    SELECT to_regclass('public.review_statuses') IS NOT NULL INTO has_legacy;

    IF has_legacy THEN
        WITH legacy AS (
            SELECT
                run_id,
                CASE lower(trim(status))
                    WHEN 'pendiente' THEN 'pending'
                    WHEN 'aceptado' THEN 'accepted'
                    WHEN 'observado' THEN 'observed'
                    WHEN 'descartado' THEN 'rejected'
                    WHEN 'rechazado' THEN 'rejected'
                    WHEN 'editado' THEN 'edited'
                    ELSE lower(trim(status))
                END AS review_status,
                COALESCE(reviewer, '') AS reviewer,
                COALESCE(notes, '') AS comments,
                updated_at
            FROM review_statuses
        ),
        eligible AS (
            SELECT ds.id, legacy.*
            FROM legacy
            JOIN domain_study_runs ds ON ds.multiplanar_run_id = legacy.run_id
            WHERE legacy.review_status IN ('pending', 'accepted', 'observed', 'rejected', 'edited')
              AND (ds.updated_at <= legacy.updated_at OR ds.review_status = 'pending')
        )
        UPDATE domain_study_runs ds
        SET review_status = eligible.review_status,
            reviewer = eligible.reviewer,
            reviewed_at = CASE WHEN eligible.review_status = 'pending' THEN NULL ELSE eligible.updated_at END,
            comments = eligible.comments,
            updated_at = eligible.updated_at
        FROM eligible
        WHERE ds.id = eligible.id;

        GET DIAGNOSTICS migrated_count = ROW_COUNT;

        WITH legacy AS (
            SELECT run_id FROM review_statuses
        )
        SELECT count(*) INTO unmatched_count
        FROM legacy
        LEFT JOIN domain_study_runs ds ON ds.multiplanar_run_id = legacy.run_id
        WHERE ds.id IS NULL;

        WITH legacy AS (
            SELECT
                run_id,
                CASE lower(trim(status))
                    WHEN 'pendiente' THEN 'pending'
                    WHEN 'aceptado' THEN 'accepted'
                    WHEN 'observado' THEN 'observed'
                    WHEN 'descartado' THEN 'rejected'
                    WHEN 'rechazado' THEN 'rejected'
                    WHEN 'editado' THEN 'edited'
                    ELSE lower(trim(status))
                END AS review_status,
                updated_at
            FROM review_statuses
        )
        SELECT count(*) INTO skipped_count
        FROM legacy
        JOIN domain_study_runs ds ON ds.multiplanar_run_id = legacy.run_id
        WHERE legacy.review_status NOT IN ('pending', 'accepted', 'observed', 'rejected', 'edited')
           OR NOT (ds.updated_at <= legacy.updated_at OR ds.review_status = 'pending');
    END IF;

    INSERT INTO domain_legacy_review_backfill_runs(id, migrated_count, skipped_count, unmatched_count, executed_at)
    VALUES ('V20260726_010_legacy_review_backfill', migrated_count, skipped_count, unmatched_count, now())
    ON CONFLICT (id) DO UPDATE SET
        migrated_count = EXCLUDED.migrated_count,
        skipped_count = EXCLUDED.skipped_count,
        unmatched_count = EXCLUDED.unmatched_count,
        executed_at = EXCLUDED.executed_at;

    INSERT INTO domain_audit_events(id, actor, action, entity_id, trace_id, metadata, created_at)
    VALUES (
        (
            substr(md5('legacy-review-backfill:' || clock_timestamp()::text), 1, 8) || '-' ||
            substr(md5('legacy-review-backfill:' || clock_timestamp()::text), 9, 4) || '-' ||
            substr(md5('legacy-review-backfill:' || clock_timestamp()::text), 13, 4) || '-' ||
            substr(md5('legacy-review-backfill:' || clock_timestamp()::text), 17, 4) || '-' ||
            substr(md5('legacy-review-backfill:' || clock_timestamp()::text), 21, 12)
        )::uuid,
        'backend',
        'legacy.review.backfill',
        'legacy-review-backfill',
        '',
        jsonb_build_object('migrated', migrated_count, 'skipped', skipped_count, 'unmatched', unmatched_count),
        now()
    );
END $$;
