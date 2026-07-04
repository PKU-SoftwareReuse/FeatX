#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.migration}"
MODE="${1:-inspect}"

TABLES=(project_info modules features code_map graph_edge)
TRUNCATE_ORDER=(code_map graph_edge features modules project_info)

usage() {
  cat <<'USAGE'
Usage:
  scripts/migrate_mysql_data.sh inspect
  scripts/migrate_mysql_data.sh import

Environment:
  Copy .env.migration.example to .env.migration and fill the source MySQL
  connection before running this script.

Modes:
  inspect  Compare source and target table counts/columns. This does not write data.
  import   Back up the target tables, then replace them with source table data.
USAGE
}

if [[ "$MODE" == "-h" || "$MODE" == "--help" ]]; then
  usage
  exit 0
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Copy .env.migration.example to .env.migration first." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${SOURCE_MYSQL_HOST:?SOURCE_MYSQL_HOST is required}"
: "${SOURCE_MYSQL_DATABASE:?SOURCE_MYSQL_DATABASE is required}"
: "${SOURCE_MYSQL_USER:?SOURCE_MYSQL_USER is required}"
: "${SOURCE_MYSQL_PASSWORD:?SOURCE_MYSQL_PASSWORD is required}"

SOURCE_MYSQL_PORT="${SOURCE_MYSQL_PORT:-3306}"
SOURCE_MYSQL_SSL_MODE="${SOURCE_MYSQL_SSL_MODE:-PREFERRED}"
TARGET_COMPOSE_PROJECT="${TARGET_COMPOSE_PROJECT:-featx_ae_verify}"
TARGET_MYSQL_CONTAINER="${TARGET_MYSQL_CONTAINER:-${TARGET_COMPOSE_PROJECT}-mysql-1}"
TARGET_MYSQL_DATABASE="${TARGET_MYSQL_DATABASE:-lotm}"
TARGET_MYSQL_USER="${TARGET_MYSQL_USER:-featx}"
TARGET_MYSQL_PASSWORD="${TARGET_MYSQL_PASSWORD:-featx}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
ALLOW_SCHEMA_DIFF="${ALLOW_SCHEMA_DIFF:-0}"

read -r -a DOCKER_CMD <<< "$DOCKER_BIN"

ARTIFACT_DIR="$ROOT_DIR/migration_artifacts"
mkdir -p "$ARTIFACT_DIR"

SOURCE_ARGS=(
  "--protocol=tcp"
  "--host=$SOURCE_MYSQL_HOST"
  "--port=$SOURCE_MYSQL_PORT"
  "--user=$SOURCE_MYSQL_USER"
  "--default-character-set=utf8mb4"
)

if [[ -n "$SOURCE_MYSQL_SSL_MODE" ]]; then
  SOURCE_ARGS+=("--ssl-mode=$SOURCE_MYSQL_SSL_MODE")
fi

mysql_source() {
  MYSQL_PWD="$SOURCE_MYSQL_PASSWORD" mysql "${SOURCE_ARGS[@]}" "$SOURCE_MYSQL_DATABASE" "$@"
}

mysqldump_source() {
  MYSQL_PWD="$SOURCE_MYSQL_PASSWORD" mysqldump "${SOURCE_ARGS[@]}" "$SOURCE_MYSQL_DATABASE" "$@"
}

mysql_target() {
  "${DOCKER_CMD[@]}" exec -i -e MYSQL_PWD="$TARGET_MYSQL_PASSWORD" "$TARGET_MYSQL_CONTAINER" \
    mysql -h127.0.0.1 -u"$TARGET_MYSQL_USER" "$TARGET_MYSQL_DATABASE" "$@"
}

mysqldump_target() {
  "${DOCKER_CMD[@]}" exec -e MYSQL_PWD="$TARGET_MYSQL_PASSWORD" "$TARGET_MYSQL_CONTAINER" \
    mysqldump -h127.0.0.1 -u"$TARGET_MYSQL_USER" "$TARGET_MYSQL_DATABASE" "$@"
}

