CREATE TABLE IF NOT EXISTS rollouts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id                   UUID NOT NULL REFERENCES configs(id),
    type                        TEXT NOT NULL CHECK (type IN ('instant', 'gradual')),
    status                      TEXT NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'in_progress', 'completed', 'stopped', 'rolled_back')),
    baseline_version            BIGINT NOT NULL,
    target_version              BIGINT NOT NULL,
    total_deployments           INTEGER NOT NULL DEFAULT 1,
    current_deployment          INTEGER NOT NULL DEFAULT 0,
    deployment_interval_seconds INTEGER NOT NULL DEFAULT 0,
    next_deployment_at          TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    stopped_at                  TIMESTAMPTZ,
    rolled_back_at              TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_rollouts_config_id ON rollouts (config_id);
CREATE INDEX IF NOT EXISTS idx_rollouts_status ON rollouts (status);
CREATE INDEX IF NOT EXISTS idx_rollouts_next_deploy ON rollouts (next_deployment_at)
    WHERE status = 'in_progress';