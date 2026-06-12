ALTER TABLE lesson_generation_jobs
    ADD COLUMN IF NOT EXISTS generation_run_id VARCHAR(48);

CREATE INDEX IF NOT EXISTS idx_lesson_generation_jobs_generation_run
    ON lesson_generation_jobs (generation_run_id);
