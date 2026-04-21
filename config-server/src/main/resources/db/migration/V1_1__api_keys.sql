CREATE TABLE api_keys(
    value text NOT NULL,
    service_id UUID REFERENCES services(id) NOT NULL,
    environment_id SMALLINT REFERENCES environments(id) NOT NULL,

    PRIMARY KEY (service_id, environment_id)
);

CREATE INDEX idx_api_keys_value ON api_keys(value);
