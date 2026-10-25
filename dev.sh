#!/usr/bin/env bash
# Native development only: never operates on Docker or the legacy services.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNITS=(featx-dev-summary.service featx-dev-api.service featx-dev-web.service)
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"

install_units() {
  [[ -x "$ROOT/Backend/.venv/bin/uvicorn" ]] || { echo 'Install Backend/requirements.txt into Backend/.venv first.' >&2; exit 1; }
  [[ -f "$ROOT/Frontend/node_modules/react-scripts/bin/react-scripts.js" ]] || { echo 'Run npm ci in Frontend first.' >&2; exit 1; }
  # Read only current service settings; .env is data, not an executable shell script.
  eval "$("$ROOT/Backend/.venv/bin/python" - "$ROOT" <<'PYCONFIG'
import os, shlex, sys
from pathlib import Path
root = Path(sys.argv[1])
values = {}
path = root / '.env'
if path.is_file():
    for line in path.read_text(encoding='utf-8').splitlines():
        key, separator, value = line.strip().partition('=')
        if separator and not key.startswith('#'):
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                value = value[1:-1]
            values[key.strip()] = value
def setting(key, default):
    return os.environ.get(key, values.get(key, default)).strip() or default
shared = Path(setting('SHARED_PROJECT_ROOT', str(root.parent / 'FeatX-shared'))).expanduser()
if not shared.is_absolute():
    shared = root / shared
for key, value in {
    'SHARED_PROJECT_ROOT': str(shared.resolve()),
    'REPOSUMMARY_HTTP_URL': setting('REPOSUMMARY_HTTP_URL', 'http://127.0.0.1:18091'),
}.items():
    print(key + '=' + shlex.quote(value))
PYCONFIG
)"
  local node
  node="$(node -p 'process.execPath')"
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT_DIR/featx-dev-summary.service" <<UNIT
[Unit]
Description=FeatX native RepoSummary
[Service]
WorkingDirectory=$ROOT/PyBackend/src
Environment=PYTHONUNBUFFERED=1
Environment=PYTHONDONTWRITEBYTECODE=1
Environment=SHARED_PROJECT_ROOT=${SHARED_PROJECT_ROOT:-/home/lixutian/Projects_SSD/FeatX-shared}
Environment=REPOSUMMARY_HTTP_HOST=127.0.0.1
Environment=REPOSUMMARY_HTTP_PORT=18091
ExecStart=$ROOT/Backend/.venv/bin/python -m featx_pybackend.api.server
Restart=on-failure
RestartSec=2
TimeoutStopSec=15
UMask=0077
UNIT
  cat > "$UNIT_DIR/featx-dev-api.service" <<UNIT
[Unit]
Description=FeatX native Python API (reload)
After=featx-dev-summary.service
[Service]
WorkingDirectory=$ROOT/Backend
Environment=PYTHONUNBUFFERED=1
Environment=PYTHONDONTWRITEBYTECODE=1
Environment=SHARED_PROJECT_ROOT=${SHARED_PROJECT_ROOT:-/home/lixutian/Projects_SSD/FeatX-shared}
Environment=FEATX_DATA_DIR=$ROOT/data
Environment=REPOSUMMARY_HTTP_URL=${REPOSUMMARY_HTTP_URL:-http://127.0.0.1:18091}
ExecStart=$ROOT/Backend/.venv/bin/uvicorn featx.main:create_app --factory --host 127.0.0.1 --port 13010 --reload --reload-dir $ROOT/Backend/featx
Restart=on-failure
RestartSec=2
TimeoutStopSec=15
UMask=0077
UNIT
  cat > "$UNIT_DIR/featx-dev-web.service" <<UNIT
[Unit]
Description=FeatX native React development server
After=featx-dev-api.service
[Service]
WorkingDirectory=$ROOT/Frontend
Environment=HOST=0.0.0.0
Environment=DANGEROUSLY_DISABLE_HOST_CHECK=true
Environment=PORT=3010
Environment=BROWSER=none
Environment=CI=true
Environment=WDS_SOCKET_PORT=0
Environment=REACT_APP_API_BASE_URL=/api
Environment=FEATX_DEV_BACKEND_URL=http://127.0.0.1:13010
ExecStart=$node $ROOT/Frontend/node_modules/react-scripts/bin/react-scripts.js start
Restart=on-failure
RestartSec=2
TimeoutStopSec=15
UNIT
  systemctl --user daemon-reload
}

case "${1:-up}" in
  up) install_units; systemctl --user start "${UNITS[@]}"; echo '访问入口：http://localhost:3010（首次启动需等待前端编译）' ;;
  down) systemctl --user stop "${UNITS[@]}" ;;
  restart) install_units; systemctl --user restart "${UNITS[@]}" ;;
  ps|status) systemctl --user --no-pager status "${UNITS[@]}" ;;
  logs) journalctl --user -u featx-dev-web -u featx-dev-api -u featx-dev-summary -n 40 -f ;;
  *) echo "Usage: $0 [up|down|restart|ps|logs]" >&2; exit 2 ;;
esac
