#!/usr/bin/env bash
set -euo pipefail

backend_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$backend_dir"

docker compose -f dev/compose.yaml up -d --wait

# 固定独立开发环境，避免沿用终端中的部署配置。
export RECOOW_DATABASE_URL='postgres://recoow_app:recoow-local-runtime@127.0.0.1:55432/recoow_dev?sslmode=disable'
export RECOOW_DATA="$backend_dir/data/dev"
export RECOOW_OPENAPI="$backend_dir/../openapi/openapi.yaml"
export RECOOW_LISTEN='127.0.0.1:8080'
export RECOOW_ALLOW_INSECURE_COOKIES=1
export RECOOW_BOOTSTRAP_TOKEN=''
export RECOOW_MAX_ACCOUNTS=0
export RECOOW_CORS_ORIGINS=''
export RECOOW_TLS_CERT=''
export RECOOW_TLS_KEY=''
unset RECOOW_MIGRATION_DATABASE_URL

mkdir -p "$RECOOW_DATA"
go build -o "$RECOOW_DATA/api" ./cmd/api
RECOOW_MIGRATION_DATABASE_URL='postgres://recoow_migrator:recoow-local-migration@127.0.0.1:55432/recoow_dev?sslmode=disable' \
  "$RECOOW_DATA/api" -migrate

printf '本地 API: http://127.0.0.1:8080\n数据库保存在 recoow-dev 的 postgres-data 卷；Ctrl+C 仅停止 API。\n'
exec "$RECOOW_DATA/api"
