#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ACTION="${1:-up}"
COMPOSE=(docker compose)
ROOTLESSKIT_API_SOCK="/run/user/$(id -u)/dockerd-rootless/api.sock"

usage() {
  cat <<'USAGE'
Usage:
  scripts/run_merge_dev.sh [up|rebuild|restart|down|logs|ps]

Commands:
  up       Build changed images if needed and start/recreate FeatX.
  rebuild  Rebuild images without cache, then start FeatX.
  restart  Restart containers without rebuilding images.
  down     Stop the Docker Compose stack.
  logs     Follow logs from all services.
  ps       Show service status.

Environment overrides:
  BACKEND_PORT=28080 FRONTEND_PORT=23000 scripts/run_merge_dev.sh up
  FEATX_BUILD_MODE=compose scripts/run_merge_dev.sh up
  FEATX_RUN_MODE=compose scripts/run_merge_dev.sh up
USAGE
}

HOST_MYSQL_CONTAINER=featx-merge-dev-mysql
HOST_BACKEND_CONTAINER=featx-merge-dev-backend
HOST_FRONTEND_CONTAINER=featx-merge-dev-frontend
LEGACY_MYSQL_CONTAINER=featx_ae_current-mysql-1
HOST_MYSQL_VOLUME=featx_ae_current_featx-mysql-data
HOST_REPOS_VOLUME=featx_ae_current_featx-repos

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

read_env_value() {
  local key="$1"
  local fallback="$2"
  if [[ -f .env ]]; then
    local line
    line="$(grep -E "^${key}=" .env | tail -n 1 || true)"
    if [[ -n "$line" ]]; then
      echo "${line#*=}"
      return
    fi
  fi
  echo "$fallback"
}

write_env_value() {
  local key="$1"
  local value="$2"

  if grep -qE "^${key}=" .env; then
    sed -i "s/^${key}=.*/${key}=${value}/" .env
  else
    printf '\n%s=%s\n' "$key" "$value" >> .env
  fi
}

detect_host_ipv4() {
  local host_ip=""

  if command -v ip >/dev/null 2>&1; then
    host_ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{
      for (i = 1; i <= NF; i++) {
        if ($i == "src") {
          print $(i + 1)
          exit
        }
      }
    }')"
  fi

  if [[ -z "$host_ip" ]]; then
    host_ip="$(hostname -I 2>/dev/null | awk '{
      for (i = 1; i <= NF; i++) {
        if ($i !~ /^127\./ && $i !~ /:/) {
          print $i
          exit
        }
      }
    }')"
  fi

  echo "$host_ip"
}

prepare_git_proxy_host() {
  local git_proxy_host="$1"
  local git_proxy_port="$2"
  local detected_host

  if [[ -z "$git_proxy_port" ]]; then
    echo "$git_proxy_host"
    return
  fi

  case "$git_proxy_host" in
    ""|127.0.0.1|localhost|10.0.2.2)
      detected_host="$(detect_host_ipv4)"
      if [[ -n "$detected_host" ]]; then
        write_env_value GIT_PROXY_HOST "$detected_host"
        echo "Using host IP $detected_host for Git proxy." >&2
        echo "$detected_host"
        return
      fi
      ;;
  esac

  echo "$git_proxy_host"
}

port_in_use() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn "( sport = :$port )" | grep -q ":$port"
  elif command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    return 1
  fi
}

compose_service_port() {
  local service="$1"
  local container_port="$2"
  local value

  value="$("${COMPOSE[@]}" port "$service" "$container_port" 2>/dev/null | head -n 1 || true)"
  if [[ -z "$value" ]]; then
    return 1
  fi

  echo "${value##*:}"
}

port_belongs_to_compose_service() {
  local port="$1"
  local service="$2"
  local container_port="$3"
  local compose_port

  compose_port="$(compose_service_port "$service" "$container_port" || true)"
  [[ "$compose_port" == "$port" ]]
}

