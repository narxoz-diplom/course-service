-- Refresh teacher catalog: Gemini-only, latest popular models (OpenAI hidden)

UPDATE ai_models
SET selectable  = FALSE,
    enabled     = FALSE,
    is_default  = FALSE,
    disabled_reason = 'Not available — platform uses Google Gemini only'
WHERE id IN ('gpt-4o', 'gpt-4o-mini');

UPDATE ai_models SET is_default = FALSE WHERE is_default = TRUE;

INSERT INTO ai_models (id, provider, provider_model_id, display_name, description, tier,
                       context_window_tokens, selectable, enabled, is_default, sort_order)
VALUES ('gemini-3.5-flash', 'google', 'gemini-3.5-flash', 'Gemini 3.5 Flash',
        'Latest stable Flash — best for agents, coding, and everyday course generation', 'fast',
        1048576, TRUE, TRUE, TRUE, 10),
       ('gemini-3.1-pro-preview', 'google', 'gemini-3.1-pro-preview', 'Gemini 3.1 Pro (Preview)',
        'Advanced reasoning for complex syllabi and long detailed lessons (preview)', 'quality',
        1048576, TRUE, TRUE, FALSE, 20),
       ('gemini-3.1-flash-lite', 'google', 'gemini-3.1-flash-lite', 'Gemini 3.1 Flash-Lite',
        'Low-cost, high-volume generation — outlines, quizzes, batch jobs', 'fast',
        1048576, TRUE, TRUE, FALSE, 25),
       ('gemini-2.5-flash-lite', 'google', 'gemini-2.5-flash-lite', 'Gemini 2.5 Flash-Lite',
        'Economical 2.5 tier for quick drafts and short content', 'fast',
        1048576, TRUE, TRUE, FALSE, 35)
ON CONFLICT (id) DO UPDATE SET
    provider              = EXCLUDED.provider,
    provider_model_id     = EXCLUDED.provider_model_id,
    display_name          = EXCLUDED.display_name,
    description           = EXCLUDED.description,
    tier                  = EXCLUDED.tier,
    context_window_tokens = EXCLUDED.context_window_tokens,
    selectable            = EXCLUDED.selectable,
    enabled               = EXCLUDED.enabled,
    is_default            = EXCLUDED.is_default,
    sort_order            = EXCLUDED.sort_order,
    disabled_reason       = NULL,
    updated_at            = NOW();

UPDATE ai_models
SET sort_order = 30,
    description = 'Proven fast model — outlines, quizzes, and batch generation',
    is_default = FALSE
WHERE id = 'gemini-2.5-flash';

UPDATE ai_models
SET sort_order = 40,
    description = 'Higher quality 2.5 tier for long detailed lessons'
WHERE id = 'gemini-2.5-pro';

INSERT INTO ai_model_capabilities (model_id, capability)
VALUES ('gemini-3.5-flash', 'course-generation'),
       ('gemini-3.1-pro-preview', 'course-generation'),
       ('gemini-3.1-flash-lite', 'course-generation'),
       ('gemini-2.5-flash-lite', 'course-generation')
ON CONFLICT DO NOTHING;

INSERT INTO ai_model_pricing (model_id, input_price_per_million_micros, output_price_per_million_micros,
                              cached_price_per_million_micros, currency, effective_from)
VALUES ('gemini-3.5-flash', 200000, 750000, 50000, 'USD', TIMESTAMPTZ '2026-06-01 00:00:00+00'),
       ('gemini-3.1-pro-preview', 1500000, 12000000, NULL, 'USD', TIMESTAMPTZ '2026-06-01 00:00:00+00'),
       ('gemini-3.1-flash-lite', 100000, 400000, 25000, 'USD', TIMESTAMPTZ '2026-06-01 00:00:00+00'),
       ('gemini-2.5-flash-lite', 100000, 400000, 25000, 'USD', TIMESTAMPTZ '2026-06-01 00:00:00+00');

INSERT INTO ai_model_policies (model_id, allowed_role, capability, monthly_token_quota)
VALUES ('gemini-3.5-flash', 'teacher', 'course-generation', NULL),
       ('gemini-3.5-flash', 'admin', 'course-generation', NULL),
       ('gemini-3.1-pro-preview', 'teacher', 'course-generation', NULL),
       ('gemini-3.1-pro-preview', 'admin', 'course-generation', NULL),
       ('gemini-3.1-flash-lite', 'teacher', 'course-generation', NULL),
       ('gemini-3.1-flash-lite', 'admin', 'course-generation', NULL),
       ('gemini-2.5-flash-lite', 'teacher', 'course-generation', NULL),
       ('gemini-2.5-flash-lite', 'admin', 'course-generation', NULL)
ON CONFLICT (model_id, allowed_role, capability) DO NOTHING;

-- Optional quota on premium preview for teachers (migrated from old gpt-4o cap)
UPDATE ai_model_policies
SET monthly_token_quota = 500000,
    daily_token_quota   = 50000
WHERE model_id = 'gemini-3.1-pro-preview'
  AND allowed_role = 'teacher'
  AND capability = 'course-generation';
