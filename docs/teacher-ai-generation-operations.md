# Teacher AI Generation — Operations Guide

Operational reference for model catalog, pricing, token accounting, quotas, and rollout.
Architecture contract: `.cursor/tasks/teacher-ai-generation/01-architecture-contract.md`.

## Test commands (quality gate)

| Repository | Command | What it verifies |
|------------|---------|------------------|
| `course-service` | `mvn test` | Model policy, generation runs, ledger, cost, quotas, usage APIs, metrics |
| `RAG_service` | `pytest` | LLM routing, usage normalization, embedding defaults, observability |
| `frontend` | `npm run test` | Model picker states, usage formatting, empty dashboards |
| `frontend` | `npm run build` | Production bundle |
| `api-gateway` | no changes required for AI feature | Routes `/api/courses/**` already exist |
| `monitoring` | Prometheus scrape configs | `course-service:8085`, `rag-service:8000` |

Focused AI test subset (course-service):

```bash
mvn test -Dtest=AiModelCatalogServiceTest,AiQuotaServiceTest,AiGenerationMetricsServiceTest,\
GenerationRunServiceTest,TokenUsageLedgerServiceTest,AiUsageCostCalculatorTest,\
AiUsageReportServiceTest,AiGenerationOrchestratorServiceTest
```

## Embedding invariant (Gemini Embedding 2)

> Embeddings are platform infrastructure. Teachers select **LLM models only**.

- Fixed model: `gemini-embedding-2-preview` (3072 dimensions).
- Configured in `RAG_service` (`embedding_model`, `gemini_embedding_model`).
- Stored in `ai_models` with `selectable=false`; never returned by `GET /api/courses/ai/models`.
- `RAG_service` registry rejects `gemini-embedding-2-preview` as `llm_model_id`.
- Chroma physical collections: `course_{id}__gemini_3072`.
- Changing the embedding model requires **full re-index** of all course collections.

Code references:

- `course-service`: `AiModelConstants.EMBEDDING_MODEL_ID`
- `RAG_service`: `app/generation/llm_model_registry.py`, `app/config.py`

## Updating the model catalog

All teacher-visible models live in PostgreSQL (`course-service` Flyway migrations).

### Add a new LLM model

1. **Migration** (or admin SQL in dev): insert into `ai_models`, `ai_model_capabilities`, `ai_model_pricing`, `ai_model_policies`.
2. **RAG_service**: add mapping in `app/generation/llm_model_registry.py` (`LlmModelSpec`).
3. **Provider credentials**: ensure `AI_GOOGLE_ENABLED` / `AI_OPENAI_ENABLED` and API keys in `RAG_service` env.
4. **Policy**: set `allowed_role` (`teacher` / `admin`), `capability` (`course-generation`), optional `monthly_token_quota` / `daily_token_quota`.
5. **No frontend change** — catalog is loaded from `GET /api/courses/ai/models`.

### Disable a model

Set `ai_models.enabled = false` and optional `disabled_reason`. Catalog marks `enabled: false`; generation returns `MODEL_DISABLED`.

### Default model

Set exactly one selectable model with `is_default = true`. Fallback constant: `AiModelConstants.DEFAULT_MODEL_ID` (`gemini-3.5-flash`). Teacher catalog is **Gemini-only** (OpenAI models are not selectable).

## Updating pricing

Prices are in **USD micros per 1M tokens** (`ai_model_pricing`).

1. Insert a new row with `effective_from` (and close the previous row with `effective_to` if needed).
2. `AiModelCatalogService` and `TokenUsageLedgerService` resolve active pricing at ledger write time.
3. **Historical ledger rows keep their `pricing_id` and `cost_micros`** — repricing does not rewrite past entries.
4. Frontend displays hints from catalog API only; **cost on dashboards comes from backend ledger**.

Example:

```sql
INSERT INTO ai_model_pricing (model_id, input_price_per_million_micros, output_price_per_million_micros, currency, effective_from)
VALUES ('gemini-2.5-flash', 150000, 600000, 'USD', NOW());
```

