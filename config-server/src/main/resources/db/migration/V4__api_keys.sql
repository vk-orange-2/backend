CREATE TABLE api_keys(
    encrypted_key text NOT NULL,
    service_id UUID REFERENCES services(id) NOT NULL,
    environment_id SMALLINT REFERENCES environments(id) NOT NULL,

    PRIMARY KEY (service_id, environment_id)
);
