#!/bin/bash
# Канал для треков из открытых плейлистов (Libre 1.2.1).
#   bash libre-server/setup-music-channel.sh
#
# Зачем: трек из личного чата может скачать только его владелец — так устроен Telegram. Поэтому бот
# Libre выкладывает такие треки в ваш публичный канал, и оттуда их включает любой слушатель.
# Перед запуском:
#   1. Создайте в Telegram публичный канал (например @libre_tracks).
#   2. Добавьте бота Libre в администраторы канала с правом «Публикация сообщений».
set -euo pipefail

BOT_TOKEN=$(grep '^BOT_TOKEN=' /etc/libre.env | cut -d= -f2-)
read -rp "Имя канала (например @libre_tracks): " CHANNEL
CHANNEL="@${CHANNEL#@}"

# бот действительно админ канала?
ME=$(curl -fsS "https://api.telegram.org/bot$BOT_TOKEN/getMe" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["id"])')
STATUS=$(curl -sS "https://api.telegram.org/bot$BOT_TOKEN/getChatMember?chat_id=$CHANNEL&user_id=$ME" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("result",{}).get("status","") if d.get("ok") else "error: "+d.get("description",""))')
if [ "$STATUS" != "administrator" ]; then
  echo "Бот не администратор канала $CHANNEL ($STATUS). Добавьте его в админы с правом публикации и запустите снова."
  exit 1
fi

# записываем в настройки сервера (старую строку заменяем)
sed -i '/^MUSIC_CHANNEL=/d' /etc/libre.env
echo "MUSIC_CHANNEL=$CHANNEL" >> /etc/libre.env
systemctl restart libre
sleep 2
echo "Готово: треки из личных чатов будут появляться в $CHANNEL, и их сможет включить любой."
