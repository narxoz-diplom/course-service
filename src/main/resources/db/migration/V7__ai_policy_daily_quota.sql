-- Optional daily token quotas per model policy (monthly quotas already exist in V4)

ALTER TABLE ai_model_policies
    ADD COLUMN IF NOT EXISTS daily_token_quota BIGINT;

-- Stricter daily cap for premium model (teachers)
UPDATE ai_model_policies
SET daily_token_quota = 50000
WHERE model_id = 'gpt-4o'
  AND allowed_role = 'teacher'
  AND capability = 'course-generation'
  AND daily_token_quota IS NULL;
