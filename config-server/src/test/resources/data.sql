INSERT INTO environments (id, code, name)
VALUES (1, 'dev', 'Development'),
       (2, 'stage', 'Staging'),
       (3, 'prod', 'Production')
    ON CONFLICT DO NOTHING;
