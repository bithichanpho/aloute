#!/usr/bin/env bash
set -euo pipefail

if [ ! -f .env ]; then
  echo 'Missing .env. Copy .env.example to .env and fill in the secrets first.'
  exit 1
fi

docker compose up -d --build

docker compose ps
