ALTER TABLE config_versions
    ADD COLUMN IF NOT EXISTS author TEXT;

ALTER TABLE config_versions
    ADD COLUMN IF NOT EXISTS comment TEXT;

CREATE TABLE IF NOT EXISTS audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id       UUID REFERENCES configs(id),
    service_name    TEXT NOT NULL,
    environment     TEXT NOT NULL,
    config_key      TEXT NOT NULL,
    operation       TEXT NOT NULL CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE', 'ROLLBACK')),
    actor           TEXT NOT NULL DEFAULT 'anonymous',
    source_ip       TEXT,
    user_agent      TEXT,
    version_before  BIGINT,
    version_after   BIGINT,
    diff            JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Индексы для поиска (FR-63..FR-65)
CREATE INDEX IF NOT EXISTS idx_audit_log_service    ON audit_log (service_name);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor      ON audit_log (actor);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_log_config_id  ON audit_log (config_id);

-- Уникальный ключ для предотвращения дублирования событий.
-- Формат: "{config_id}:{version}" — гарантирует, что одна версия не породит два события даже при повторной обработке
ALTER TABLE centrifugo_outbox
    ADD COLUMN IF NOT EXISTS idempotency_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_outbox_idempotency
    ON centrifugo_outbox (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
