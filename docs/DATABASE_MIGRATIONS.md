# Database migration validation

## Policy

- PostgreSQL schema changes are versioned as Flyway migrations under `src/main/resources/db/migration`.
- Keep Hibernate `ddl-auto=validate` for the production profile; do not silently mutate a shared database through Hibernate.
- JSONB document-field changes should remain backward compatible unless a dedicated Flyway backfill is supplied. All query-critical JSONB indexes belong in versioned PostgreSQL migrations.
- Do not repair orphan relationships by bulk-deleting user data. Audit and reconcile references explicitly.
- Never rename, delete or edit an applied migration, or reuse its version for different SQL. Add a higher version instead. The deployed 28-file baseline through V20261110 is pinned by `MigrationHistoryContractTest`; future migrations do not require rewriting that baseline.
- The historical migration chain owns `public`. Use `DB_SCHEMA=public` consistently for this release. Moving an existing installation to another schema needs a reviewed migration; changing the environment variable alone does not relocate historical tables.
- Flyway validation and `clean-disabled` remain enabled. Keep `out-of-order=true` for the existing V20261104.1 compatibility migration, which some databases receive after later versions. New migrations must be newer than V20261110.
- SQL migration line endings are pinned to LF. Tests also detect stale/duplicate migration resources left in target after switching source snapshots. Package releases from clean build output; do not copy target or .env between machines.

## Fresh-schema test

`FreshPostgresSchemaValidationIT` creates its own random local test database, applies Flyway and boots a JPA entity manager with `hibernate.hbm2ddl.auto=validate`. `MigrationCompatibilityIT` also checks upgrades with historical post/call data, a late V20261104.1, checksum rejection without repair/data changes, repeat startup and two concurrent migrators. Only randomly named databases created by these tests are dropped.

Maven Failsafe binds `*IT` tests to `integration-test`/`verify`. CI runs PostgreSQL 17 and 18 with a loopback `aloute_quality_*` test database URL, matching the integration-test safety guards. Hibernate validates the Flyway schema instead of recreating it. CI/test JVM timezone is UTC for reproducible JDBC startup across operating systems; application display/business time settings are unchanged.

Local Windows run (requires Docker Desktop):

```powershell
.\deploy\scripts\validate-empty-schema.ps1
.\deploy\scripts\validate-empty-schema.ps1 -PostgresVersion 17
```

The script uses an available loopback port, runs the contract tests plus fresh/upgrade compatibility tests, and removes only the container it successfully created. It does not bootstrap the real application or use DB_URL. `-Offline` uses cached Maven dependencies; the selected Docker image must already exist locally to avoid a pull. If validation fails, fix the migration/entity mismatch with a new migration and tests; do not edit shared Flyway history.

## When two machines disagree

1. Confirm both use the same source/release artifact and migration files. Keep credentials/local .env separate; check the intended DB host/name/profile without publishing secrets.
2. Compare only Flyway metadata (`version`, `script`, `checksum`, `success`) with the release. A matching version number does not prove matching SQL. Flyway's schema-history lock serializes concurrent migration attempts on the same database.
3. If history matches but schema objects are missing, investigate the drift and add a forward, non-destructive migration where appropriate. Keep a backup and verify on a disposable copy first.
4. If an already applied version has different SQL/checksum, restore the correct historical file/artifact and review both histories. Do not automatically run repair, baseline over the mismatch, delete schema-history rows or disable validation. A genuinely divergent historical database needs a reviewed reconciliation; there is no safe universal auto-repair.

## Version note

Versioned PostgreSQL migrations currently extend through `V20261110__reconcile_document_indexes.sql` (28 files including V20261104.1). V20261102 introduces the JSONB store; subsequent migrations preserve date compatibility, call history, the social read API and query indexes. Never rename these historical files to renumber the chain. Release sign-off includes fresh/upgrade validation and a schema diff against a sanitized production copy.
