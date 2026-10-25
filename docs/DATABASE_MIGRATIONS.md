# Database migration validation

## Policy

- PostgreSQL schema changes are versioned as Flyway migrations under `src/main/resources/db/migration`.
- Keep Hibernate `ddl-auto=validate` for the production profile; do not silently mutate a shared database through Hibernate.
- JSONB document-field changes should remain backward compatible unless a dedicated Flyway backfill is supplied. Query-critical JSONB indexes belong in versioned PostgreSQL migrations.
- Do not repair orphan relationships by bulk-deleting user data. Audit and reconcile references explicitly.
- Never reuse an already-applied Flyway version for different SQL. If a migration has been applied to a shared database, fix it with a new forward migration rather than editing the old file.

## Merged migration baseline

The current merged source contains the CASO migration chain through `V20261108__expand_account_email_length.sql` and retains `V20261109__restore_jsonb_timestamp_functions.sql` because that exact migration had already been applied in the user's previous working database.

Important newer migrations include:

- `V20261102__postgres_jsonb_document_store.sql`: JSONB document storage and initial query indexes.
- `V20261103__iso_document_dates.sql` and `V20261104__indexable_document_dates.sql`: kept byte-for-byte because the shared database's history records their checksums. On that database the CASO call SQL is what actually ran under these versions.
- `V20261104_1__durable_call_sessions.sql`: CASO's original `V20261103__durable_call_sessions.sql` and `V20261104__call_history_idempotency.sql`, made idempotent. A fresh database needs it before `V20261105`; on the shared database it is a no-op.
- `V20261105__reserve_historical_call_ids.sql`: historical call-id reservation.
- `V20261106__jsonb_temporal_query_keys.sql`: exact temporal-key functions and matching JSONB expression indexes; this is the index-oriented migration in the CASO source.
- `V20261107__social_following_read_api.sql`: social-following read API/index support.
- `V20261108__expand_account_email_length.sql`: safely widens owned email columns.
- `V20261109__restore_jsonb_timestamp_functions.sql`: retained compatibility helper migration already applied in the previous working database.

- `V20261110__reconcile_document_indexes.sql`: the helper and index changes from `deploy/index-optimization/01` and `02` (same names, `IF NOT EXISTS`), drops the unused `*_ts` indexes and restores the `V20261102` text indexes. Fresh and shared databases end with the same functions and indexes. It replaces the earlier experimental `V20261110__reapply_document_query_indexes.sql`, which failed and rolled back without leaving a history row.

`spring.flyway.out-of-order` is `true` so the shared database, already at `20261109`, accepts `20261104.1`. Fresh databases still apply every version in order. Before deploying, confirm the shared history has no failed (`success = false`) row; Flyway refuses to migrate past one.

## Fresh-schema test

`FreshPostgresSchemaValidationIT` should run Flyway against an empty disposable PostgreSQL database and boot a JPA entity manager with `hibernate.hbm2ddl.auto=validate`. Maven Failsafe binds `*IT` tests to `integration-test`/`verify`. CI creates a distinct schema-validation database and supplies the test URL/credentials.

Local Windows run (requires Docker Desktop):

```powershell
.\deploy\scripts\validate-empty-schema.ps1
```

The script only accepts a disposable database on loopback. Never run it against Supabase or another shared database.

## Before applying to an existing database

Back up the database and inspect the migration history:

```sql
SELECT installed_rank, version, description, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
```

For the database used in the prior troubleshooting, confirm versions `20261103`–`20261109` have the matching descriptions and `success = true`. If a failed `20261110` row exists, do not delete it blindly; first preserve the history and verify the exact failed row. A new checkout should not use `migration-recovery` or disable Flyway validation to hide unresolved migrations.
