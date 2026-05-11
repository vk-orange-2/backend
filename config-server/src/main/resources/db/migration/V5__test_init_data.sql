INSERT INTO services(id, name)
    VALUES('18946dac-9aef-4362-9c6b-9f5d140ed13c'::uuid, 'client-test-service')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO configs(id, service_id, environment_id, config_key, current_version)
    VALUES('2a3b4c5d-6e7f-8a9b-0c1d-2e3f4a5b6c7d'::uuid, '18946dac-9aef-4362-9c6b-9f5d140ed13c'::uuid, 1, 'example-config', 1)
    ON CONFLICT (service_id, environment_id, config_key) DO NOTHING;

INSERT INTO config_versions(id, config_id, version, payload, change_type)
    VALUES('3b4c5d6e-7f8a-9b0c-1d2e-3f4a5b6c7d8e'::uuid, '2a3b4c5d-6e7f-8a9b-0c1d-2e3f4a5b6c7d'::uuid, 1, '{"message": "Hello from config server!", "enabled": true}', 'create')
    ON CONFLICT (config_id, version) DO NOTHING;

--18946dac-9aef-4362-9c6b-9f5d140ed13c:1:237b4b6b-da35-4501-8852-2131125b4418
-- signingKey is '1234567890123456'
INSERT INTO api_keys(encrypted_key, service_id, environment_id)
    VALUES('FjKmglBOKCqbAVO+3ZK9FsnTcqMM5NikeUz6/aiuCXKAtGpzZXYhkhoT8mHLXEGIjiXA/No2pTcspOj5l2nFUQ==', '18946dac-9aef-4362-9c6b-9f5d140ed13c'::uuid, 1)
    ON CONFLICT (service_id, environment_id) DO NOTHING;
