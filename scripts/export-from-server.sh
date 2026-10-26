#!/usr/bin/env bash
set -euo pipefail
umask 077

repository_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$repository_directory/deploy/common.sh"
host=
output_path=
mode=systemd
remote_script=/opt/recoow/export.sh
remote_bundle=
container=recoow
ssh_options=(-o BatchMode=yes)
while [ "$#" -gt 0 ]; do
    case "$1" in
        --host|--output|--port|--identity|--mode|--remote-script|--bundle|--container)
            [ "$#" -ge 2 ] || die "$1 缺少参数"
            case "$1" in
                --host) host=$2 ;; --output) output_path=$2 ;; --mode) mode=$2 ;;
                --remote-script) remote_script=$2 ;; --bundle) remote_bundle=$2 ;; --container) container=$2 ;;
                --port) ssh_options+=(-p "$2") ;; --identity) ssh_options+=(-i "$2") ;;
            esac
            shift 2 ;;
        --help) printf '用法：bash scripts/export-from-server.sh --host USER@HOST --output PATH [--port PORT] [--identity KEY] [--mode systemd|docker] [--remote-script PATH] [--bundle DIR] [--container NAME]\n通过 SSH 安全下载完整迁移包。远程需已安装新 export.sh、root 或免密 sudo。\n'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done
[[ "$host" =~ ^[a-zA-Z0-9_.@:-]+$ ]] && [[ "$host" != -* ]] || die "必须提供有效 --host"
[ -n "$output_path" ] || die "必须指定 --output"
case "$mode" in systemd|docker) ;; *) die "远程导出只支持 systemd/docker" ;; esac
for remote_argument in "$remote_script" "$remote_bundle" "$container"; do
    [[ "$remote_argument" =~ ^[a-zA-Z0-9_./-]*$ ]] || die "远程路径和容器名只能使用字母、数字、下划线、点、斜线和短横线"
done
require_command ssh
mkdir -p "$(dirname -- "$output_path")"
[ ! -e "$output_path" ] || die "输出文件已存在：$output_path"
archive_temporary=$(mktemp "$(dirname -- "$output_path")/.recoow-download.XXXXXX")
trap 'rm -f -- "$archive_temporary"' EXIT
remote_command="bash $remote_script --mode $mode --container $container --output -"
if [ -n "$remote_bundle" ]; then remote_command+=" --bundle $remote_bundle"; fi
ssh "${ssh_options[@]}" "$host" "if [ \"\$(id -u)\" -eq 0 ]; then $remote_command; else sudo -n $remote_command; fi" > "$archive_temporary"
tar -tzf "$archive_temporary" >/dev/null
mv -- "$archive_temporary" "$output_path"
info "迁移包已下载：$output_path（权限 0600）"
