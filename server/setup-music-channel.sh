#!/bin/bash

set -euo pipefail

BOT_TOKEN=$(grep '^BOT_TOKEN=' /etc/libre.env | cut -d= -f2-)
read -rp "Имя канала (например @libre_tracks): " CHANNEL
CHANNEL="@${CHANNEL#@}"

ME=$(curl -fsS "https://api.telegram.org/bot$BOT_TOKEN/getMe" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["id"])')
STATUS=$(curl -sS "https://api.telegram.org/bot$BOT_TOKEN/getChatMember?chat_id=$CHANNEL&user_id=$ME" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("result",{}).get("status","") if d.get("ok") else "error: "+d.get("description",""))')
if [ "$STATUS" != "administrator" ]; then
  echo "Бот не администратор канала $CHANNEL ($STATUS). Добавьте его в админы с правом публикации и запустите снова."
  exit 1
fi

sed -i '/^MUSIC_CHANNEL=/d' /etc/libre.env
echo "MUSIC_CHANNEL=$CHANNEL" >> /etc/libre.env
systemctl restart libre
sleep 2
echo "Готово: треки из личных чатов будут появляться в $CHANNEL, и их сможет включить любой."
