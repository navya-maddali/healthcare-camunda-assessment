-- Framework tables shared by every service built on camunda-process-framework.
-- payload is TEXT for H2 compatibility; production Postgres deployments may migrate
-- this column to jsonb in a follow-up migration if native JSON operators are needed.

CREATE TABLE worker_execution (
    business_key   VARCHAR(200) NOT NULL,
    element_id     VARCHAR(200) NOT NULL,
    completed_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result_hash    VARCHAR(200),
    PRIMARY KEY (business_key, element_id)
);

CREATE TABLE process_outbox (
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(200) NOT NULL,
    kind           VARCHAR(20)  NOT NULL,   -- 'START' | 'MESSAGE'
    payload        TEXT         NOT NULL,   -- see comment above re: jsonb
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at  TIMESTAMP
);

CREATE INDEX idx_process_outbox_undispatched
    ON process_outbox (created_at)
    WHERE dispatched_at IS NULL;
