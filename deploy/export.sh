#!/usr/bin/env bash
set -euo pipefail
umask 077

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_directory/common.sh"
mode=systemd
bundle_directory=$script_directory
data_directory=
container=recoow
output_path=
while [ "$#" -gt 0 ]; do
    case "$1" in
        --mode|--bundle|--data|--container|--output)
            [ "$#" -ge 2 ] || die "$1 缺少参数"
            case "$1" in
                --mode) mode=$2 ;; --bundle) bundle_directory=$2 ;; --data) data_directory=$2 ;;
                --container) container=$2 ;; --output) output_path=$2 ;;
            esac
            shift 2 ;;
        --help) printf '用法：sudo bash export.sh --output PATH|- [--mode systemd|docker|stopped] [--bundle DIR] [--data DIR] [--container NAME]\n导出完整运行包及所有账号、设备、SQLite/WAL、媒体；运行中服务会短暂停止并自动恢复。\n'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done
case "$mode" in systemd|docker|stopped) ;; *) die "未知模式：$mode" ;; esac
[ -n "$output_path" ] || die "必须指定 --output（- 表示标准输出）"
for required in tar sha256sum flock; do require_command "$required"; done
linux_arch >/dev/null
bundle_directory=$(cd -- "$bundle_directory" && pwd)
verify_bundle "$bundle_directory" runtime
if [ "$output_path" != - ]; then
    mkdir -p "$(dirname -- "$output_path")"
    output_path="$(cd -- "$(dirname -- "$output_path")" && pwd)/$(basename -- "$output_path")"
    [ ! -e "$output_path" ] || die "输出文件已存在：$output_path"
fi
if [ "$mode" != stopped ]; then
    [ "$(id -u)" -eq 0 ] || die "systemd/docker 导出请使用 root 或 sudo"
    exec 9>/run/lock/recoow-maintenance.lock
else
    [ -n "$data_directory" ] || die "stopped 模式必须指定 --data，且确认没有进程写入该目录"
    exec 9>"$data_directory/.recoow-export.lock"
fi
flock -n 9 || die "已有部署或导出正在运行"
work_directory=$(mktemp -d)
restart_needed=false
archive_temporary=
restore_service() {
    if [ "$restart_needed" = true ]; then
        if [ "$mode" = systemd ]; then
            systemctl start recoow.service >&2 || return 1
        else
            docker start "$container" >&2 || return 1
        fi
        restart_needed=false
    fi
}
cleanup() {
    local exit_status=$?
    trap - EXIT
    if ! restore_service; then
        info "严重：原服务未能重新启动，请立即手动恢复"
        exit_status=1
    fi
    rm -rf -- "$work_directory"
    if [ -n "$archive_temporary" ]; then rm -f -- "$archive_temporary"; fi
    exit "$exit_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$work_directory/package/schema"
for package_file in common.sh run.sh install.sh export.sh recoow.env.example deploy.env.example Dockerfile docker-compose.yml .dockerignore README.md; do
    cp "$bundle_directory/$package_file" "$work_directory/package/$package_file"
done
if [ -f "$bundle_directory/build-info.json" ]; then cp "$bundle_directory/build-info.json" "$work_directory/package/build-info.json"; fi
for binary_path in "$bundle_directory"/recoow-api-linux-*; do cp "$binary_path" "$work_directory/package/"; done
if [ "$mode" = docker ]; then
    require_command docker
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container" |
        grep -E '^RECOOW_(LISTEN|DATA|SCHEMA|BOOTSTRAP_TOKEN|MAX_ACCOUNTS|PAIRING_TTL|CORS_ORIGINS|TLS_CERT|TLS_KEY)=' > "$work_directory/source.env"
    load_config "$work_directory/source.env"
    [ -z "$data_directory" ] || die "docker 模式从容器复制 /data，不接受 --data"
    [ "${RECOOW_DATA:-/data}" = /data ] || die "仅支持容器数据目录 /data"
    [ "${RECOOW_SCHEMA:-/app/schema}" = /app/schema ] || die "仅支持容器字段目录 /app/schema"
    docker cp "$container:/app/schema/." "$work_directory/package/schema"
    image_id=$(docker inspect --format '{{.Image}}' "$container")
    architecture=$(docker image inspect --format '{{.Architecture}}' "$image_id")
    case "$architecture" in amd64|arm64) ;; *) die "不支持的容器架构：$architecture" ;; esac
    docker cp "$container:/app/recoow-api" "$work_directory/package/recoow-api-linux-$architecture"
    cmp -s "$bundle_directory/recoow-api-linux-$architecture" "$work_directory/package/recoow-api-linux-$architecture" || die "--bundle 必须来自当前容器使用的同一运行包，确保两个架构的程序版本一致"
    if [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ]; then
        restart_needed=true
        docker stop --time 30 "$container" >&2
        [ "$(docker inspect --format '{{.State.ExitCode}}' "$container")" != 137 ] || die "容器被强制终止，拒绝生成迁移包"
    fi
    mkdir -p "$work_directory/package/data"
    docker cp "$container:/data/." "$work_directory/package/data"
