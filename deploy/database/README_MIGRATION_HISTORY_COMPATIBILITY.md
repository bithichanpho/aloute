# Flyway migration-history compatibility for the current Supabase database

This package is aligned to the applied checksums observed in the existing `public.flyway_schema_history` logs on 2026-10-26. It is intended for that existing database, not as a clean-install baseline.

## Active migration files restored to applied variants

- `V20261103__iso_document_dates.sql` — checksum `-529502178`
- `V20261104__indexable_document_dates.sql` — checksum `1623145107`
- `V20261109__restore_jsonb_timestamp_functions.sql` — checksum `-1646065943`

No migration version was added. `V20261110` is not present in the Flyway migration folder. The optimization migrations retain their original names and SQL contents, matching the existing database history and the user-provided Flyway checksums.

## CASO call migration source preservation

The CASO branch reused versions `20261103` and `20261104` for different SQL (`durable_call_sessions` and `call_history_idempotency`). Those SQL files are preserved under `deploy/database/reference-migrations/`, outside Flyway discovery. Since the current database history proceeds through `V20261105__reserve_historical_call_ids.sql`, the call-related schema is expected to have been applied earlier; verify that the call tables and idempotency constraints exist before using call features.

**This package targets the user's existing database.** A clean-install database cannot bootstrap both histories under the same version numbers without assigning distinct forward-only versions or managing the call schema separately. Do not run `flyway repair` merely to hide this version collision.

## Run on Windows

1. Extract this ZIP to a new folder. Restore your local `.env` and Firebase credentials separately; they are not included.
2. Run:

   ```powershell
   .\mvnw.cmd clean test
   .\mvnw.cmd spring-boot:run
   ```

3. The supplied log's checksum mismatches for `20261103`, `20261104`, and `20261109` should be resolved by these source files. If a `ModuleBoundaryTest` ArchUnit violation appears after Flyway succeeds, treat it as a separate test failure and use its own violation report; it is not the cause of the shown application startup failure.
