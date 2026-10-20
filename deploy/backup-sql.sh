#!/usr/bin/env bash
set -euo pipefail

# Backward-compatible entry point. ALOUTE now backs up PostgreSQL and MongoDB together.
exec "$(dirname "$0")/backup-datastores.sh" "$@"
