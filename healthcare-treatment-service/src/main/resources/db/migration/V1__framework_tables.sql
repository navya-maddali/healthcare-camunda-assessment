-- Framework-owned tables. The base framework ships these in its scaffold module; this service
-- lives outside the framework tree, so it carries its own copy of the same schema.
--
-- worker_execution backs the framework's JdbcIdempotencyGuard: BaseWorker records
-- (business_key, element_id) on completion and short-circuits a replay of the same pair.
-- process_outbox backs JdbcOutboxRelay.

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
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at  TIMESTAMP
);

CREATE INDEX idx_process_outbox_undispatched
    ON process_outbox (created_at)
    WHERE dispatched_at IS NULL;
