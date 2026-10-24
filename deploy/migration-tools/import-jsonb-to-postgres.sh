#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "Usage: $0 /absolute/path/to/jsonb-import.csv" >&2
  exit 2
fi
CSV_FILE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
if [[ "$CSV_FILE" == *"'"* ]]; then
  echo "CSV file path must not contain a single quote for psql \\copy." >&2
  exit 2
fi
: "${PGHOST:?Set PGHOST (or use PG* libpq environment variables)}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${PGUSER:?PGUSER is required}"

psql -v ON_ERROR_STOP=1 <<SQL
BEGIN;
CREATE TEMP TABLE jsonb_document_import_stage (
    collection_name varchar(80) NOT NULL,
    document_id varchar(128) NOT NULL,
    payload text NOT NULL
) ON COMMIT DROP;
\\copy jsonb_document_import_stage (collection_name, document_id, payload) FROM '$CSV_FILE' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
INSERT INTO app_document_records (collection_name, document_id, payload, created_at, updated_at)
SELECT collection_name, document_id, payload::jsonb, now(), now()
FROM jsonb_document_import_stage
ON CONFLICT (collection_name, document_id)
DO UPDATE SET payload = EXCLUDED.payload, updated_at = now();
COMMIT;
SQL

echo "Imported legacy document rows into app_document_records. Verify row counts and key application flows before enabling writes."
