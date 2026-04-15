-- 1. Environments (dictionary)
CREATE TABLE IF NOT EXISTS environments (
    id         SMALLINT PRIMARY KEY,
    code       TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL
);

INSERT INTO environments (id, code, name)
VALUES
    (1, 'dev',   'Development'),
    (2, 'stage', 'Staging'),
    (3, 'prod',  'Production')
    ON CONFLICT (id) DO NOTHING;

-- 2. Services
CREATE TABLE IF NOT EXISTS services (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 3. Configs
CREATE TABLE IF NOT EXISTS configs (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID        NOT NULL REFERENCES services(id),
    environment_id  SMALLINT    NOT NULL REFERENCES environments(id),
    config_key      TEXT        NOT NULL,
    current_version BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_configs_service_env_key UNIQUE (service_id, environment_id, config_key)
);

-- 4. Config versions (immutable history)
CREATE TABLE IF NOT EXISTS config_versions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id   UUID        NOT NULL REFERENCES configs(id) ON DELETE CASCADE,
    version     BIGINT      NOT NULL CHECK (version > 0),
    payload     JSONB       NOT NULL,
    change_type TEXT        NOT NULL CHECK (change_type IN ('create', 'update', 'rollback')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_config_versions_config_version UNIQUE (config_id, version)
);

CREATE INDEX IF NOT EXISTS idx_config_versions_config_ver_desc ON config_versions(config_id, version DESC);

-- 5. Centrifugo outbox (transactional outbox pattern)
CREATE TABLE IF NOT EXISTS centrifugo_outbox (
    id         BIGSERIAL   PRIMARY KEY,
    method     TEXT        NOT NULL,
    payload    JSONB       NOT NULL,
    partition  INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Trigger: notify centrifugo immediately after insert
CREATE OR REPLACE FUNCTION centrifugo_notify()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('centrifugo_notify', NEW.partition::text);
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER centrifugo_notify_trigger
    AFTER INSERT ON centrifugo_outbox
    FOR EACH ROW
    EXECUTE FUNCTION centrifugo_notify();
