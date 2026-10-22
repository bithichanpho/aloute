#!/usr/bin/env bash
set -euo pipefail
# Backward-compatible script name; PostgreSQL is the only application datastore.
exec "$(dirname "$0")/backup-sql.sh" "$@"
