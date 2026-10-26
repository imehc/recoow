#!/usr/bin/env bash

die() { printf '错误：%s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*" >&2; }
require_command() { command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"; }

linux_arch() {
    [ "$(uname -s)" = Linux ] || die "运行环境必须是 Linux"
    case "$(uname -m)" in
        x86_64|amd64) printf 'amd64\n' ;;
        aarch64|arm64) printf 'arm64\n' ;;
        *) die "不支持的架构：$(uname -m)" ;;
    esac
}

load_config() {
    local config_file=$1 config_line config_key config_value
    [ -f "$config_file" ] || die "配置文件不存在：$config_file"
    while IFS= read -r config_line || [ -n "$config_line" ]; do
        config_line=${config_line%$'\r'}
        case "$config_line" in ''|'#'*) continue ;; esac
        [[ "$config_line" == *=* ]] || die "配置必须使用 KEY=value：$config_file"
        config_key=${config_line%%=*}
        config_value=${config_line#*=}
        case "$config_key" in
            RECOOW_LISTEN|RECOOW_DATA|RECOOW_SCHEMA|RECOOW_BOOTSTRAP_TOKEN|RECOOW_MAX_ACCOUNTS|RECOOW_PAIRING_TTL|RECOOW_CORS_ORIGINS|RECOOW_TLS_CERT|RECOOW_TLS_KEY) ;;
            *) die "不支持的配置项：$config_key" ;;
        esac
        if [ -z "${!config_key+x}" ]; then
            export "$config_key=$config_value"
        fi
    done < "$config_file"
}

verify_bundle() {
    local bundle_directory=$1
    [ -f "$bundle_directory/SHA256SUMS" ] || die "缺少 SHA256SUMS；请使用新的打包脚本"
    if [ "${2:-all}" = runtime ]; then
        (cd "$bundle_directory" && awk '$2 !~ /^\.\/data\// && $2 != "./recoow.env" { print }' SHA256SUMS | sha256sum -c - >/dev/null) || die "运行包校验失败"
    else
        (cd "$bundle_directory" && sha256sum -c SHA256SUMS >/dev/null) || die "运行包校验失败"
    fi
}

write_manifest() {
    (
        cd "$1" || exit 1
        manifest_output=$(mktemp)
        trap 'rm -f -- "$manifest_output"' EXIT
        find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort | while IFS= read -r manifest_file; do
            if command -v sha256sum >/dev/null 2>&1; then
                sha256sum "$manifest_file"
            else
                shasum -a 256 "$manifest_file"
            fi
        done > "$manifest_output"
        mv -- "$manifest_output" SHA256SUMS
    )
}

copy_tree() {
    local source_directory=$1 target_directory=$2
    mkdir -p "$target_directory"
    COPYFILE_DISABLE=1 tar --exclude='._*' --exclude='.DS_Store' -C "$source_directory" -cf - . |
        tar -C "$target_directory" -xf -
}

ready_url() {
    local listen_address=${RECOOW_LISTEN:-0.0.0.0:8080} url_scheme=http
    case "$listen_address" in
        0.0.0.0:*|:*) listen_address="127.0.0.1:${listen_address##*:}" ;;
        '[::]:'*) listen_address="[::1]:${listen_address##*:}" ;;
    esac
    if [ -n "${RECOOW_TLS_CERT:-}" ] && [ -n "${RECOOW_TLS_KEY:-}" ]; then url_scheme=https; fi
    printf '%s\n' "${RECOOW_HEALTH_URL:-$url_scheme://$listen_address/api/schema}"
}

wait_ready() {
    local health_url=$1 attempt=0 status_code
    while [ "$attempt" -lt 30 ]; do
        attempt=$((attempt + 1))
        status_code=$(curl --noproxy '*' -sS -o /dev/null -w '%{http_code}' --connect-timeout 1 --max-time 2 "$health_url" 2>/dev/null) || true
        if [ "$status_code" = 401 ]; then return 0; fi
        sleep 1
    done
    die "服务未就绪：$health_url；请检查服务日志、端口及证书"
}
