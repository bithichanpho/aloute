# Database migration validation

## Policy

- PostgreSQL schema changes are versioned as Flyway migrations under `src/main/resources/db/migration`.
- Keep Hibernate `ddl-auto=validate` for the production profile; do not silently mutate a shared database through Hibernate.
- JSONB document-field changes should remain backward compatible unless a dedicated Flyway backfill is supplied. All query-critical JSONB indexes belong in versioned PostgreSQL migrations.
- Do not repair orphan relationships by bulk-deleting user data. Audit and reconcile references explicitly.

## Fresh-schema test

`FreshPostgresSchemaValidationIT` runs Flyway against an empty disposable PostgreSQL database and boots a JPA entity manager with `hibernate.hbm2ddl.auto=validate`. Maven Failsafe binds `*IT` tests to `integration-test`/`verify`. CI creates a distinct `aloute_schema_validation` database and supplies the test URL/credentials, so `mvn verify` no longer silently skips this test in CI.

Local Windows run (requires Docker Desktop):

```powershell
.\deploy\scripts\validate-empty-schema.ps1
```

The script only accepts a disposable database on loopback and deletes its temporary PostgreSQL container in `finally`. If the test fails, fix the migration/entity mismatch with a new migration and add appropriate tests; do not edit Flyway history that has already been applied to shared databases.

## Version note

Versioned PostgreSQL migrations in this source extend through `V20261102__postgres_jsonb_document_store.sql`. The migration creates the JSONB table and query-critical indexes used by the JDBC document adapter; previous migrations maintain relational entities and outbox workflows. Release sign-off should include a fresh-schema run and a schema diff against a sanitized production dump.
