CREATE TABLE IF NOT EXISTS centrifugo_outbox ( -- If you change table name, it should be changed in centrifugo config too
	id BIGSERIAL PRIMARY KEY,
	method text NOT NULL,
	payload JSONB NOT NULL,
	partition INTEGER NOT NULL default 0,
	created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL
);

-- INSERT INTO centrifugo_outbox (method, payload)
-- VALUES ('publish', '{"channel": "service:some_service:dev", "data": {"key": "some key", "version": 2, "payload": "somedata"}}');

CREATE OR REPLACE FUNCTION centrifugo_notify()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify('centrifugo_notify', NEW.partition::text); -- If you change channel name, it should be changed in centrifugo config too
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER centrifugo_notify_trigger
AFTER INSERT ON centrifugo_outbox
FOR EACH ROW
EXECUTE FUNCTION centrifugo_notify();