rootlesskit_port_exists() {
  local port="$1"
  [[ -S "$ROOTLESSKIT_API_SOCK" ]] || return 1
  curl --unix-socket "$ROOTLESSKIT_API_SOCK" -fsS http://localhost/v1/ports 2>/dev/null \
    | tr -d '[:space:]' \
    | grep -q "\"parentPort\":${port}.*\"childPort\":${port}"
}

rootlesskit_port_ids() {
  local port="$1"
  [[ -S "$ROOTLESSKIT_API_SOCK" ]] || return 0
  curl --unix-socket "$ROOTLESSKIT_API_SOCK" -fsS http://localhost/v1/ports 2>/dev/null \
    | tr '{' '\n' \
    | sed -n 's/.*"id":\([0-9]\+\).*"parentPort":'"$port"'.*/\1/p'
}

ensure_rootlesskit_port() {
  local port="$1"
  if rootlesskit_port_exists "$port"; then
    return
  fi
  if [[ ! -S "$ROOTLESSKIT_API_SOCK" ]]; then
    return
  fi

  curl --unix-socket "$ROOTLESSKIT_API_SOCK" -fsS -X POST http://localhost/v1/ports \
    -H 'Content-Type: application/json' \
    -d "{\"proto\":\"tcp4\",\"parentIP\":\"0.0.0.0\",\"parentPort\":${port},\"childIP\":\"127.0.0.1\",\"childPort\":${port}}" \
    >/dev/null
}

delete_rootlesskit_port() {
  local port="$1"
  local id
  for id in $(rootlesskit_port_ids "$port"); do
    curl --unix-socket "$ROOTLESSKIT_API_SOCK" -fsS -X DELETE "http://localhost/v1/ports/${id}" >/dev/null 2>&1 || true
  done
}

find_free_port() {
  local start="$1"
  local port="$start"
  while port_in_use "$port"; do
    port=$((port + 1))
  done
  echo "$port"
}

wait_for_url() {
  local url="$1"
  local name="$2"
  local attempts="${3:-90}"

  printf "Waiting for %s" "$name"
  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo " OK"
      return
    fi
    printf "."
    sleep 2
  done

  echo
  echo "Timed out waiting for $name at $url" >&2
  echo "Run 'scripts/run_merge_dev.sh logs' to inspect service logs." >&2
  exit 1
}

image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

ensure_env_file() {
  if [[ ! -f .env ]]; then
    cp .env.example .env
    echo "Created .env from .env.example"
  fi
}

prepare_ports() {
  local requested_backend="${BACKEND_PORT:-$(read_env_value BACKEND_PORT 8080)}"
  local requested_frontend="${FRONTEND_PORT:-$(read_env_value FRONTEND_PORT 3000)}"

  if [[ -z "${BACKEND_PORT:-}" ]] && port_in_use "$requested_backend" \
      && ! port_belongs_to_compose_service "$requested_backend" backend 8080 \
      && ! rootlesskit_port_exists "$requested_backend"; then
    BACKEND_PORT="$(find_free_port "$requested_backend")"
    export BACKEND_PORT
    write_env_value BACKEND_PORT "$BACKEND_PORT"
    echo "Backend port $requested_backend is busy; using $BACKEND_PORT and saving it to .env."
  else
    BACKEND_PORT="$requested_backend"
    export BACKEND_PORT
  fi

  if [[ -z "${FRONTEND_PORT:-}" ]] && port_in_use "$requested_frontend" \
      && ! port_belongs_to_compose_service "$requested_frontend" frontend 80 \
      && ! rootlesskit_port_exists "$requested_frontend"; then
    FRONTEND_PORT="$(find_free_port "$requested_frontend")"
    export FRONTEND_PORT
    write_env_value FRONTEND_PORT "$FRONTEND_PORT"
    echo "Frontend port $requested_frontend is busy; using $FRONTEND_PORT and saving it to .env."
  else
    FRONTEND_PORT="$requested_frontend"
    export FRONTEND_PORT
  fi
}