else
    config_file=${RECOOW_ENV_FILE:-$bundle_directory/recoow.env}
    if [ "$mode" = systemd ]; then config_file=${RECOOW_ENV_FILE:-/etc/recoow/recoow.env}; fi
    if [ ! -f "$config_file" ]; then config_file="$bundle_directory/recoow.env.example"; fi
    load_config "$config_file"
    if [ -z "$data_directory" ]; then data_directory=${RECOOW_DATA:-/var/lib/recoow}; fi
    data_directory=$(cd -- "$data_directory" && pwd)
    case "$work_directory/" in "$data_directory/"*) die "临时目录不能位于数据目录内" ;; esac
    if [ "$output_path" != - ]; then
        case "$output_path" in "$data_directory/"*) die "导出文件必须放在数据目录之外" ;; esac
    fi
    if [ "$mode" = systemd ]; then
        systemctl show recoow --no-pager -p User -p WorkingDirectory -p ExecStart >&2
        state=$(systemctl show recoow --no-pager --value -p ActiveState)
        case "$state" in
            active) restart_needed=true; systemctl stop recoow.service ;;
            inactive|failed) ;;
            *) die "服务处于过渡状态：$state；请稍后重试" ;;
        esac
    fi
    copy_tree "${RECOOW_SCHEMA:-$bundle_directory/schema}" "$work_directory/package/schema"
    [ -f "$data_directory/recoow.db" ] || die "数据库不存在：$data_directory/recoow.db"
    copy_tree "$data_directory" "$work_directory/package/data"
fi
[ -s "$work_directory/package/data/recoow.db" ] || die "迁移包缺少数据库"
restore_service || die "原服务恢复失败，导出终止"
for metadata_path in "$work_directory/package/schema"/._*; do
    if [ -f "$metadata_path" ]; then rm -f -- "$metadata_path"; fi
done
rm -f -- "$work_directory/package/data/.recoow-export.lock"
printf '%s\n' \
    'RECOOW_LISTEN=0.0.0.0:8080' 'RECOOW_DATA=' 'RECOOW_SCHEMA=' \
    "RECOOW_BOOTSTRAP_TOKEN=${RECOOW_BOOTSTRAP_TOKEN:-}" \
    "RECOOW_MAX_ACCOUNTS=${RECOOW_MAX_ACCOUNTS:-1}" \
    "RECOOW_PAIRING_TTL=${RECOOW_PAIRING_TTL:-300}" \
    "RECOOW_CORS_ORIGINS=${RECOOW_CORS_ORIGINS:-}" \
    'RECOOW_TLS_CERT=' 'RECOOW_TLS_KEY=' > "$work_directory/package/recoow.env"
chmod 0755 "$work_directory/package/"*.sh "$work_directory/package/"recoow-api-linux-*
chmod -R u+rwX,go-rwx "$work_directory/package/data"
write_manifest "$work_directory/package"
if [ "$output_path" = - ]; then
    COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' -C "$work_directory/package" -czf - .
else
    archive_temporary=$(mktemp "$(dirname -- "$output_path")/.recoow-export.XXXXXX")
    COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' -C "$work_directory/package" -czf "$archive_temporary" .
    mv -- "$archive_temporary" "$output_path"
fi
info "导出完成；迁移包包含全部账号和设备凭据，请按敏感备份保存。原有 TLS 路径已清空，请在新服务器重新配置 HTTPS。"
