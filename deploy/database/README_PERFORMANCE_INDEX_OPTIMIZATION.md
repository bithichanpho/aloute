# ALOUTE merged build with index/performance alignment

This package combines the CASO + DEVELOP merged code with the chat JSON compatibility fix and aligns the runtime with the optimizer branch without rewriting already-applied Flyway history.

## What is included

- Preserves the merged feature/module code and `ChatMessage.callDurationSeconds`, fixing the reported `UnrecognizedPropertyException` when old call records contain that field.
- Keeps the original optimizer migrations `V20261103__iso_document_dates.sql` and `V20261104__indexable_document_dates.sql` as-is. Their source contents match the checksums previously observed in the user's Supabase Flyway history.
- Keeps `V20261106__jsonb_temporal_query_keys.sql` and `V20261109__restore_jsonb_timestamp_functions.sql` so the merged `SqlCriteriaCompiler` continues using the exact JSONB temporal-key expressions and legacy-safe helper functions expected by the merge branch.
- Restores production-friendly defaults from the optimize branch: Thymeleaf cache enabled by default and static resource cache max-age `7d`. Developer overrides remain supported via `THYMELEAF_CACHE=false` and `STATIC_CACHE_MAX_AGE=0s`.
- Includes optional idempotent SQL at `deploy/database/optional-supplemental-jsonb-read-indexes.sql`, targeted at current merged query expressions. It adds indexes for community feed ordering, root comments, repost timelines/lookups, and chat call/client/sender/receiver lookups.
- Includes `deploy/database/verify-jsonb-index-optimization.sql` to verify helper functions and indexes.

## Why the supplemental SQL is separate from Flyway

Do not edit historical Flyway scripts or re-run the optimizer migration under another name just to bypass checksum validation. The active merged code uses `aloute_jsonb_local_datetime_key(jsonb)` and `aloute_jsonb_instant_key(jsonb)` for temporal expressions, while the original optimize migration creates legacy `aloute_jsonb_ts(text)` / `aloute_jsonb_tstz(text)` indexes too. The merge's `V20261106` creates the matching v2 indexes. The supplemental SQL adds only useful gaps; it does not replace existing indexes.

No `V20261110` Flyway migration is added. This preserves the current version history and avoids repeating the earlier failed helper-function attempt.

## Apply to the existing Supabase database

1. Back up the Supabase project first. No tables or records need to be deleted.
2. Start the app from this source and ensure Flyway validates and `V20261106`/`V20261109` have succeeded. Do not use `flyway repair` just to hide a checksum mismatch.
3. Open Supabase SQL Editor and run `deploy/database/verify-jsonb-index-optimization.sql`. The `local_datetime_v2_helper` and `instant_v2_helper` result columns should not be NULL.
4. Run `deploy/database/optional-supplemental-jsonb-read-indexes.sql` once. It is idempotent; rerunning it will not duplicate indexes. The preflight deliberately fails clearly if the table or v2 helper is missing.
5. Verify again, then compare actual query plans and response time on the same data. Indexes improve matching database paths, but the benefit depends on row counts and query plans.

Do not run the supplemental SQL on a database before the base migrations. On a clean database, let Flyway run the project's normal migrations first, then follow steps 3-5.

## Run locally (Windows)

```powershell
.\mvnw.cmd -Dtest=ChatMessageJsonCompatibilityTest test
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

The merged app's configured default port remains `8081`. This package's ZIP structure and SQL/migration consistency can be checked locally, but a full Maven build and live Supabase query-plan benchmark have not been executed in this environment.