expose_host_ports() {
  ensure_rootlesskit_port "$BACKEND_PORT"
  ensure_rootlesskit_port "$FRONTEND_PORT"
}

prepare_mysql_port() {
  local requested_mysql="${FEATX_MYSQL_PORT:-$(read_env_value FEATX_MYSQL_PORT 3307)}"

  if [[ -z "${FEATX_MYSQL_PORT:-}" ]] && port_in_use "$requested_mysql"; then
    FEATX_MYSQL_PORT="$(find_free_port "$requested_mysql")"
    export FEATX_MYSQL_PORT
    write_env_value FEATX_MYSQL_PORT "$FEATX_MYSQL_PORT"
    echo "MySQL port $requested_mysql is busy; using $FEATX_MYSQL_PORT and saving it to .env."
  else
    FEATX_MYSQL_PORT="$requested_mysql"
    export FEATX_MYSQL_PORT
  fi
}

host_overlay_build() {
  local default_language
  default_language="$(read_env_value REACT_APP_DEFAULT_LANGUAGE CN)"

  if ! image_exists featx-backend:ase26 || ! image_exists featx-frontend:ase26; then
    echo "Cannot use host overlay build because base FeatX images are missing." >&2
    echo "Use rootful Docker or run: FEATX_BUILD_MODE=compose scripts/run_merge_dev.sh up" >&2
    exit 1
  fi

  if command -v javac >/dev/null 2>&1; then
    echo "Building backend jar on host..."
    (cd Backend && ./mvnw -q -DskipTests package)
  else
    echo "Host javac was not found; building backend jar in a JDK 17 container..."
    docker run --rm \
      -e HOME=/tmp \
      -e MAVEN_USER_HOME=/root/.m2 \
      -v "$ROOT_DIR:/workspace" \
      -v featx-maven-cache:/root/.m2 \
      -w /workspace/Backend \
      eclipse-temurin:17-jdk-jammy \
      sh -lc "./mvnw -q -DskipTests package && chmod -R a+rwX target"
  fi

  echo "Building frontend bundle on host..."
  if [[ ! -d Frontend/node_modules ]]; then
    (cd Frontend && npm install)
  fi
  (cd Frontend && REACT_APP_API_BASE_URL=/api REACT_APP_DEFAULT_LANGUAGE="$default_language" npm run build)

  local tmp_dir
  local backend_jar
  tmp_dir="$(mktemp -d)"
  backend_jar="$(find Backend/target -maxdepth 1 -type f -name '*.jar' | head -n 1)"
  if [[ -z "$backend_jar" ]]; then
    echo "Backend jar was not found under Backend/target." >&2
    rm -rf "$tmp_dir"
    exit 1
  fi

  echo "Updating backend Docker image from existing FeatX runtime..."
  mkdir -p "$tmp_dir/backend"
  cp "$backend_jar" "$tmp_dir/backend/featx-backend.jar"
  cp -a RepoSummary "$tmp_dir/backend/RepoSummary"
  docker build --network host -t featx-backend:ase26 -f - "$tmp_dir/backend" <<'DOCKERFILE'
FROM featx-backend:ase26
RUN command -v git >/dev/null 2>&1 || (apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*)
COPY featx-backend.jar /app/featx-backend.jar
RUN rm -rf /app/RepoSummary
COPY RepoSummary /app/RepoSummary
DOCKERFILE

  echo "Updating frontend Docker image from existing Nginx runtime..."
  mkdir -p "$tmp_dir/frontend"
  cp docker/nginx.conf "$tmp_dir/frontend/nginx.conf"
  cp -a Frontend/build "$tmp_dir/frontend/build"
  docker build -t featx-frontend:ase26 -f - "$tmp_dir/frontend" <<'DOCKERFILE'
FROM featx-frontend:ase26
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY build /usr/share/nginx/html
RUN chmod -R a+rX /usr/share/nginx/html
DOCKERFILE

  rm -rf "$tmp_dir"
}

