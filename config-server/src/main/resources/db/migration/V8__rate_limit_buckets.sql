CREATE TABLE rate_limit_bucket
(
    bucket_key      VARCHAR(255) PRIMARY KEY,
    tokens          DOUBLE PRECISION NOT NULL,
    last_refill_at  TIMESTAMP NOT NULL
);
