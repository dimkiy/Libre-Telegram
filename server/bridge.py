"""
Libre ↔ MAX — a bridge between the Libre Telegram bot and a bot in MAX.

A person in Telegram writes /max to the Libre bot and gets an invite link to the MAX bot. Whoever
opens it in MAX (a friend, or the same person on their own MAX) is paired with them. From then on:
  * what the Telegram person writes to the Libre bot arrives in MAX from the MAX bot;
  * what the MAX person writes to the MAX bot arrives in Telegram from the Libre bot — and the
    Telegram person answers simply by replying to it.
Text and photos go both ways. One Telegram person can have several pairs: /chats picks who gets the
next messages (or just reply to their message).

What is kept (bridge.db): pairs (Telegram id, MAX id, display names), invite codes for a day, and
"this Telegram message came from that pair" (ids only, 30 days) so replies find their way. Message
texts and photos are never stored — they only pass through.

Config: /etc/libre.env (BOT_TOKEN — the Libre bot) and /etc/libre-bridge.env (MAX_TOKEN, MAX_BOT_LINK).
"""
import asyncio
import io
import json
import logging
import os
import re
import secrets
import sqlite3
import time

import aiohttp

TG_TOKEN = os.environ["BOT_TOKEN"]
MAX_TOKEN = os.environ["MAX_TOKEN"]
MAX_API = os.environ.get("MAX_API", "https://platform-api.max.ru").rstrip("/")
MAX_BOT_LINK = os.environ.get("MAX_BOT_LINK", "").rstrip("/")  # e.g. https://max.ru/libre_bot
DATA = os.environ.get("LIBRE_DATA", "/var/lib/libre")
TG_API = os.environ.get("TG_API", "https://api.telegram.org").rstrip("/")  # overridable for tests
TG = f"{TG_API}/bot{TG_TOKEN}"
TG_FILE = f"{TG_API}/file/bot{TG_TOKEN}"
CODE_TTL = 86400
MAP_TTL = 30 * 86400
MAX_TEXT = 4000

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("bridge")

os.makedirs(os.path.join(DATA, "db"), exist_ok=True)
db = sqlite3.connect(os.path.join(DATA, "db", "bridge.db"), isolation_level=None, check_same_thread=False)
db.execute("PRAGMA journal_mode=WAL")
db.executescript("""
CREATE TABLE IF NOT EXISTS pairs (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    tg_id    INTEGER NOT NULL,
    max_id   INTEGER NOT NULL,
    tg_name  TEXT,
    max_name TEXT,
    created  INTEGER NOT NULL,
    last     INTEGER NOT NULL,
    UNIQUE (tg_id, max_id)
);
CREATE TABLE IF NOT EXISTS codes (
    code    TEXT PRIMARY KEY,
    tg_id   INTEGER NOT NULL,
    tg_name TEXT,
    created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS tg_active (tg_id INTEGER PRIMARY KEY, pair INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS max_active (max_id INTEGER PRIMARY KEY, pair INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS tg_map (
    chat    INTEGER NOT NULL,
    msg     INTEGER NOT NULL,
    pair    INTEGER NOT NULL,
    created INTEGER NOT NULL,
    PRIMARY KEY (chat, msg)
);
CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT);
""")


def now():
    return int(time.time())


def kv_get(k, default=None):
    row = db.execute("SELECT v FROM kv WHERE k=?", (k,)).fetchone()
    return row[0] if row else default


