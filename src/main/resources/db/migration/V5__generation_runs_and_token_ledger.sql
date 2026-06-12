-- Generation runs and append-only token usage ledger

CREATE TABLE IF NOT EXISTS generation_runs
(
    id                  VARCHAR(48) PRIMARY KEY,
    idempotency_key     VARCHAR(64),
    teacher_id          VARCHAR(128) NOT NULL,
    course_id           BIGINT,
    job_id              VARCHAR(48),
    generation_type     VARCHAR(40)  NOT NULL,
    requested_model_id  VARCHAR(64)  NOT NULL,
    actual_model_id     VARCHAR(64),
    actual_provider     VARCHAR(32),
    actual_provider_model_id VARCHAR(128),
    status              VARCHAR(20)  NOT NULL,
    rag_request_id      VARCHAR(128),
    error_code          VARCHAR(64),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at         TIMESTAMPTZ,
    CONSTRAINT uq_generation_runs_teacher_idempotency UNIQUE (teacher_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_generation_runs_teacher_created
    ON generation_runs (teacher_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_generation_runs_course_created
    ON generation_runs (course_id, created_at DESC);

CREATE TABLE IF NOT EXISTS token_usage_ledger
(
    id                     BIGSERIAL PRIMARY KEY,
    generation_run_id      VARCHAR(48)  NOT NULL REFERENCES generation_runs (id),
    provider_request_id    VARCHAR(128),
    provider               VARCHAR(32)  NOT NULL,
    model_id               VARCHAR(64)  NOT NULL,
    provider_model_id      VARCHAR(128),
    input_tokens           INT,
    output_tokens          INT,
    cached_tokens          INT,
    reasoning_tokens       INT,
    total_tokens           INT,
    pricing_id             BIGINT REFERENCES ai_model_pricing (id),
    cost_micros            BIGINT,
    currency               VARCHAR(3)   NOT NULL DEFAULT 'USD',
    usage_source           VARCHAR(24)  NOT NULL,
    attempt_number         INT          NOT NULL DEFAULT 1,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'RECORDED',
    used_fallback          BOOLEAN      NOT NULL DEFAULT FALSE,
    fallback_from_model_id VARCHAR(64),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_token_usage_non_negative CHECK (
        (input_tokens IS NULL OR input_tokens >= 0)
            AND (output_tokens IS NULL OR output_tokens >= 0)
            AND (cached_tokens IS NULL OR cached_tokens >= 0)
            AND (reasoning_tokens IS NULL OR reasoning_tokens >= 0)
            AND (total_tokens IS NULL OR total_tokens >= 0)
            AND (cost_micros IS NULL OR cost_micros >= 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_token_usage_ledger_run
    ON token_usage_ledger (generation_run_id, created_at);

CREATE INDEX IF NOT EXISTS idx_token_usage_ledger_model_created
    ON token_usage_ledger (model_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_usage_aggregates
(
    id              BIGSERIAL PRIMARY KEY,
    teacher_id      VARCHAR(128) NOT NULL,
    model_id        VARCHAR(64)  NOT NULL,
    period_type     VARCHAR(16)  NOT NULL,
    period_start    DATE         NOT NULL,
    input_tokens    BIGINT       NOT NULL DEFAULT 0,
    output_tokens   BIGINT       NOT NULL DEFAULT 0,
    cached_tokens   BIGINT       NOT NULL DEFAULT 0,
    reasoning_tokens BIGINT      NOT NULL DEFAULT 0,
    total_tokens    BIGINT       NOT NULL DEFAULT 0,
    cost_micros     BIGINT       NOT NULL DEFAULT 0,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'USD',
    generation_count INT         NOT NULL DEFAULT 0,
    ledger_entry_count INT        NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ai_usage_aggregate UNIQUE (teacher_id, model_id, period_type, period_start)
);
