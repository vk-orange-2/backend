ALTER TABLE configs
    ADD COLUMN is_secret BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE configs
    ADD COLUMN status TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'deleted'));

ALTER TABLE configs
    ADD COLUMN deleted_at TIMESTAMPTZ;