def kv_set(k, v):
    db.execute("INSERT INTO kv(k, v) VALUES(?, ?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (k, str(v)))


def full_name(first, last):
    return " ".join(x for x in (first or "", last or "") if x).strip() or "Без имени"


def pair_row(pid):
    return db.execute("SELECT id, tg_id, max_id, tg_name, max_name FROM pairs WHERE id=?", (pid,)).fetchone()


def touch(pid):
    db.execute("UPDATE pairs SET last=? WHERE id=?", (now(), pid))


# ---------------------------------------------------------------- API calls

class Api:
    def __init__(self, session):
        self.s = session

    async def tg(self, method, data=None, files=None, timeout=70):
        try:
            if files:
                form = aiohttp.FormData()
                for k, v in (data or {}).items():
                    form.add_field(k, str(v) if not isinstance(v, (dict, list)) else json.dumps(v, ensure_ascii=False))
                for k, (name, content) in files.items():
                    form.add_field(k, content, filename=name)
                async with self.s.post(f"{TG}/{method}", data=form, timeout=aiohttp.ClientTimeout(total=timeout)) as r:
                    return await r.json()
            async with self.s.post(f"{TG}/{method}", json=data or {}, timeout=aiohttp.ClientTimeout(total=timeout)) as r:
                return await r.json()
        except Exception as e:
            log.warning("tg %s: %s", method, e)
            return {"ok": False, "description": str(e)}

    async def mx(self, method, path, params=None, body=None, timeout=60):
        try:
            async with self.s.request(method, MAX_API + path, params=params, json=body,
                                      headers={"Authorization": MAX_TOKEN},
                                      timeout=aiohttp.ClientTimeout(total=timeout)) as r:
                text = await r.text()
                try:
                    data = json.loads(text) if text else {}
                except ValueError:
                    data = {"raw": text}
                if r.status >= 400:
                    log.warning("max %s %s -> %s %s", method, path, r.status, text[:300])
                    data["_error"] = r.status
                return data
        except Exception as e:
            log.warning("max %s %s: %s", method, path, e)
            return {"_error": -1}

    async def download(self, url, limit=20 * 1024 * 1024):
        try:
            async with self.s.get(url, timeout=aiohttp.ClientTimeout(total=90)) as r:
                if r.status != 200:
                    return None
                data = await r.content.read(limit + 1)
                return data if len(data) <= limit else None
        except Exception as e:
            log.warning("download: %s", e)
            return None

    # -- MAX: send text / photo to a user
    async def max_send(self, max_id, text, photo=None):
        body = {"text": text[:MAX_TEXT] if text else None}
        if photo:
            token = await self.max_upload_photo(photo)
            if token:
                body["attachments"] = [{"type": "image", "payload": {"photos": token}}]
            elif not text:
                body["text"] = "(фото не удалось передать)"
        res = await self.mx("POST", "/messages", params={"user_id": max_id}, body=body)
        return "_error" not in res

    async def max_upload_photo(self, data):
        ep = await self.mx("POST", "/uploads", params={"type": "image"})
        url = ep.get("url")
        if not url:
            return None
        try:
            form = aiohttp.FormData()
            form.add_field("data", data, filename="photo.jpg", content_type="image/jpeg")
            async with self.s.post(url, data=form, headers={"Authorization": MAX_TOKEN},
                                   timeout=aiohttp.ClientTimeout(total=120)) as r:
                res = await r.json(content_type=None)
            return res.get("photos") or None
        except Exception as e:
            log.warning("max upload: %s", e)
            return None


# ---------------------------------------------------------------- pairing

def new_code(tg_id, tg_name):
    db.execute("DELETE FROM codes WHERE created < ?", (now() - CODE_TTL,))
    code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
    db.execute("INSERT INTO codes(code, tg_id, tg_name, created) VALUES(?, ?, ?, ?)", (code, tg_id, tg_name, now()))
    return code


async def link(api, code, max_id, max_name):
    code = (code or "").strip().upper()
    row = db.execute("SELECT tg_id, tg_name, created FROM codes WHERE code=?", (code,)).fetchone()
    if not row or now() - row[2] > CODE_TTL:
        await api.max_send(max_id, "Код не найден или устарел. Попросите новую ссылку: в Telegram боту Libre команда /max.")
        return
    tg_id, tg_name = row[0], row[1]
    db.execute("DELETE FROM codes WHERE code=?", (code,))
    db.execute("""INSERT INTO pairs(tg_id, max_id, tg_name, max_name, created, last) VALUES(?, ?, ?, ?, ?, ?)
                  ON CONFLICT(tg_id, max_id) DO UPDATE SET max_name=excluded.max_name, last=excluded.last""",
               (tg_id, max_id, tg_name, max_name, now(), now()))
    pid = db.execute("SELECT id FROM pairs WHERE tg_id=? AND max_id=?", (tg_id, max_id)).fetchone()[0]
    db.execute("INSERT OR REPLACE INTO tg_active(tg_id, pair) VALUES(?, ?)", (tg_id, pid))
    db.execute("INSERT OR REPLACE INTO max_active(max_id, pair) VALUES(?, ?)", (max_id, pid))
    await api.tg("sendMessage", {"chat_id": tg_id, "text":
        f"Связь с {max_name} в MAX установлена.\n\nПишите сюда — сообщения уйдут в MAX. Ответы придут в этот чат; "
        f"чтобы ответить на конкретное, используйте «Ответить». Все связи: /chats"})
    await api.max_send(max_id, f"Вы на связи с {tg_name} из Telegram. Пишите сюда — сообщения уйдут в Telegram.\n"
                               f"Отключиться: /stop")


async def unlink(api, pid, by_tg):
    p = pair_row(pid)
    if not p:
        return
    db.execute("DELETE FROM pairs WHERE id=?", (pid,))
    db.execute("DELETE FROM tg_active WHERE pair=?", (pid,))
    db.execute("DELETE FROM max_active WHERE pair=?", (pid,))
    db.execute("DELETE FROM tg_map WHERE pair=?", (pid,))
    if by_tg:
        await api.max_send(p[2], f"{p[3]} отключил связь с Telegram.")
    else:
        await api.tg("sendMessage", {"chat_id": p[1], "text": f"{p[4]} отключил связь с MAX."})


# ---------------------------------------------------------------- Telegram side

HELP = ("Мост Libre ↔ MAX: переписка с человеком из MAX прямо здесь.\n\n"
        "/max — ссылка-приглашение для друга в MAX (или для себя — чтобы получать сообщения в своём MAX)\n"
        "/chats — с кем вы на связи и кому уходят сообщения\n\n"
        "Сообщения и фото не хранятся на сервере — только пересылаются.")


async def tg_chats(api, tg_id):
    rows = db.execute("SELECT id, max_name FROM pairs WHERE tg_id=? ORDER BY last DESC", (tg_id,)).fetchall()
    if not rows:
        await api.tg("sendMessage", {"chat_id": tg_id, "text": "Пока ни с кем нет связи. Отправьте другу ссылку из /max."})
        return
    active = db.execute("SELECT pair FROM tg_active WHERE tg_id=?", (tg_id,)).fetchone()
    active = active[0] if active else None
    kb = [[{"text": ("● " if r[0] == active else "") + (r[1] or "MAX"), "callback_data": f"act:{r[0]}"},
           {"text": "Отключить", "callback_data": f"del:{r[0]}"}] for r in rows]
    await api.tg("sendMessage", {"chat_id": tg_id, "text": "Связи с MAX. Сообщения уходят тому, кто отмечен ●:",
                                 "reply_markup": {"inline_keyboard": kb}})


async def tg_message(api, m):
    chat = m.get("chat", {})
    if chat.get("type") != "private":
        return
    tg_id = chat["id"]
    frm = m.get("from", {})
    tg_name = full_name(frm.get("first_name"), frm.get("last_name"))
    text = m.get("text") or m.get("caption") or ""
    cmd = text.split()[0].lower() if text.startswith("/") else ""
    if cmd.startswith("/start"):
        if text.strip().endswith("max"):
            cmd = "/max"
        else:
            await api.tg("sendMessage", {"chat_id": tg_id, "text": HELP})
            return
    if cmd == "/max":
        code = new_code(tg_id, tg_name)
        link_text = f"{MAX_BOT_LINK}?start={code}" if MAX_BOT_LINK else None
        await api.tg("sendMessage", {"chat_id": tg_id, "text":
            ("Отправьте эту ссылку другу — пусть откроет её в MAX:\n" + link_text + "\n\n" if link_text else "") +
            f"Или пусть напишет боту Libre в MAX этот код: {code}\n\nСсылка и код действуют сутки. "
            "Можно открыть ссылку и самому — тогда сообщения отсюда будут приходить в ваш MAX."})
        return
    if cmd in ("/chats", "/list"):
        await tg_chats(api, tg_id)
        return
    if cmd == "/help":
        await api.tg("sendMessage", {"chat_id": tg_id, "text": HELP})
        return
    # which pair: the message they reply to, else the chosen one, else the only one
    pid = None
    reply = m.get("reply_to_message")
    if reply:
        row = db.execute("SELECT pair FROM tg_map WHERE chat=? AND msg=?", (tg_id, reply.get("message_id"))).fetchone()
        pid = row[0] if row else None
    if pid is None:
        row = db.execute("SELECT pair FROM tg_active WHERE tg_id=?", (tg_id,)).fetchone()
        pid = row[0] if row else None
    p = pair_row(pid) if pid else None
    if not p or p[1] != tg_id:
        rows = db.execute("SELECT id FROM pairs WHERE tg_id=?", (tg_id,)).fetchall()
        if len(rows) == 1:
            p = pair_row(rows[0][0])
        else:
            await api.tg("sendMessage", {"chat_id": tg_id, "text":
                "Не знаю, кому отправить. Выберите в /chats или ответьте на сообщение нужного человека." if rows else HELP})
            return
    photo = None
    if m.get("photo"):
        f = await api.tg("getFile", {"file_id": m["photo"][-1]["file_id"]})
        path = (f.get("result") or {}).get("file_path")
        photo = await api.download(f"{TG_FILE}/{path}") if path else None
    if not text and not photo:
        await api.tg("sendMessage", {"chat_id": tg_id, "text": "Пока через мост проходят только текст и фото."})
        return
    ok = await api.max_send(p[2], f"{tg_name}: {text}" if text else f"{tg_name}:", photo)
    if ok:
        touch(p[0])
        db.execute("INSERT OR REPLACE INTO tg_active(tg_id, pair) VALUES(?, ?)", (tg_id, p[0]))
    else:
        await api.tg("sendMessage", {"chat_id": tg_id, "reply_to_message_id": m.get("message_id"),
                                     "text": f"Не удалось доставить в MAX — возможно, {p[4]} остановил бота."})


async def tg_callback(api, q):
    tg_id = q["from"]["id"]
    data = q.get("data", "")
    pid = int(data.split(":")[1]) if ":" in data and data.split(":")[1].isdigit() else 0
    p = pair_row(pid)
    if not p or p[1] != tg_id:
        await api.tg("answerCallbackQuery", {"callback_query_id": q["id"], "text": "Этой связи уже нет"})
        return
    if data.startswith("act:"):
        db.execute("INSERT OR REPLACE INTO tg_active(tg_id, pair) VALUES(?, ?)", (tg_id, pid))
        await api.tg("answerCallbackQuery", {"callback_query_id": q["id"], "text": f"Сообщения уходят: {p[4]}"})
    elif data.startswith("del:"):
        await unlink(api, pid, True)
        await api.tg("answerCallbackQuery", {"callback_query_id": q["id"], "text": "Связь отключена"})
    await tg_chats(api, tg_id)


async def tg_loop(api):
    info = await api.tg("getWebhookInfo")
    if (info.get("result") or {}).get("url"):
        log.error("The Libre bot has a webhook set — the bridge needs getUpdates. Remove it with deleteWebhook.")
        return
    offset = int(kv_get("tg_offset", "0"))
    while True:
        res = await api.tg("getUpdates", {"offset": offset, "timeout": 50, "allowed_updates": ["message", "callback_query"]})
        if not res.get("ok"):
            await asyncio.sleep(5)
            continue
        for u in res.get("result", []):
            offset = u["update_id"] + 1
            kv_set("tg_offset", offset)
            try:
                if "message" in u:
                    await tg_message(api, u["message"])
                elif "callback_query" in u:
                    await tg_callback(api, u["callback_query"])
            except Exception as e:
                log.exception("tg update: %s", e)


# ---------------------------------------------------------------- MAX side

CODE_RE = re.compile(r"^[A-Z0-9]{8}$")


async def max_message(api, msg):
    sender = msg.get("sender") or {}
    recipient = msg.get("recipient") or {}
    if recipient.get("chat_type") not in (None, "dialog") or sender.get("is_bot"):
        return
    max_id = sender.get("user_id")
    if not max_id:
        return
    max_name = full_name(sender.get("first_name"), sender.get("last_name"))
    body = msg.get("body") or {}
    text = (body.get("text") or "").strip()
    if CODE_RE.match(text.upper()):
        await link(api, text, max_id, max_name)
        return
    if text.lower() in ("/stop", "/стоп"):
        for (pid,) in db.execute("SELECT id FROM pairs WHERE max_id=?", (max_id,)).fetchall():
            await unlink(api, pid, False)
        await api.max_send(max_id, "Связь с Telegram отключена.")
        return
    if text.lower() in ("/start", "/help"):
        await api.max_send(max_id, "Это мост Libre: переписка с человеком из Telegram. Чтобы подключиться, откройте "
                                   "ссылку-приглашение или пришлите сюда код из 8 символов. Отключиться: /stop")
        return
    row = db.execute("SELECT pair FROM max_active WHERE max_id=?", (max_id,)).fetchone()
    p = pair_row(row[0]) if row else None
    if not p:
        row = db.execute("SELECT id FROM pairs WHERE max_id=? ORDER BY last DESC LIMIT 1", (max_id,)).fetchone()
        p = pair_row(row[0]) if row else None
    if not p:
        await api.max_send(max_id, "Вы ещё ни с кем не связаны. Откройте ссылку-приглашение из Telegram или пришлите код.")
        return
    if max_name != p[4]:
        db.execute("UPDATE pairs SET max_name=? WHERE id=?", (max_name, p[0]))
    head = f"{max_name} · MAX"
    sent = None
    photos = [a for a in body.get("attachments") or [] if a.get("type") == "image"]
    for i, a in enumerate(photos[:10]):
        url = (a.get("payload") or {}).get("url")
        data = await api.download(url) if url else None
        if data:
            cap = (f"{head}:\n{text}" if text else head) if i == 0 else None
            res = await api.tg("sendPhoto", {"chat_id": p[1], **({"caption": cap[:1024]} if cap else {})},
                               files={"photo": ("photo.jpg", data)})
            if res.get("ok") and sent is None:
                sent = res["result"]["message_id"]
    if sent is None:
        if not text:
            text = "(вложение, которое мост пока не передаёт)"
        res = await api.tg("sendMessage", {"chat_id": p[1], "text": f"{head}:\n{text}"[:4096]})
        sent = (res.get("result") or {}).get("message_id")
    if sent:
        db.execute("INSERT OR REPLACE INTO tg_map(chat, msg, pair, created) VALUES(?, ?, ?, ?)", (p[1], sent, p[0], now()))
        touch(p[0])
    else:
        await api.max_send(max_id, f"Не удалось доставить в Telegram — возможно, {p[3]} остановил бота Libre.")


async def max_loop(api):
    me = await api.mx("GET", "/me")
    if "_error" in me:
        log.error("MAX token is not accepted (%s) — check MAX_TOKEN", me.get("_error"))
    else:
        log.info("MAX bot: %s", me.get("username") or me.get("first_name"))
    marker = kv_get("max_marker")
    while True:
        params = {"timeout": 30, "types": "message_created,bot_started"}
        if marker:
            params["marker"] = marker
        res = await api.mx("GET", "/updates", params=params, timeout=45)
        if "_error" in res:
            await asyncio.sleep(5)
            continue
        for u in res.get("updates", []):
            try:
                kind = u.get("update_type")
                if kind == "message_created":
                    await max_message(api, u.get("message") or {})
                elif kind == "bot_started":
                    user = u.get("user") or {}
                    if u.get("payload"):
                        await link(api, u["payload"], user.get("user_id"), full_name(user.get("first_name"), user.get("last_name")))
                    else:
                        await api.max_send(user.get("user_id"), "Это мост Libre: переписка с человеком из Telegram. "
                                                                "Откройте ссылку-приглашение или пришлите сюда код из 8 символов.")
            except Exception as e:
                log.exception("max update: %s", e)
        if res.get("marker") is not None:
            marker = str(res["marker"])
            kv_set("max_marker", marker)


async def housekeeping():
    while True:
        db.execute("DELETE FROM tg_map WHERE created < ?", (now() - MAP_TTL,))
        db.execute("DELETE FROM codes WHERE created < ?", (now() - CODE_TTL,))
        await asyncio.sleep(3600)


async def main():
    async with aiohttp.ClientSession() as session:
        api = Api(session)
        await api.tg("setMyCommands", {"commands": [
            {"command": "max", "description": "Ссылка-приглашение в MAX"},
            {"command": "chats", "description": "С кем вы на связи в MAX"},
        ]})
        await asyncio.gather(tg_loop(api), max_loop(api), housekeeping())


if __name__ == "__main__":
    asyncio.run(main())
