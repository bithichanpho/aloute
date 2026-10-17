# Database migration validation

## Policy

- PostgreSQL schema changes are versioned as Flyway migrations under `src/main/resources/db/migration`.
- Keep Hibernate `ddl-auto=validate` for the production profile; do not silently mutate a shared database through Hibernate.
- MongoDB document field additions should remain backward compatible with existing documents unless a dedicated migration/backfill is supplied.
- Do not repair orphan relationships by bulk-deleting user data. Audit and reconcile references explicitly.

## Fresh-schema test

`FreshPostgresSchemaValidationIT` runs Flyway against an empty disposable PostgreSQL database and boots a JPA entity manager with `hibernate.hbm2ddl.auto=validate`. Maven Failsafe binds `*IT` tests to `integration-test`/`verify`. CI creates a distinct `aloute_schema_validation` database and supplies the test URL/credentials, so `mvn verify` no longer silently skips this test in CI.

Local Windows run (requires Docker Desktop):

```powershell
.\deploy\scripts\validate-empty-schema.ps1
```

The script only accepts a disposable database on loopback and deletes its temporary PostgreSQL container in `finally`. If the test fails, fix the migration/entity mismatch with a new migration and add appropriate tests; do not edit Flyway history that has already been applied to shared databases.

## Version note

`V20261020` exists in this source revision. Its definitions were inferred against the entity/migration tree; no independent production database dump was supplied to prove equivalence with every deployed schema. Release sign-off should include a fresh-schema run and a schema diff against a sanitized production dump.