count_query() {
  local query=""
  local table
  for table in "${TABLES[@]}"; do
    if [[ -n "$query" ]]; then
      query+=" UNION ALL "
    fi
    query+="SELECT '$table' AS table_name, COUNT(*) AS row_count FROM \`$table\`"
  done
  printf '%s;\n' "$query"
}

columns_query() {
  cat <<'SQL'
SELECT table_name, ordinal_position, column_name, column_type, is_nullable,
       COALESCE(column_default, '<NULL>') AS column_default, column_key
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('project_info', 'modules', 'features', 'code_map', 'graph_edge')
ORDER BY table_name, ordinal_position;
SQL
}

schema_diff() {
  local ts source_cols target_cols diff_file
  ts="$(date +%Y%m%d-%H%M%S)"
  source_cols="$ARTIFACT_DIR/source-columns-$ts.tsv"
  target_cols="$ARTIFACT_DIR/target-columns-$ts.tsv"
  diff_file="$ARTIFACT_DIR/schema-diff-$ts.diff"

  mysql_source --batch --raw -e "$(columns_query)" > "$source_cols"
  mysql_target --batch --raw -e "$(columns_query)" > "$target_cols"

  if diff -u "$target_cols" "$source_cols" > "$diff_file"; then
    echo "Schema check: OK. Column definitions match."
    rm -f "$diff_file"
    return 0
  fi

  echo "Schema check: DIFFERENT. Review $diff_file before importing."
  return 1
}

show_counts() {
  echo "Source row counts:"
  mysql_source --table -e "$(count_query)"
  echo
  echo "Target row counts:"
  mysql_target --table -e "$(count_query)"
}

inspect() {
  echo "Checking source connection: $SOURCE_MYSQL_USER@$SOURCE_MYSQL_HOST:$SOURCE_MYSQL_PORT/$SOURCE_MYSQL_DATABASE"
  mysql_source -e "SELECT VERSION() AS source_mysql_version;" >/dev/null

  echo "Checking target container: $TARGET_MYSQL_CONTAINER/$TARGET_MYSQL_DATABASE"
  mysql_target -e "SELECT VERSION() AS target_mysql_version;" >/dev/null

  show_counts
  echo
  schema_diff || true
}

import_data() {
  if ! schema_diff && [[ "$ALLOW_SCHEMA_DIFF" != "1" ]]; then
    echo "Abort: source and target schemas differ. Set ALLOW_SCHEMA_DIFF=1 only after reviewing the diff." >&2
    exit 1
  fi

  local ts backup dump_file
  ts="$(date +%Y%m%d-%H%M%S)"
  backup="$ARTIFACT_DIR/target-backup-$ts.sql"
  dump_file="$ARTIFACT_DIR/source-data-$ts.sql"

  echo "Backing up current target tables to $backup"
  mysqldump_target --single-transaction --skip-triggers --set-gtid-purged=OFF --skip-column-statistics --no-tablespaces --hex-blob "${TABLES[@]}" > "$backup"

  echo "Dumping source table data to $dump_file"
  mysqldump_source \
    --single-transaction \
    --skip-triggers \
    --set-gtid-purged=OFF \
    --skip-column-statistics \
    --no-tablespaces \
    --no-create-info \
    --complete-insert \
    --hex-blob \
    "${TABLES[@]}" > "$dump_file"

  echo "Replacing target table data"
  {
    echo "SET FOREIGN_KEY_CHECKS=0;"
    for table in "${TRUNCATE_ORDER[@]}"; do
      echo "TRUNCATE TABLE \`$table\`;"
    done
    cat "$dump_file"
    echo "SET FOREIGN_KEY_CHECKS=1;"
  } | mysql_target

  echo "Import completed."
  show_counts
  echo
  echo "Target backup: $backup"
  echo "Source data dump: $dump_file"
}

case "$MODE" in
  inspect)
    inspect
    ;;
  import)
    import_data
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac
