CREATE TABLE IF NOT EXISTS configs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service       VARCHAR(255) NOT NULL,
    env           VARCHAR(50)  NOT NULL,
    config_key    VARCHAR(255) NOT NULL,
    config_value  TEXT         NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_config_service_env_key UNIQUE (service, env, config_key)
);

CREATE INDEX idx_configs_service_env ON configs(service, env);