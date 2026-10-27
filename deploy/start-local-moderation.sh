#!/usr/bin/env bash
# Invoked by start-local-moderation.ps1 inside the user's Ubuntu WSL distribution.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="${XDG_DATA_HOME:-$HOME/.local/share}/aloute/moderation"
venv_dir="$runtime_dir/venv"
mkdir -p -- "$runtime_dir" "$repo_root/target"
exec 9>"$runtime_dir/start.lock"
flock -w 120 9 || { echo 'Another moderation setup is still running.' >&2; exit 1; }

for executable in python3 ffmpeg ffprobe; do
    command -v "$executable" >/dev/null || {
        echo "Missing $executable. Run start-local-moderation.ps1 -InstallDependencies first." >&2
        exit 1
    }
done
if [[ ! -f "$repo_root/.env" ]]; then
    echo 'Missing project .env. Configure OPENAI_API_KEY and MODERATION_API_TOKEN privately.' >&2
    exit 1
fi

# No shell sourcing of .env: uvicorn's dotenv reader supplies configuration to the service.
if [[ ! -x "$venv_dir/bin/python" ]]; then
    python3 -m venv "$venv_dir"
fi
requirements_digest="$(sha256sum "$repo_root/moderation/requirements.txt" | cut -d ' ' -f 1)"
installed_digest="$(cat "$runtime_dir/requirements.sha256" 2>/dev/null || true)"
if [[ "$requirements_digest" != "$installed_digest" ]]; then
    "$venv_dir/bin/python" -m pip install --disable-pip-version-check -r "$repo_root/moderation/requirements.txt"
    printf '%s\n' "$requirements_digest" >"$runtime_dir/requirements.sha256"
fi

if [[ "${1:-}" != '--serve' ]]; then
    echo 'Moderation environment is ready.'
    exit 0
fi

if "$venv_dir/bin/python" - <<'PY'
import urllib.request, json
try:
    with urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=2) as response:
        health = json.load(response)
        if health.get('status') == 'ok' and health.get('provider') == 'moderation-api':
            raise SystemExit(0)
except Exception:
    pass
raise SystemExit(1)
PY
then
    echo 'Moderation is already healthy on port 8000.'
    exit 0
fi

# Do not replace another listener or stop any existing process automatically.
if "$venv_dir/bin/python" - <<'PY'
import socket
with socket.socket() as connection:
    connection.settimeout(1)
    raise SystemExit(connection.connect_ex(('127.0.0.1', 8000)))
PY
then
    echo 'Port 8000 is occupied but unhealthy. Inspect the existing service before restarting.' >&2
    exit 1
fi

printf '%s\n' "$$" >"$runtime_dir/moderation.pid"
exec 9>&-
# Keep the WSL command attached to this service. Windows starts that command in a hidden window;
# Linux background jobs can otherwise exit with their short-lived wsl.exe launcher.
exec "$venv_dir/bin/python" -m uvicorn main:app --app-dir "$repo_root/moderation" \
    --env-file "$repo_root/.env" --host 127.0.0.1 --port 8000 --workers 1 --limit-concurrency 32
