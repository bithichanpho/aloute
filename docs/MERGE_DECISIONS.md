# Merge decisions: CASO and DEVELOP

The two source ZIPs do not share identical implementations for 69 common paths. The merge uses CASO as the source of truth for these conflicts and retains DEVELOP-only documentation/tooling instead of splicing class bodies mechanically.

| Area | Selected merged implementation | Reason |
|---|---|---|
| PostgreSQL JSONB criteria/store | CASO | Includes `VALID_DATE_TIME`, named read-view criteria, temporal-key expression generation and matching SQL index migrations. |
| Feed | CASO | Includes the feed query module and query/read-view refactor; the older feed code remains represented through compatibility interfaces where needed. |
| Calls | CASO | Splits call signaling, durable history and completion into module APIs and includes durable session/idempotency migrations. |
| Notifications/outbox | CASO | Uses API abstractions and bulk deletion paths to avoid loading entities merely to delete them. |
| Password reset | CASO | Includes the verified-code proof flow and the `/verify-reset-code` route/template/assets. |
| Caches | CASO | Includes TTL jitter, local cache stats and bounded Redis SCAN-based invalidation. |
| Input validation | CASO | Checks BCrypt UTF-8 byte length, not only Java character count. |
| CI and runtime configuration | CASO | Retains the later CI env and current compose overrides; `application.yml` defaults to port 8081 for a direct local run, while Compose sets `SERVER_PORT=8080`. |
| Documentation/tools | DEVELOP-only files retained | Migration guidance, load test notes, privacy/moderation and production runbook are preserved and stale version notes are updated. |

## Reproducible checks still required

Maven/Java integration tests with PostgreSQL 17 and Redis 7 remain the final authority. The build was not run here because Maven Wrapper could not fetch its Maven distribution. Check the CI workflow and run `mvnw.cmd clean verify` with Java 21 and the project's environment variables before deploying.
