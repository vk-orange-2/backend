-- Add 'delete' to the change_type check constraint
ALTER TABLE config_versions DROP CONSTRAINT config_versions_change_type_check;
ALTER TABLE config_versions ADD CONSTRAINT config_versions_change_type_check 
    CHECK (change_type IN ('create', 'update', 'rollback', 'delete'));
