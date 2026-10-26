#!/usr/bin/env bash
set -euo pipefail

repository_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$repository_directory/deploy/common.sh"
host=
archive_path=
ssh_options=(-o BatchMode=yes)
while [ "$#" -gt 0 ]; do
    case "$1" in
        --host|--archive|--port|--identity)
            [ "$#" -ge 2 ] || die "$1 缺少参数"
            case "$1" in
                --host) host=$2 ;; --archive) archive_path=$2 ;;
                --port) ssh_options+=(-p "$2") ;; --identity) ssh_options+=(-i "$2") ;;
            esac
            shift 2 ;;
        --help) printf '用法：bash scripts/deploy-backend.sh --host USER@HOST --archive PATH [--port PORT] [--identity KEY]\n通过 SSH 上传并安装裸机运行包；远程需 root 或免密 sudo，已有数据与配置保留。\n'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done
[[ "$host" =~ ^[a-zA-Z0-9_.@:-]+$ ]] && [[ "$host" != -* ]] || die "必须提供有效 --host"
[ -f "$archive_path" ] || die "必须提供 --archive；先运行 package-backend.sh"
require_command ssh
ssh "${ssh_options[@]}" "$host" 'bash -c '\''set -euo pipefail; umask 077; directory=$(mktemp -d); trap "rm -rf -- \"$directory\"" EXIT; cat > "$directory/package.tar.gz"; mkdir "$directory/package"; tar -xzf "$directory/package.tar.gz" -C "$directory/package"; if [ "$(id -u)" -eq 0 ]; then bash "$directory/package/install.sh"; else sudo -n bash "$directory/package/install.sh"; fi'\''' < "$archive_path"
info "远程部署完成：$host"
