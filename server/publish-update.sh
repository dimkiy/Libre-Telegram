#!/bin/bash

set -euo pipefail

APK="${1:-}"
VERSION="${2:-}"
if [ -z "$APK" ] || [ -z "$VERSION" ] || [ ! -f "$APK" ]; then
  echo "Использование: bash publish-update.sh /root/Libre-1.0.7.apk 1.0.7"; exit 1
fi
read -rp "Что нового (пункты через ;): " NOTES

DIR=/var/lib/libre/media/app
mkdir -p "$DIR"
NAME="Libre-$VERSION.apk"
cp "$APK" "$DIR/$NAME.part" && mv "$DIR/$NAME.part" "$DIR/$NAME"
chmod 644 "$DIR/$NAME"

DOMAIN=$(grep '^PUBLIC_URL=' /etc/libre.env | cut -d= -f2)
SHA=$(sha256sum "$DIR/$NAME" | cut -d' ' -f1)
SIZE=$(stat -c%s "$DIR/$NAME")

python3 - "$DIR" "$NAME" "$VERSION" "$SHA" "$SIZE" "$DOMAIN" "$NOTES" <<'PY'
import json, os, sys, time
d, name, version, sha, size, domain, notes = sys.argv[1:8]
info = {
    "version": version,
    "url": f"{domain}/media/app/{name}",
    "sha256": sha,
    "size": int(size),
    "notes": [n.strip() for n in notes.split(";") if n.strip()],
    "published": int(time.time()),
}
tmp = os.path.join(d, "latest.json.part")
with open(tmp, "w", encoding="utf-8") as f:
    json.dump(info, f, ensure_ascii=False)
os.replace(tmp, os.path.join(d, "latest.json"))
PY
chmod 644 "$DIR/latest.json"
chown -R libre:libre "$DIR"

ls -1t "$DIR"/Libre-*.apk 2>/dev/null | tail -n +3 | xargs -r rm -f

echo "Готово: Libre $VERSION выложен ($((SIZE / 1024 / 1024)) МБ)"
curl -fsS "$DOMAIN/v1/update"; echo