force_stop_container() {
  local container="$1"
  if docker inspect "$container" >/dev/null 2>&1; then
    local status
    status="$(docker inspect "$container" --format '{{.State.Status}}')"
    if [[ "$status" == "running" ]]; then
      if ! docker stop "$container" >/dev/null 2>&1; then
        local pid
        pid="$(docker inspect "$container" --format '{{.State.Pid}}' 2>/dev/null || echo 0)"
        if [[ "$pid" != "0" ]]; then
          kill "$pid" 2>/dev/null || true
          sleep 3
        fi
      fi
    fi
  fi
}

remove_host_containers() {
  local container
  for container in "$HOST_FRONTEND_CONTAINER" "$HOST_BACKEND_CONTAINER" "$HOST_MYSQL_CONTAINER"; do
    force_stop_container "$container"
    docker rm "$container" >/dev/null 2>&1 || true
  done
}

stop_legacy_mysql_if_needed() {
  if docker inspect "$LEGACY_MYSQL_CONTAINER" >/dev/null 2>&1; then
    local status
    status="$(docker inspect "$LEGACY_MYSQL_CONTAINER" --format '{{.State.Status}}')"
    if [[ "$status" == "running" ]]; then
      echo "Stopping $LEGACY_MYSQL_CONTAINER so the new run can reuse its MySQL data volume."
      force_stop_container "$LEGACY_MYSQL_CONTAINER"
    fi
  fi
}

seed_repos_volume() {
  docker volume create "$HOST_REPOS_VOLUME" >/dev/null
  docker run --rm --network host \
    -v "$HOST_REPOS_VOLUME:/target-repos" \
    featx-backend:ase26 \
    sh -lc 'if [ -z "$(ls -A /target-repos 2>/dev/null)" ]; then cp -a /workspace/repos/. /target-repos/; fi'
}

wait_for_mysql() {
  local mysql_user="$1"
  local mysql_password="$2"

  printf "Waiting for mysql"
  for _ in $(seq 1 90); do
    if docker exec "$HOST_MYSQL_CONTAINER" mysqladmin ping \
        -h127.0.0.1 -P"$FEATX_MYSQL_PORT" \
        -u"$mysql_user" -p"$mysql_password" --silent >/dev/null 2>&1; then
      echo " OK"
      return
    fi
    printf "."
    sleep 2
  done

  echo
  echo "Timed out waiting for MySQL on port $FEATX_MYSQL_PORT" >&2
  docker logs "$HOST_MYSQL_CONTAINER" >&2 || true
  exit 1
}

write_host_nginx_conf() {
  mkdir -p .run
  cat > .run/featx-host-nginx.conf <<NGINX
server {
    listen ${FRONTEND_PORT};
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:${BACKEND_PORT}/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_buffering off;
    }

    location / {
        try_files \$uri /index.html;
    }
}
NGINX
}

