# One-time document migration into PostgreSQL JSONB

The application no longer connects to a document database. The Flyway migration `V20261102__postgres_jsonb_document_store.sql` creates `app_document_records`; JPA-managed relational tables remain in place, while document-shaped aggregates are persisted with JDBC and JSONB.

**Important:** Flyway does not copy historical documents automatically. If an existing deployment has data in its old MongoDB collections, export those documents before disabling the old service. Do not delete the old database or its backups until row counts and representative application flows have been checked in the new release.

## 1. Export the existing collections

Use a workstation or migration host with the legacy `mongoexport` CLI installed. This is a one-time migration tool only; the application and its Maven dependencies no longer use a MongoDB driver.

```bash
export MONGO_URI='mongodb://<legacy-host>:27017/aloute_db?replicaSet=rs0'
export EXPORT_DIR="$PWD/legacy-document-export"
mkdir -p "$EXPORT_DIR"
for collection in \
  posts comments likes reposts chat_messages userBlock followRequest savedPost \
  commentReaction contentReport postRevision accountSecurity account_cleanup_jobs post_media_assets
do
  mongoexport --uri="$MONGO_URI" --collection="$collection" --jsonArray \
    --out="$EXPORT_DIR/$collection.json"
done
```

Stop writes to the old application before the final export, or use a consistent snapshot/maintenance window so writes are not lost between export and cutover.

## 2. Prepare JSONB rows

Python 3 standard library is sufficient; no third-party Python packages are required.

```bash
python3 deploy/migration-tools/prepare-jsonb-import.py \
  --input-dir "$EXPORT_DIR" \
  --output-file "$PWD/jsonb-document-import.csv" \
  --require-all
```

The converter maps each exported `_id` to the model `id` field and the PostgreSQL `document_id` key, normalizes Extended JSON scalar values, and converts BSON dates to ISO-8601 values compatible with the Java model types. It also maps legacy default collection names (`userBlock`, `followRequest`, `savedPost`, `commentReaction`, `contentReport`, `postRevision`, and `accountSecurity`) to the new snake_case PostgreSQL collection names. Review the per-collection record counts printed by the script.

## 3. Apply the Flyway migration, then import

Deploy the new build in a maintenance window so Flyway applies any new, reviewed migrations in order. `V20261102` creates the JSONB table and initial indexes; `V20261106` adds temporal-key index expressions. Do not change SQL for any migration already applied to a shared database. Stop the application before import so no requests race with backfill. Connect `psql` to the same database as `DB_URL` using the usual `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, and `PGPASSWORD` environment variables (or a configured libpq service):

```bash
export PGHOST='<postgres-host>' PGPORT='5432' PGDATABASE='<database>' PGUSER='<user>'
export PGPASSWORD='<password>'
deploy/migration-tools/import-jsonb-to-postgres.sh "$PWD/jsonb-document-import.csv"
```

The import is transactional and upserts by `(collection_name, document_id)`. A conflicting domain unique index (for example, two like rows for the same post/user) will abort the transaction rather than silently discard data; inspect and resolve such source duplicates before retrying.

After import, compare counts from the export summary with PostgreSQL:

```sql
SELECT collection_name, count(*)
FROM app_document_records
GROUP BY collection_name
ORDER BY collection_name;
```

Smoke-test sign-in/2FA, post feeds and edit history, likes/reactions, threaded comments, chat inbox/unread counters, social blocking/follow requests, moderation retries, and media cleanup before reopening traffic. Keep the legacy export and PostgreSQL backup until cutover is accepted.
