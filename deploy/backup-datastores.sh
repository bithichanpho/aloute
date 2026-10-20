#!/usr/bin/env bash
set -euo pipefail

: "${POSTGRES_URL:?POSTGRES_URL must be a PostgreSQL URI accepted by pg_dump}"
: "${MONGO_URI:?MONGO_URI is required}"

BACKUP_DIR="${BACKUP_DIR:-backups}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

PG_FILE="$BACKUP_DIR/postgres-$STAMP.dump"
MONGO_FILE="$BACKUP_DIR/mongo-$STAMP.archive.gz"
pg_dump --format=custom --no-owner --no-acl --file="$PG_FILE" "$POSTGRES_URL"
mongodump --uri="$MONGO_URI" --archive="$MONGO_FILE" --gzip
sha256sum "$PG_FILE" "$MONGO_FILE" > "$BACKUP_DIR/checksums-$STAMP.sha256"

echo "Created $PG_FILE"
echo "Created $MONGO_FILE"
echo "Restore-test these artifacts regularly with pg_restore --list and mongorestore --dryRun."
