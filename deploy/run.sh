#!/usr/bin/env bash
set -euo pipefail

bundle_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$bundle_directory/common.sh"

case "${1:-}" in
    --help) printf '用法：bash run.sh [--verify]\n配置：recoow.env；环境变量优先。默认数据目录：运行包/data。\n'; exit 0 ;;
    --verify) require_command sha256sum; verify_bundle "$bundle_directory"; exit 0 ;;
    '') ;;
    *) die "未知参数：$1" ;;
esac

architecture=$(linux_arch)
config_file=${RECOOW_ENV_FILE:-$bundle_directory/recoow.env}
if [ ! -f "$config_file" ] && [ -z "${RECOOW_ENV_FILE:-}" ]; then
    config_file="$bundle_directory/recoow.env.example"
fi
load_config "$config_file"
export RECOOW_DATA=${RECOOW_DATA:-$bundle_directory/data}
export RECOOW_OPENAPI=${RECOOW_OPENAPI:-$bundle_directory/openapi/openapi.yaml}
binary_path="$bundle_directory/recoow-api-linux-$architecture"
[ -x "$binary_path" ] || die "缺少可执行文件：$binary_path"
mkdir -p "$RECOOW_DATA"
exec "$binary_path"
