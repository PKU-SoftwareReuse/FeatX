#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PARENT_DIR="$(dirname "$ROOT_DIR")"
REPO_NAME="$(basename "$ROOT_DIR")"
DATE_STAMP="${DATE_STAMP:-$(date +%Y%m%d)}"
OUT_FILE="${1:-$PARENT_DIR/FeatX_ASE26_artifact_$DATE_STAMP.tar.gz}"

tar \
  --exclude="$REPO_NAME/.git" \
  --exclude="$REPO_NAME/.env" \
  --exclude="$REPO_NAME/.env.migration" \
  --exclude="$REPO_NAME/migration_artifacts" \
  --exclude="$REPO_NAME/out" \
  --exclude="$REPO_NAME/.run" \
  --exclude="$REPO_NAME/gdshow" \
  --exclude="$REPO_NAME/Backend/Untitled-1.sh" \
  --exclude="$REPO_NAME/**/*.iml" \
  --exclude="$REPO_NAME/**/*.ipynb" \
  --exclude="$REPO_NAME/**/node_modules" \
  --exclude="$REPO_NAME/**/build" \
  --exclude="$REPO_NAME/**/target" \
  --exclude="$REPO_NAME/**/__pycache__" \
  --exclude="$REPO_NAME/**/*.pyc" \
  -czf "$OUT_FILE" \
  -C "$PARENT_DIR" "$REPO_NAME"

echo "$OUT_FILE"
