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
- `V20261103__durable_call_sessions.sql` and `V20261104__call_history_idempotency.sql`: durable call state and idempotent call history.
- `V20261105__reserve_historical_call_ids.sql`: historical call-id reservation.
- `V20261106__jsonb_temporal_query_keys.sql`: exact temporal-key functions and matching JSONB expression indexes; this is the index-oriented migration in the CASO source.
- `V20261107__social_following_read_api.sql`: social-following read API/index support.
- `V20261108__expand_account_email_length.sql`: safely widens owned email columns.
- `V20261109__restore_jsonb_timestamp_functions.sql`: retained compatibility helper migration already applied in the previous working database.

The earlier experimental `V20261110__reapply_document_query_indexes.sql` is intentionally absent because its application failed and rolled back. This package keeps the existing migration chain through `V20261109`; it does not add a new Flyway version. Four supplemental indexes adapted to the current temporal-key functions are available as a manually-run SQL script under `deploy/index-optimization/`, outside the Flyway migration directory. See `docs/INDEX_OPTIMIZATION_NO_NEW_FLYWAY_VERSION.md`.

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
