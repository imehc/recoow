#!/usr/bin/env bash
set -euo pipefail

repository_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$repository_directory/deploy/common.sh"
output_path="$repository_directory/dist/recoow-linux-$(date +%Y%m%d-%H%M%S).tar.gz"
while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) [ "$#" -ge 2 ] || die "--output 缺少路径"; output_path=$2; shift 2 ;;
        --help) printf '用法：bash scripts/package-backend.sh [--output PATH]\n构建 Linux amd64/arm64 静态二进制及裸机/Docker 运行包，不包含生产数据。\n'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done
for required in go python3 tar; do require_command "$required"; done
mkdir -p "$(dirname -- "$output_path")"
output_path="$(cd -- "$(dirname -- "$output_path")" && pwd)/$(basename -- "$output_path")"
[ ! -e "$output_path" ] || die "输出文件已存在：$output_path；请选择新名称或先手动移走旧包"
work_directory=$(mktemp -d)
archive_temporary=$(mktemp "$(dirname -- "$output_path")/.recoow-package.XXXXXX")
trap 'rm -rf -- "$work_directory"; rm -f -- "$archive_temporary"' EXIT
mkdir -p "$work_directory/package/schema"
python3 "$repository_directory/scripts/generate_sync_schema.py" --output "$work_directory/package/schema"
for architecture in amd64 arm64; do
    info "构建 Linux/$architecture"
    (cd "$repository_directory/backend" && CGO_ENABLED=0 GOOS=linux GOARCH="$architecture" \
        go build -trimpath -buildvcs=false -ldflags='-s -w' -o "$work_directory/package/recoow-api-linux-$architecture" ./cmd/api)
done
for package_file in common.sh run.sh install.sh export.sh recoow.env.example deploy.env.example Dockerfile docker-compose.yml .dockerignore README.md; do
    cp "$repository_directory/deploy/$package_file" "$work_directory/package/$package_file"
done
chmod 0755 "$work_directory/package/"*.sh
write_manifest "$work_directory/package"
COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' -C "$work_directory/package" -czf "$archive_temporary" .
mv -- "$archive_temporary" "$output_path"
info "运行包：$output_path"
