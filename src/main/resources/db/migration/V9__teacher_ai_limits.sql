-- Per-teacher AI generation limits (platform default applies when no row exists)

CREATE TABLE IF NOT EXISTS teacher_ai_limits
(
    teacher_id            VARCHAR(128) PRIMARY KEY,
    unlimited_access      BOOLEAN      NOT NULL DEFAULT FALSE,
    monthly_token_limit   BIGINT,
    daily_token_limit     BIGINT,
    note                  VARCHAR(512),
    updated_by            VARCHAR(128),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_teacher_ai_limits_non_negative CHECK (
        (monthly_token_limit IS NULL OR monthly_token_limit >= 0)
            AND (daily_token_limit IS NULL OR daily_token_limit >= 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_teacher_ai_limits_updated
    ON teacher_ai_limits (updated_at DESC);
