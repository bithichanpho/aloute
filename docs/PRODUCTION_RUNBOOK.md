# ALOUTE production runbook

## Pre-deploy gate

1. Pin the exact Git commit or container image to deploy and keep the previous known-good version available.
2. Run `./mvnw --batch-mode clean verify` on Java 21.
3. Validate Compose without printing resolved secrets: `docker compose config --quiet`.
4. Back up PostgreSQL with `deploy/backup-sql.sh`, copy the artifacts off the application host, and verify their checksums.
5. Review every new Flyway migration. Migrations must remain additive and compatible with both the new and immediately previous application version.
6. Flyway applies PostgreSQL JSONB document-store indexes with the rest of the database schema; no separate document database/index step is required.
7. Confirm Redis, RabbitMQ/STOMP, PostgreSQL capacity and the public TURN address before scaling application replicas.

Required production values are documented in `.env.example`. Do not reuse the example values. Keep `.env` and the Firebase Admin service-account file outside Git.

## Deploy and verify

```bash
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8080/actuator/health
```

The public health endpoint exposes status only. Metrics, Prometheus output, OpenAPI and Swagger require an administrator account.

After startup, verify these paths with test accounts on different application replicas:

- login, refresh and logout;
- feed and profile cursor pagination;
- direct/group chat plus reconnect;
- call offer, answer, hangup and 30-second missed-call timeout;
- notification outbox delivery after temporarily interrupting Firebase access;
- upload moderation and media cleanup.

Monitor HTTP latency/error rate, Hikari pool usage, PostgreSQL/Redis/RabbitMQ connection errors, outbox retry growth and moderation retry growth.

## Application rollback

1. Stop routing new traffic to the failed release.
2. Deploy the previous known-good application version.
3. Do not run `flyway undo`, delete migration rows, or manually drop columns. Additive migrations are intentionally kept so the previous version can continue using its old fields.
4. If a migration is incompatible, keep the service in maintenance mode and ship a forward repair migration. Restore a database backup only when data itself is damaged and after preserving the failed database for investigation.
5. Re-run the health and critical-path checks before restoring traffic.

## Data recovery

- PostgreSQL: inspect an artifact first with `pg_restore --list`, then restore into a new database and validate it before switching the application connection.
- PostgreSQL: restore the custom-format archive into a new database with `pg_restore`, apply Flyway migrations, and validate row counts for both relational tables and `app_document_records`.
- Redis is an acceleration/coordination layer, not the system of record for posts, accounts or chat history. Losing Redis invalidates cache, presence and active call state; restart traffic only after rate limiting is healthy.
- RabbitMQ carries real-time delivery. Durable notification intent remains in PostgreSQL, but clients must reconnect for transient chat/presence messages after a broker outage.

Record the deployed commit, migration versions, backup artifact names, verification result and rollback owner for each release.
