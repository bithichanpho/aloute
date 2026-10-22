#!/usr/bin/env bash
set -euo pipefail

# PostgreSQL is now the only application database. Accept either a libpq URL
# (POSTGRES_URL) or the application's JDBC URL plus DB_USERNAME/DB_PASSWORD.
BACKUP_DIR="${BACKUP_DIR:-backups}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
PG_FILE="$BACKUP_DIR/postgres-$STAMP.dump"

if [[ -n "${POSTGRES_URL:-}" ]]; then
  pg_dump --format=custom --no-owner --no-acl --file="$PG_FILE" "$POSTGRES_URL"
else
  : "${DB_URL:?Set POSTGRES_URL or DB_URL (jdbc:postgresql://host:port/database)}"
  : "${DB_USERNAME:?DB_USERNAME is required}"
  : "${DB_PASSWORD:?DB_PASSWORD is required}"
  jdbc_re='^jdbc:postgresql://([^/:?#]+)(:([0-9]+))?/([^?]+)'
  if [[ ! "$DB_URL" =~ $jdbc_re ]]; then
    echo "DB_URL must be jdbc:postgresql://host[:port]/database" >&2
    exit 2
  fi
  export PGHOST="${BASH_REMATCH[1]}"
  export PGPORT="${BASH_REMATCH[3]:-5432}"
  export PGDATABASE="${BASH_REMATCH[4]}"
  export PGUSER="$DB_USERNAME"
  export PGPASSWORD="$DB_PASSWORD"
  pg_dump --format=custom --no-owner --no-acl --file="$PG_FILE"
fi

sha256sum "$PG_FILE" > "$BACKUP_DIR/checksums-$STAMP.sha256"
echo "Created $PG_FILE"
echo "Verify the artifact with: pg_restore --list '$PG_FILE'"
