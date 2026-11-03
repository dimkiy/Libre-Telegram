#!/bin/bash

set -euo pipefail
cd "$(dirname "$0")"
[ "$(id -u)" = "0" ] || { echo "Запустите от root"; exit 1; }
[ -f /etc/libre.env ] || { echo "Сначала установите сервер Libre (install.sh)"; exit 1; }

read -rsp "Токен бота MAX (не отображается): " MAX_TOKEN; echo
read -rp "Ссылка на бота в MAX (например https://max.ru/libre_bot): " MAX_BOT_LINK
[ -n "$MAX_TOKEN" ] || { echo "Нужен токен"; exit 1; }

umask 077
cat > /etc/libre-bridge.env <<ENV
MAX_TOKEN=$MAX_TOKEN
MAX_BOT_LINK=$MAX_BOT_LINK
ENV
umask 022

cp bridge.py /opt/libre/bridge.py
cat > /etc/systemd/system/libre-bridge.service <<'UNIT'
[Unit]
Description=Libre <-> MAX bridge
After=network.target

[Service]
User=libre
Group=libre
EnvironmentFile=/etc/libre.env
EnvironmentFile=/etc/libre-bridge.env
ExecStart=/opt/libre/venv/bin/python /opt/libre/bridge.py
Restart=always
RestartSec=5
MemoryMax=300M
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=/var/lib/libre

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now libre-bridge
systemctl restart libre-bridge
sleep 3
systemctl --no-pager --lines=8 status libre-bridge || true
echo "Мост Libre ↔ MAX запущен. Проверьте: напишите боту Libre в Telegram /max"