start_host_stack() {
  local mysql_root_password mysql_database mysql_user mysql_password
  local llm_api_url llm_api_key
  local openai_base_url openai_api_key openai_api_model sentence_transformer_model
  local git_proxy_host git_proxy_port
  local models_host
  local model_volume_args=()

  prepare_mysql_port
  mysql_root_password="$(read_env_value MYSQL_ROOT_PASSWORD featx_root)"
  mysql_database="$(read_env_value MYSQL_DATABASE lotm)"
  mysql_user="$(read_env_value MYSQL_USER featx)"
  mysql_password="$(read_env_value MYSQL_PASSWORD featx)"
  llm_api_url="$(read_env_value LLM_API_URL https://api.deepseek.com)"
  llm_api_key="$(read_env_value LLM_API_KEY "")"
  openai_base_url="$(read_env_value OPENAI_BASE_URL https://api.deepseek.com)"
  openai_api_key="$(read_env_value OPENAI_API_KEY "")"
  openai_api_model="$(read_env_value OPENAI_API_MODEL deepseek-v4-pro)"
  sentence_transformer_model="$(read_env_value SENTENCE_TRANSFORMER_MODEL sentence-transformers/all-mpnet-base-v2)"
  models_host="$(read_env_value FEATX_MODELS_HOST ../models)"
  git_proxy_host="$(read_env_value GIT_PROXY_HOST 10.0.2.2)"
  git_proxy_port="$(read_env_value GIT_PROXY_PORT "")"
  git_proxy_host="$(prepare_git_proxy_host "$git_proxy_host" "$git_proxy_port")"

  if [[ -n "$models_host" ]]; then
    if [[ "$models_host" != /* ]]; then
      models_host="$ROOT_DIR/$models_host"
    fi
    if [[ -d "$models_host" ]]; then
      model_volume_args=(-v "$models_host:/app/models:ro")
    else
      echo "Models directory $models_host was not found; backend will use image/default model fallbacks." >&2
    fi
  fi

  docker volume create "$HOST_MYSQL_VOLUME" >/dev/null
  seed_repos_volume
  remove_host_containers
  stop_legacy_mysql_if_needed

  docker run -d --name "$HOST_MYSQL_CONTAINER" --network host \
    -e MYSQL_ROOT_PASSWORD="$mysql_root_password" \
    -e MYSQL_DATABASE="$mysql_database" \
    -e MYSQL_USER="$mysql_user" \
    -e MYSQL_PASSWORD="$mysql_password" \
    -v "$HOST_MYSQL_VOLUME:/var/lib/mysql" \
    featx-mysql:ase26 \
    --port="$FEATX_MYSQL_PORT" >/dev/null

  wait_for_mysql "$mysql_user" "$mysql_password"

  docker run -d --name "$HOST_BACKEND_CONTAINER" --network host \
    --env-file .env \
    -e SERVER_PORT="$BACKEND_PORT" \
    -e LTM_REPO_PATH=/workspace/repos \
    -e LOTM_REPO_PATH=/workspace/repos \
    -e SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${FEATX_MYSQL_PORT}/${mysql_database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
    -e SPRING_DATASOURCE_USERNAME="$mysql_user" \
    -e SPRING_DATASOURCE_PASSWORD="$mysql_password" \
    -e LLM_API_URL="$llm_api_url" \
    -e LLM_API_KEY="$llm_api_key" \
    -e OPENAI_BASE_URL="$openai_base_url" \
    -e OPENAI_API_KEY="$openai_api_key" \
    -e OPENAI_API_MODEL="$openai_api_model" \
    -e SENTENCE_TRANSFORMER_MODEL="$sentence_transformer_model" \
    -e GIT_PROXY_HOST="$git_proxy_host" \
    -e GIT_PROXY_PORT="$git_proxy_port" \
    -e DB_HOST=127.0.0.1 \
    -e DB_PORT="$FEATX_MYSQL_PORT" \
    -e DB_NAME="$mysql_database" \
    -e DB_USER="$mysql_user" \
    -e DB_PASSWORD="$mysql_password" \
    -e REPOSUMMARY_PYTHON=/opt/reposummary-venv/bin/python \
    -e REPOSUMMARY_DIR=/app/RepoSummary \
    -e LOMBOK_JAR=/app/Backend/tools/lombok-1.18.36.jar \
    "${model_volume_args[@]}" \
    -v "$HOST_REPOS_VOLUME:/workspace/repos" \
    featx-backend:ase26 >/dev/null

  write_host_nginx_conf
  docker run -d --name "$HOST_FRONTEND_CONTAINER" --network host \
    -v "$ROOT_DIR/.run/featx-host-nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
    featx-frontend:ase26 >/dev/null
}

restart_host_stack() {
  docker restart "$HOST_MYSQL_CONTAINER" "$HOST_BACKEND_CONTAINER" "$HOST_FRONTEND_CONTAINER" >/dev/null
}

show_host_logs() {
  local container
  local running_logs=0

  trap 'kill $(jobs -pr) 2>/dev/null || true' EXIT INT TERM
  for container in "$HOST_MYSQL_CONTAINER" "$HOST_BACKEND_CONTAINER" "$HOST_FRONTEND_CONTAINER"; do
    if docker inspect "$container" >/dev/null 2>&1; then
      docker logs --tail 100 -f "$container" 2>&1 \
        | sed -u "s/^/[$container] /" &
      running_logs=1
    fi
  done

  if [[ "$running_logs" == "0" ]]; then
    echo "No featx-merge-dev containers were found." >&2
    return 1
  fi

  wait
}

show_host_ps() {
  docker ps -a --filter "name=featx-merge-dev" \
    --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
}

compose_build() {
  "${COMPOSE[@]}" "$@"
}

build_images() {
  local mode="${FEATX_BUILD_MODE:-host-overlay}"

  case "$mode" in
    host-overlay)
      host_overlay_build
      ;;
    compose)
      compose_build "$@"
      ;;
    *)
      echo "Unsupported FEATX_BUILD_MODE=$mode; use host-overlay or compose." >&2
      exit 1
      ;;
  esac
}

start_stack() {
  local build_args=("$@")
  ensure_env_file
  prepare_ports

  build_images "${build_args[@]}"
  if [[ "${FEATX_RUN_MODE:-host}" == "compose" ]]; then
    "${COMPOSE[@]}" up -d --no-build
  else
    start_host_stack
    expose_host_ports
  fi

  wait_for_url "http://localhost:${BACKEND_PORT}/connect/test" "backend"
  wait_for_url "http://localhost:${FRONTEND_PORT}/api/connect/test" "frontend API proxy"

  echo
  echo "FeatX is running:"
  echo "  Frontend: http://localhost:${FRONTEND_PORT}/"
  echo "  Backend:  http://localhost:${BACKEND_PORT}/connect/test"
  echo
  echo "Useful commands:"
  echo "  scripts/run_merge_dev.sh logs"
  echo "  scripts/run_merge_dev.sh down"
}

restart_stack() {
  ensure_env_file
  prepare_ports

  if [[ "${FEATX_RUN_MODE:-host}" == "compose" ]]; then
    "${COMPOSE[@]}" restart
  else
    prepare_mysql_port
    restart_host_stack
    expose_host_ports
  fi

  wait_for_url "http://localhost:${BACKEND_PORT}/connect/test" "backend"
  wait_for_url "http://localhost:${FRONTEND_PORT}/api/connect/test" "frontend API proxy"

  echo
  echo "FeatX restarted:"
  echo "  Frontend: http://localhost:${FRONTEND_PORT}/"
  echo "  Backend:  http://localhost:${BACKEND_PORT}/connect/test"
}

require_command docker

case "$ACTION" in
  up)
    start_stack build
    ;;
  rebuild)
    if [[ "${FEATX_BUILD_MODE:-host-overlay}" == "compose" ]]; then
      start_stack build --no-cache
    else
      start_stack build
    fi
    ;;
  restart)
    restart_stack
    ;;
  down)
    if [[ "${FEATX_RUN_MODE:-host}" == "compose" ]]; then
      "${COMPOSE[@]}" down
    else
      remove_host_containers
      delete_rootlesskit_port "$(read_env_value BACKEND_PORT 8080)"
      delete_rootlesskit_port "$(read_env_value FRONTEND_PORT 3000)"
    fi
    ;;
  logs)
    if [[ "${FEATX_RUN_MODE:-host}" == "compose" ]]; then
      "${COMPOSE[@]}" logs -f
    else
      show_host_logs
    fi
    ;;
  ps)
    if [[ "${FEATX_RUN_MODE:-host}" == "compose" ]]; then
      "${COMPOSE[@]}" ps
    else
      show_host_ps
    fi
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage
    exit 1
    ;;
esac
