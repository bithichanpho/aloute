#!/usr/bin/env bash
set -euo pipefail
mkdir -p backups
STAMP=$(date +%Y%m%d-%H%M%S)
docker exec -i aloute-sqlserver /opt/mssql-tools18/bin/sqlcmd \
  -S localhost -U sa -P "${DB_PASSWORD:?DB_PASSWORD is required}" -C \
  -Q "BACKUP DATABASE [${DB_NAME:-aloute_supabase}] TO DISK = N'/var/opt/mssql/data/${DB_NAME:-aloute_supabase}-${STAMP}.bak' WITH INIT, COMPRESSION, STATS=10"
docker cp "aloute-sqlserver:/var/opt/mssql/data/${DB_NAME:-aloute_supabase}-${STAMP}.bak" "backups/${DB_NAME:-aloute_supabase}-${STAMP}.bak"
echo "Backup created: backups/${DB_NAME:-aloute_supabase}-${STAMP}.bak"
