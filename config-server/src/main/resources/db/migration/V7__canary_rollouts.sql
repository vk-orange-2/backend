ALTER TABLE rollouts ADD COLUMN canary_percentage INTEGER;

ALTER TABLE rollouts ADD CONSTRAINT rollouts_canary_percentage_check
    CHECK (
        (type = 'canary' AND canary_percentage IS NOT NULL AND canary_percentage BETWEEN 1 AND 100)
            OR (type != 'canary' AND canary_percentage IS NULL)
        );

CREATE INDEX IF NOT EXISTS idx_rollouts_status_type ON rollouts (status, type);