## Token reconciliation and unknown usage

### Source of truth

1. **`token_usage_ledger`** — append-only, auditable per provider call (`provider_request_id`, `attempt_number`).
2. **`ai_usage_aggregates`** — daily/monthly rollups for dashboards and quotas.
3. **`generation_runs`** — lifecycle and correlation (`id`, `rag_request_id`, status).

Teacher/admin dashboards read ledger totals; time-series prefers aggregates.

### Usage sources (`usage_source`)

| Value | Meaning |
|-------|---------|
| `provider_reported` | Provider returned token counts |
| `unavailable` | Provider response had no usage; ledger stores null tokens, no estimated cost |
| `estimated` | Reserved for future estimation paths |
| `reconciled` | Reserved for manual/admin reconciliation |

**Rule:** token usage is never silently discarded when the provider reports it. `TokenUsageLedgerService` deduplicates by `provider_request_id` per run, not across runs.

### Idempotent generation

Duplicate `idempotencyKey` + `SUCCEEDED` run → HTTP 409; quota is not consumed again.

### Traceability

- `course-service` logs: `generationRunId`, `ragRequestId`, `providerRequestId`
- `RAG_service` logs: `generationRunId`, `requestId`, `providerRequestId`
- Prometheus: `ai.generation.*` (course-service), `rag_generation_*` (RAG_service)

## Quotas

Two layers apply before generation starts:

1. **Per-teacher account limit** (`TeacherAiLimitService`, table `teacher_ai_limits`)
   - Default when no row exists: **1,000,000 tokens / month** and **150,000 / day** (sum across all models).
   - Config: `ai.quotas.teacher-default-monthly-tokens`, `ai.quotas.teacher-default-daily-tokens`.
   - Admin role → unlimited without a DB row.
   - Admin can set per-user override: custom caps or `unlimited_access=true`.
   - APIs: `GET /api/courses/ai/usage/me/limit`, admin `GET|PUT|DELETE /api/courses/admin/ai/teacher-limits/{userId}`.
   - Catalog field: `userLimit` (Course Edit banner).

2. **Per-model policy limit** (`AiQuotaService`, `ai_model_policies`)
   - Dimensions: **user** + **model** + **role policy**; periods: **monthly** / **daily**.
   - Catalog exposes per-model `quota.remainingTokens` / `limitTokens`.

Either layer exceeded → HTTP 429, `code: QUOTA_EXCEEDED`.

## Feature rollout

```bash
# Hide model picker; force platform default LLM
AI_MODEL_SELECTION_ENABLED=false

# Provider toggles (course-service catalog availability)
AI_GOOGLE_ENABLED=true
AI_OPENAI_ENABLED=false
```

## Migrations (V4–V9)

| Version | Purpose |
|---------|---------|
| V4 | `ai_models`, pricing, policies |
| V5 | `generation_runs`, `token_usage_ledger`, `ai_usage_aggregates` |
| V6 | `lesson_generation_jobs.generation_run_id` |
| V7 | `daily_token_quota` on policies |
| V9 | `teacher_ai_limits` per-teacher caps / unlimited overrides |

Flyway table: `course_service_flyway_history`. Shared DB with other services — do not drop tables on rollback. Rollback = forward-fix migration or manual SQL; test on staging first.

## Logging and secrets

Verified practices:

- Logs include IDs and token **counts**, not full prompts or file content.
- API keys / JWT secrets are not logged.
- `GlobalExceptionHandler` returns stable error codes, not stack traces to clients.

Do not add logging of: `system_prompt`, `user_message`, raw uploaded text, or `X-API-Key` / provider keys.

## Frontend contract

- No hardcoded model list — models from `GET /api/courses/ai/models`.
- `modelId` + `idempotencyKey` sent on generation requests.
- Costs displayed from `usageSummary` / usage report APIs only.
