#!/usr/bin/env bash
set -euo pipefail

repository_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$repository_directory/deploy/common.sh"
output_path=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --output) [ "$#" -ge 2 ] || die "--output 缺少路径"; output_path=$2; shift 2 ;;
        --help) printf '用法：bash scripts/package-backend.sh [--output PATH]\n构建 Linux amd64/arm64 静态二进制及裸机/Docker 运行包，不包含生产数据。\n'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done
for required in go python3 tar make; do require_command "$required"; done
python3 "$repository_directory/scripts/version.py" check
backend_version=$(python3 "$repository_directory/scripts/version.py" show backend --field version)
if [ -z "$output_path" ]; then output_path="$repository_directory/dist/recoow-backend-v$backend_version-linux-$(date +%Y%m%d-%H%M%S).tar.gz"; fi
mkdir -p "$(dirname -- "$output_path")"
output_path="$(cd -- "$(dirname -- "$output_path")" && pwd)/$(basename -- "$output_path")"
[ ! -e "$output_path" ] || die "输出文件已存在：$output_path；请选择新名称或先手动移走旧包"
make -C "$repository_directory" generate-go-api
work_directory=$(mktemp -d)
archive_temporary=$(mktemp "$(dirname -- "$output_path")/.recoow-package.XXXXXX")
trap 'rm -rf -- "$work_directory"; rm -f -- "$archive_temporary"' EXIT
mkdir -p "$work_directory/package/openapi"
python3 "$repository_directory/scripts/version.py" backend-metadata > "$work_directory/package/build-info.json"
backend_commit=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$work_directory/package/build-info.json")
backend_dirty=$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["dirty"]).lower())' "$work_directory/package/build-info.json")
cp "$repository_directory/openapi/openapi.yaml" "$work_directory/package/openapi/openapi.yaml"
for architecture in amd64 arm64; do
    info "构建 Linux/$architecture"
    (cd "$repository_directory/backend" && CGO_ENABLED=0 GOOS=linux GOARCH="$architecture" \
        go build -trimpath -buildvcs=false -ldflags="-s -w -X github.com/imehc/recoow/backend/internal/buildinfo.Commit=$backend_commit -X github.com/imehc/recoow/backend/internal/buildinfo.Dirty=$backend_dirty" -o "$work_directory/package/recoow-api-linux-$architecture" ./cmd/api)
    (cd "$repository_directory/backend" && CGO_ENABLED=0 GOOS=linux GOARCH="$architecture" \
        go build -trimpath -buildvcs=false -o "$work_directory/package/recoow-worker-linux-$architecture" ./cmd/worker)
done
for package_file in common.sh run.sh install.sh export.sh recoow.env.example deploy.env.example Dockerfile docker-compose.yml .dockerignore README.md; do
    cp "$repository_directory/deploy/$package_file" "$work_directory/package/$package_file"
done
chmod 0755 "$work_directory/package/"*.sh
write_manifest "$work_directory/package"
COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' -C "$work_directory/package" -czf "$archive_temporary" .
mv -- "$archive_temporary" "$output_path"
info "运行包：$output_path"
