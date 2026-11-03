#!/bin/bash

set -euo pipefail

cd "$(dirname "$0")"

if [ "$(id -u)" != "0" ]; then
  echo "Запустите от root: sudo bash install.sh"; exit 1
fi

read -rp "Домен сервера (например api.libre-app.org): " DOMAIN
DOMAIN="${DOMAIN#https://}"; DOMAIN="${DOMAIN%/}"
read -rsp "Токен бота Libre от @BotFather (не отображается): " BOT_TOKEN; echo
if [ -z "$DOMAIN" ] || [ -z "$BOT_TOKEN" ]; then
  echo "Нужны и домен, и токен"; exit 1
fi

echo "== пакеты"
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -yq python3-venv nginx certbot python3-certbot-nginx sqlite3

echo "== swap 2 ГБ (если его нет)"
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== пользователь и папки"
id libre >/dev/null 2>&1 || useradd --system --home /var/lib/libre --shell /usr/sbin/nologin libre
mkdir -p /opt/libre /var/lib/libre/db /var/lib/libre/media/b /var/lib/libre/media/w /var/lib/libre/media/app /var/backups/libre
cp server.py /opt/libre/server.py
cp publish-update.sh /root/publish-update.sh
chown -R libre:libre /var/lib/libre
chmod 755 /var/lib/libre /var/lib/libre/media /var/lib/libre/media/b /var/lib/libre/media/w /var/lib/libre/media/app
chmod 700 /var/lib/libre/db

echo "== python"
python3 -m venv /opt/libre/venv
/opt/libre/venv/bin/pip install -q --upgrade pip
/opt/libre/venv/bin/pip install -q "aiohttp>=3.9,<4"

echo "== настройки"
if [ -f /etc/libre.env ] && grep -q '^VIEW_SALT=' /etc/libre.env; then
  SALT=$(grep '^VIEW_SALT=' /etc/libre.env | cut -d= -f2)
else
  SALT=$(openssl rand -hex 16)
fi
umask 077
cat > /etc/libre.env <<EOF
BOT_TOKEN=$BOT_TOKEN
PUBLIC_URL=https://$DOMAIN
LIBRE_DATA=/var/lib/libre
PORT=8090
VIEW_SALT=$SALT
EOF
umask 022

cat > /etc/systemd/system/libre.service <<'EOF'
[Unit]
Description=Libre Cloud
After=network.target

[Service]
User=libre
Group=libre
EnvironmentFile=/etc/libre.env
ExecStart=/opt/libre/venv/bin/python /opt/libre/server.py
Restart=always
RestartSec=3
UMask=0022
MemoryMax=700M
NoNewPrivileges=true
ProtectSystem=full
ReadWritePaths=/var/lib/libre

[Install]
WantedBy=multi-user.target
EOF

echo "== nginx"
cat > /etc/nginx/sites-available/libre <<EOF
server {
    listen 80;
    server_name $DOMAIN;
    access_log off;
    client_max_body_size 17m;

    location /media/ {
        alias /var/lib/libre/media/;
        expires 30d;
        add_header Cache-Control "public, immutable";
    }
    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_read_timeout 60s;
    }
}
EOF
ln -sf /etc/nginx/sites-available/libre /etc/nginx/sites-enabled/libre
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo "== сервис"
systemctl daemon-reload
systemctl enable --now libre
systemctl restart libre

echo "== HTTPS-сертификат"
certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email --redirect

echo "== ежедневный бэкап базы (хранятся 7 дней)"
cat > /etc/cron.daily/libre-backup <<'EOF'
sqlite3 /var/lib/libre/db/libre.db ".backup '/var/backups/libre/libre-$(date +%F).db'"
find /var/backups/libre -name 'libre-*.db' -mtime +7 -delete
EOF
chmod 755 /etc/cron.daily/libre-backup

sleep 2
echo "== проверка"
curl -fsS "https://$DOMAIN/health" && echo && echo "ГОТОВО: Libre Cloud работает на https://$DOMAIN"
