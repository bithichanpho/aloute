#!/usr/bin/env sh
set -eu
: "${PGHOST:?Set PGHOST}"
: "${PGUSER:?Set PGUSER}"
: "${PGDATABASE:?Set PGDATABASE}"
OUT="${1:-V1__baseline.sql}"
exec pg_dump --schema-only --no-owner --no-privileges --schema=public \
  --host="$PGHOST" --port="${PGPORT:-5432}" --username="$PGUSER" \
  --dbname="$PGDATABASE" --file="$OUT"
