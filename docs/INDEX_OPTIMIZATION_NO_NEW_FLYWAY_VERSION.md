# Index optimization retained without adding a Flyway version

> **Superseded.** `V20261104_1__durable_call_sessions.sql` and `V20261110__reconcile_document_indexes.sql` now carry the call schema, helper fix and indexes through Flyway, so a fresh database also works. The manual scripts are no longer needed; running them first is harmless. See `docs/DATABASE_MIGRATIONS.md`.

## Requirement

This project is intended to run against the user's existing Supabase database whose successful Flyway history currently ends at `20261109`. Do not add `V20261110` or reuse a prior version for different SQL. The current source includes 26 migrations from `V20260925` through `V20261109`.

## How the original optimize-db SQL was incorporated

The original `aloute-optimize-index-db.zip` uses `V20261103__iso_document_dates.sql` and `V20261104__indexable_document_dates.sql`. Those version numbers conflict with the current CASO chain, where `V20261103` is `durable_call_sessions` and `V20261104` is `call_history_idempotency`. Replacing those migrations would conflict with the current database history and could introduce an unrelated data rewrite, so they are not copied into Flyway.

The merged CASO project already has a compatible nanosecond-preserving implementation in `V20261106__jsonb_temporal_query_keys.sql`: `aloute_jsonb_local_datetime_key(jsonb)`, `aloute_jsonb_instant_key(jsonb)`, and 14 matching expression indexes. The manual index patch adds four access paths (reposts by user/date, reposts by post/date, chat by sender, and chat by group) while keeping everything outside Flyway. It does not rewrite JSONB payload rows and it does not update `flyway_schema_history`.

## Correct workflow (important for the previous missing-helper error)

1. Back up the database and confirm it is the same Supabase project used by the app.
2. Run **the entire** `deploy/index-optimization/01_restore_jsonb_temporal_helpers.sql` by itself in Supabase SQL Editor. Wait for it to succeed. Its two smoke-test fields `local_probe` and `instant_probe` must not be `NULL`. This script replaces the `instant_key` body so the helper call is schema-qualified as `public.aloute_jsonb_local_datetime_key(...)` and uses a stable `search_path`.
3. Only after step 2 succeeds, run the entire `deploy/index-optimization/02_apply_18_optimized_indexes_no_flyway.sql` by itself. The result summary should be `present_count = 18`, `missing_count = 0`.
4. Run `deploy/index-optimization/03_verify_optimized_indexes.sql` (or `verify-optimized-indexes.sql`) and inspect each `indexdef`; the verification also displays temporal helpers and Flyway history.
5. Check that migration `20261109` remains successful and that there is no persisted `20261110` row. If `20261110` appears, stop and examine the exact history record rather than deleting history blindly.
6. Start the application normally, without `migration-recovery`, only after the history check and the project build are clean.

## Operational notes

- Do not copy the old `V20261103`/`V20261104` optimizer migrations into `src/main/resources/db/migration`.
- Do not edit an already-applied migration file to change its checksum.
- `CREATE INDEX IF NOT EXISTS` does not correct a same-named index with a wrong definition; check `indexdef` in the verification results if a name already exists.
- Index creation may block writes or increase I/O. Apply during a low-traffic period.
- Index presence alone does not prove that PostgreSQL's planner uses it. Check key queries with `EXPLAIN (ANALYZE, BUFFERS)` on representative data before claiming a measured speedup.
