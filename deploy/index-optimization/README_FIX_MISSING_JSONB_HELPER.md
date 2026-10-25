# Fix missing JSONB temporal helper and apply indexes (no Flyway version)

The database error `function aloute_jsonb_local_datetime_key(jsonb) does not exist` means the current `aloute_jsonb_instant_key(jsonb)` function body calls a helper that is not resolvable. Do not run an index definition or the original V20261106 migration in isolation.

Run these files **as separate Supabase SQL Editor executions and in this order**:

1. `01_restore_jsonb_temporal_helpers.sql` — restores both helpers with a stable `search_path`, qualifies the helper call as `public.aloute_jsonb_local_datetime_key(...)`, and runs smoke tests. Wait for the result and verify `local_probe`/`instant_probe` are not NULL.
2. `02_apply_18_optimized_indexes_no_flyway.sql` — ensures all 18 indexes exist and runs `ANALYZE`.
3. `03_verify_optimized_indexes.sql` — read-only verification of index presence, definitions, helpers and Flyway history.

The SQL is under `deploy/index-optimization/`, outside Flyway's migration directory. No migration version is added, no payload rows are rewritten, and `public.flyway_schema_history` is not modified by these scripts. Do not run the former combined scripts; they have been removed in favour of the two-step procedure so a later index error cannot rollback the helper fix.
