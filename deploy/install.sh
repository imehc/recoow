#!/usr/bin/env bash
set -euo pipefail

bundle_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$bundle_directory/common.sh"
prefix=/opt/recoow
data_directory=/var/lib/recoow
config_directory=/etc/recoow
while [ "$#" -gt 0 ]; do
    case "$1" in
        --help) printf '用法：sudo bash install.sh\n安装至 /opt/recoow，数据在 /var/lib/recoow，配置在 /etc/recoow/recoow.env。\n迁移包中的 data/ 仅恢复到空数据目录；已有数据绝不覆盖。\n'; exit 0 ;;
        *) die "未知参数：$1" ;;
    esac
done
[ "$(id -u)" -eq 0 ] || die "请使用 root 或 sudo 执行"
architecture=$(linux_arch)
for required in systemctl useradd getent sha256sum curl tar flock; do require_command "$required"; done
[ -d /run/systemd/system ] || die "当前 Linux 未运行 systemd；请使用 bash run.sh"
exec 9>/run/lock/recoow-maintenance.lock
flock -n 9 || die "已有部署或导出正在运行"
verify_bundle "$bundle_directory"
[ -x "$bundle_directory/recoow-api-linux-$architecture" ] || die "运行包不支持 $architecture"
if [ -d "$bundle_directory/data" ] && [ -d "$data_directory" ] && [ -n "$(find "$data_directory" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    die "迁移包不能覆盖已有数据：$data_directory；请在新服务器或空目录恢复"
fi
if [ -f "$config_directory/recoow.env" ]; then
    load_config "$config_directory/recoow.env"
    [ "${RECOOW_DATA:-$data_directory}" = "$data_directory" ] || die "已有配置使用自定义数据目录；请使用 run.sh 或手动部署，避免覆盖"
    [ "${RECOOW_SCHEMA:-$prefix/schema}" = "$prefix/schema" ] || die "已有配置使用自定义字段目录；请手动部署"
fi
if ! id recoow >/dev/null 2>&1; then useradd --system --user-group --no-create-home --shell /usr/sbin/nologin recoow; fi
getent group recoow >/dev/null || die "缺少 recoow 用户组"
install -d -m 0755 "$prefix"
install -d -m 0750 -o recoow -g recoow "$data_directory"
install -d -m 0750 -g recoow "$config_directory"
install -d -m 0755 /etc/systemd/system
staging_directory=$(mktemp -d "$prefix/.install.XXXXXX")
trap 'rm -rf -- "$staging_directory"' EXIT
for package_file in common.sh run.sh install.sh export.sh recoow.env.example deploy.env.example Dockerfile docker-compose.yml .dockerignore README.md; do
    install -m 0644 "$bundle_directory/$package_file" "$staging_directory/$package_file"
done
for binary_path in "$bundle_directory"/recoow-api-linux-*; do install -m 0755 "$binary_path" "$staging_directory/$(basename -- "$binary_path")"; done
chmod 0755 "$staging_directory/"*.sh
copy_tree "$bundle_directory/schema" "$staging_directory/schema"
chmod -R a+rX "$staging_directory/schema"
write_manifest "$staging_directory"
systemctl stop recoow.service 2>/dev/null || { [ ! -f /etc/systemd/system/recoow.service ] || die "无法停止旧服务"; }
for installed_path in "$staging_directory"/* "$staging_directory"/.dockerignore; do
    if [ -d "$installed_path" ]; then
        rm -rf -- "${prefix:?}/$(basename -- "$installed_path")"
    fi
    mv -f -- "$installed_path" "$prefix/$(basename -- "$installed_path")"
done
if [ -d "$bundle_directory/data" ]; then
    copy_tree "$bundle_directory/data" "$data_directory"
    chown -R recoow:recoow "$data_directory"
    chmod 0750 "$data_directory"
fi
if [ ! -f "$config_directory/recoow.env" ]; then
    source_config="$bundle_directory/recoow.env.example"
    if [ -f "$bundle_directory/recoow.env" ]; then source_config="$bundle_directory/recoow.env"; fi
    awk '
        /^RECOOW_DATA=/ { print "RECOOW_DATA=/var/lib/recoow"; next }
        /^RECOOW_SCHEMA=/ { print "RECOOW_SCHEMA=/opt/recoow/schema"; next }
        { print }
    ' "$source_config" > "$config_directory/recoow.env"
    chmod 0640 "$config_directory/recoow.env"
fi
load_config "$config_directory/recoow.env"
chgrp recoow "$config_directory/recoow.env"
chmod 0640 "$config_directory/recoow.env"
cat > /etc/systemd/system/recoow.service <<'UNIT'
[Unit]
Description=recoow cloud sync backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=recoow
Group=recoow
WorkingDirectory=/opt/recoow
Environment=RECOOW_ENV_FILE=/etc/recoow/recoow.env
ExecStart=/opt/recoow/run.sh
Restart=on-failure
RestartSec=3
TimeoutStopSec=30
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/recoow
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable recoow.service >/dev/null
systemctl restart recoow.service
wait_ready "$(ready_url)"
info "安装完成，服务已就绪。查看日志：journalctl -u recoow -f"
