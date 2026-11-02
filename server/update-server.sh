#!/bin/bash
# Обновить код сервера Libre (после новой версии архива libre-server.zip).
#   cd /root && unzip -o libre-server.zip -d libre-server && bash libre-server/update-server.sh
set -euo pipefail
cd "$(dirname "$0")"
cp server.py /opt/libre/server.py
cp publish-update.sh /root/publish-update.sh
cp setup-music-channel.sh /root/setup-music-channel.sh
mkdir -p /var/lib/libre/media/app /var/lib/libre/media/w /var/lib/libre/backup
chown libre:libre /var/lib/libre/backup && chmod 700 /var/lib/libre/backup
chown libre:libre /var/lib/libre/media/app /var/lib/libre/media/w
chmod 755 /var/lib/libre/media/app /var/lib/libre/media/w
systemctl restart libre
# мост с MAX — если установлен (install-bridge.sh), обновляется вместе с сервером
if [ -f /etc/systemd/system/libre-bridge.service ]; then
  cp bridge.py /opt/libre/bridge.py
  systemctl restart libre-bridge
fi
sleep 2
DOMAIN=$(grep '^PUBLIC_URL=' /etc/libre.env | cut -d= -f2)
curl -fsS "$DOMAIN/health" && echo && echo "Сервер Libre обновлён"
