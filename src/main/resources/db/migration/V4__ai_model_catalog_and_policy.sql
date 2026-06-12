-- AI model catalog, pricing, and policy (teacher-selectable LLM models only in public APIs)

CREATE TABLE IF NOT EXISTS ai_models
(
    id                    VARCHAR(64) PRIMARY KEY,
    provider              VARCHAR(32)  NOT NULL,
    provider_model_id     VARCHAR(128) NOT NULL,
    display_name          VARCHAR(128) NOT NULL,
    description           TEXT,
    tier                  VARCHAR(16)  NOT NULL,
    context_window_tokens BIGINT,
    selectable            BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    disabled_reason       VARCHAR(512),
    is_default            BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order            INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_model_capabilities
(
    model_id   VARCHAR(64) NOT NULL REFERENCES ai_models (id) ON DELETE CASCADE,
    capability VARCHAR(64) NOT NULL,
    PRIMARY KEY (model_id, capability)
);

CREATE TABLE IF NOT EXISTS ai_model_pricing
(
    id                                 BIGSERIAL PRIMARY KEY,
    model_id                           VARCHAR(64)  NOT NULL REFERENCES ai_models (id) ON DELETE CASCADE,
    input_price_per_million_micros     BIGINT       NOT NULL,
    output_price_per_million_micros    BIGINT       NOT NULL,
    cached_price_per_million_micros    BIGINT,
    reasoning_price_per_million_micros BIGINT,
    currency                           VARCHAR(3)   NOT NULL DEFAULT 'USD',
    effective_from                     TIMESTAMPTZ  NOT NULL,
    effective_to                       TIMESTAMPTZ,
    created_at                         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_model_pricing_model_effective
    ON ai_model_pricing (model_id, effective_from DESC);

CREATE TABLE IF NOT EXISTS ai_model_policies
(
    id                   BIGSERIAL PRIMARY KEY,
    model_id             VARCHAR(64) NOT NULL REFERENCES ai_models (id) ON DELETE CASCADE,
    allowed_role         VARCHAR(32) NOT NULL,
    capability           VARCHAR(64) NOT NULL,
    monthly_token_quota  BIGINT,
    UNIQUE (model_id, allowed_role, capability)
);

-- Infrastructure embedding model (not teacher-selectable; never returned by catalog API)
INSERT INTO ai_models (id, provider, provider_model_id, display_name, description, tier,
                       context_window_tokens, selectable, enabled, is_default, sort_order)
VALUES ('gemini-embedding-2-preview', 'google', 'gemini-embedding-2-preview',
        'Gemini Embedding 2', 'Fixed platform embedding model for vector ingest and retrieval', 'infrastructure',
        NULL, FALSE, TRUE, FALSE, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ai_model_capabilities (model_id, capability)
VALUES ('gemini-embedding-2-preview', 'embedding')
ON CONFLICT DO NOTHING;

-- V1 teacher-selectable LLM models
INSERT INTO ai_models (id, provider, provider_model_id, display_name, description, tier,
                       context_window_tokens, selectable, enabled, is_default, sort_order)
VALUES ('gemini-2.5-flash', 'google', 'gemini-2.5-flash', 'Gemini 2.5 Flash',
        'Fast and economical for outlines, quizzes, and batch generation', 'fast', 1048576, TRUE, TRUE, TRUE, 10),
       ('gemini-2.5-pro', 'google', 'gemini-2.5-pro', 'Gemini 2.5 Pro',
        'Higher quality for long detailed lessons and complex syllabi', 'quality', 1048576, TRUE, TRUE, FALSE, 20),
       ('gpt-4o-mini', 'openai', 'gpt-4o-mini', 'GPT-4o Mini',
        'Budget-friendly alternative for quick drafts and short content', 'fast', 128000, TRUE, TRUE, FALSE, 30),
       ('gpt-4o', 'openai', 'gpt-4o', 'GPT-4o',
        'Premium quality for nuanced lesson writing and creative scenarios', 'quality', 128000, TRUE, TRUE, FALSE, 40)
ON CONFLICT (id) DO NOTHING;

INSERT INTO ai_model_capabilities (model_id, capability)
VALUES ('gemini-2.5-flash', 'course-generation'),
       ('gemini-2.5-pro', 'course-generation'),
       ('gpt-4o-mini', 'course-generation'),
       ('gpt-4o', 'course-generation')
ON CONFLICT DO NOTHING;

-- Pricing (USD micros per 1M tokens; placeholders for ops to update)
INSERT INTO ai_model_pricing (model_id, input_price_per_million_micros, output_price_per_million_micros,
                              cached_price_per_million_micros, currency, effective_from)
VALUES ('gemini-2.5-flash', 150000, 600000, 37500, 'USD', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
       ('gemini-2.5-pro', 1250000, 10000000, NULL, 'USD', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
       ('gpt-4o-mini', 150000, 600000, NULL, 'USD', TIMESTAMPTZ '2026-01-01 00:00:00+00'),
       ('gpt-4o', 2500000, 10000000, NULL, 'USD', TIMESTAMPTZ '2026-01-01 00:00:00+00');

-- Policies: teacher + admin for all four LLMs; stricter monthly quota for gpt-4o (teachers)
INSERT INTO ai_model_policies (model_id, allowed_role, capability, monthly_token_quota)
VALUES ('gemini-2.5-flash', 'teacher', 'course-generation', NULL),
       ('gemini-2.5-flash', 'admin', 'course-generation', NULL),
       ('gemini-2.5-pro', 'teacher', 'course-generation', NULL),
       ('gemini-2.5-pro', 'admin', 'course-generation', NULL),
       ('gpt-4o-mini', 'teacher', 'course-generation', NULL),
       ('gpt-4o-mini', 'admin', 'course-generation', NULL),
       ('gpt-4o', 'teacher', 'course-generation', 500000),
       ('gpt-4o', 'admin', 'course-generation', NULL)
ON CONFLICT (model_id, allowed_role, capability) DO NOTHING;
