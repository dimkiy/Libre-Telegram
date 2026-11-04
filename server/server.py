import asyncio
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sqlite3
import time
import urllib.parse

from aiohttp import web

BOT_TOKEN = os.environ["BOT_TOKEN"]
PUBLIC_URL = os.environ["PUBLIC_URL"].rstrip("/")
DATA = os.environ.get("LIBRE_DATA", "/var/lib/libre")
PORT = int(os.environ.get("PORT", "8090"))
VIEW_SALT = os.environ.get("VIEW_SALT", "").encode() or hashlib.sha256(("salt" + BOT_TOKEN).encode()).digest()

DB_PATH = os.path.join(DATA, "db", "libre.db")
BANNERS = os.path.join(DATA, "media", "b")
WALL_PHOTOS = os.path.join(DATA, "media", "w")
BACKUPS = os.path.join(DATA, "backup")
CLIPS = os.path.join(DATA, "media", "c")
CLIP_MAX = 40 * 1024 * 1024
CLIPS_TOTAL = int(os.environ.get("CLIPS_MAX_MB", "4096")) * 1024 * 1024
MAX_BANNER = 16 * 1024 * 1024
TOKEN_TTL = 180 * 86400
INIT_DATA_TTL = 86400

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("libre")

os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
os.makedirs(BANNERS, exist_ok=True)
os.makedirs(WALL_PHOTOS, exist_ok=True)
os.makedirs(BACKUPS, exist_ok=True)
os.makedirs(CLIPS, exist_ok=True)

db = sqlite3.connect(DB_PATH, isolation_level=None, check_same_thread=False)
db.execute("PRAGMA journal_mode=WAL")
db.execute("PRAGMA synchronous=NORMAL")
db.executescript("""
CREATE TABLE IF NOT EXISTS users (
    id           INTEGER PRIMARY KEY,
    since        INTEGER NOT NULL,
    views        INTEGER NOT NULL DEFAULT 0,
    banner       TEXT,
    banner_video INTEGER NOT NULL DEFAULT 0,
    banner_ver   INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS tokens (
    hash    TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tokens_user ON tokens(user_id);
CREATE TABLE IF NOT EXISTS gifts (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    from_id INTEGER NOT NULL,
    to_id   INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS gifts_to ON gifts(to_id);
CREATE TABLE IF NOT EXISTS seen (
    target INTEGER NOT NULL,
    viewer TEXT NOT NULL,
    day    INTEGER NOT NULL,
    PRIMARY KEY (target, viewer, day)
);
CREATE TABLE IF NOT EXISTS posts (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    owner    INTEGER NOT NULL,
    author   INTEGER NOT NULL,
    name     TEXT,
    text     TEXT NOT NULL,
    photo    TEXT,
    created  INTEGER NOT NULL,
    likes    INTEGER NOT NULL DEFAULT 0,
    comments INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS posts_owner ON posts(owner, id);
CREATE INDEX IF NOT EXISTS posts_author ON posts(author);
CREATE TABLE IF NOT EXISTS comments (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    post    INTEGER NOT NULL,
    author  INTEGER NOT NULL,
    name    TEXT,
    text    TEXT NOT NULL,
    created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS comments_post ON comments(post, id);
CREATE TABLE IF NOT EXISTS likes (
    post INTEGER NOT NULL,
    user INTEGER NOT NULL,
    PRIMARY KEY (post, user)
);
CREATE TABLE IF NOT EXISTS walls (
    owner  INTEGER PRIMARY KEY,
    who    INTEGER NOT NULL DEFAULT 0,
    hidden INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS wall_blocked (
    owner INTEGER NOT NULL,
    user  INTEGER NOT NULL,
    PRIMARY KEY (owner, user)
);
CREATE TABLE IF NOT EXISTS cards (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    from_id INTEGER NOT NULL,
    to_id   INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS showcases (
    user_id INTEGER PRIMARY KEY,
    payload TEXT NOT NULL,
    ver     INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS clans (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    TEXT NOT NULL,
    tag     TEXT NOT NULL,
    tag_key TEXT NOT NULL UNIQUE,
    color   INTEGER NOT NULL,
    owner   INTEGER NOT NULL,
    created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS clan_members (
    user   INTEGER PRIMARY KEY,
    clan   INTEGER NOT NULL,
    role   INTEGER NOT NULL DEFAULT 0,
    joined INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS clan_members_clan ON clan_members(clan);
CREATE TABLE IF NOT EXISTS clan_invites (
    code    TEXT PRIMARY KEY,
    clan    INTEGER NOT NULL,
    created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS public_playlists (
    user_id INTEGER PRIMARY KEY,
    name    TEXT NOT NULL,
    count   INTEGER NOT NULL,
    payload TEXT NOT NULL,
    ver     INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS nft_numbers (
    gift_id INTEGER NOT NULL,
    num     INTEGER NOT NULL,
    owner   INTEGER NOT NULL,
    PRIMARY KEY (gift_id, num)
);
CREATE INDEX IF NOT EXISTS nft_numbers_owner ON nft_numbers(owner);
CREATE TABLE IF NOT EXISTS reports (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    post     INTEGER NOT NULL,
    reporter INTEGER NOT NULL,
    created  INTEGER NOT NULL,
    UNIQUE (post, reporter)
);
""")


def ensure_column(table, column, decl):
    if column not in {r[1] for r in db.execute(f"PRAGMA table_info({table})")}:
        db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {decl}")


ensure_column("clan_members", "name", "TEXT")
ensure_column("users", "ach", "TEXT")
ensure_column("users", "status", "INTEGER NOT NULL DEFAULT 0")
ensure_column("users", "status_text", "TEXT")
ensure_column("users", "status_until", "INTEGER NOT NULL DEFAULT 0")
ensure_column("users", "wear", "TEXT")
ensure_column("users", "anon", "TEXT")

db.executescript("""
CREATE TABLE IF NOT EXISTS relay (
    doc      INTEGER PRIMARY KEY,
    src      TEXT NOT NULL,
    created  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS relay_wait (
    user     INTEGER NOT NULL,
    size     INTEGER NOT NULL,
    doc      INTEGER NOT NULL,
    created  INTEGER NOT NULL,
    PRIMARY KEY (user, doc)
);
""")


def now() -> int:
    return int(time.time())


def sha(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()


def check_init_data(init_data: str):
    try:
        pairs = dict(urllib.parse.parse_qsl(init_data, keep_blank_values=True, strict_parsing=True))
    except ValueError:
        return None
    got = pairs.pop("hash", None)
    if not got:
        return None
    check = "\n".join(f"{k}={pairs[k]}" for k in sorted(pairs))
    secret = hmac.new(b"WebAppData", BOT_TOKEN.encode(), hashlib.sha256).digest()
    want = hmac.new(secret, check.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(want, got):
        return None
    try:
        if now() - int(pairs.get("auth_date", "0")) > INIT_DATA_TTL:
            return None
        user = json.loads(pairs["user"])
        uid = int(user["id"])
        return uid if uid > 0 else None
    except (KeyError, ValueError, TypeError):
        return None


def user_of(request: web.Request):
    h = request.headers.get("Authorization", "")
    if not h.startswith("Bearer "):
        return None
    row = db.execute("SELECT user_id, created FROM tokens WHERE hash=?", (sha(h[7:].strip()),)).fetchone()
    if not row or now() - row[1] > TOKEN_TTL:
        return None
    return row[0]


_hits: dict = {}


def limited(key, per_minute: int) -> bool:
    minute = now() // 60
    k = (key, minute)
    _hits[k] = _hits.get(k, 0) + 1
    if len(_hits) > 50000:
        for old in [x for x in _hits if x[1] < minute]:
            _hits.pop(old, None)
    return _hits[k] > per_minute


def need_user(handler):
    async def wrapped(request: web.Request):
        uid = user_of(request)
        if uid is None:
            return web.json_response({"error": "auth"}, status=401)
        if limited(uid, 240):
            return web.json_response({"error": "slow down"}, status=429)
        request["uid"] = uid
        return await handler(request)
    return wrapped


async def auth(request: web.Request):
    ip = request.headers.get("X-Real-IP", request.remote or "")
    if limited(("ip", ip), 30):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    uid = check_init_data(str(body.get("init_data", "")))
    if uid is None:
        return web.json_response({"error": "bad signature"}, status=403)
    db.execute("INSERT OR IGNORE INTO users(id, since) VALUES(?, ?)", (uid, now()))
    token = secrets.token_urlsafe(32)
    db.execute("INSERT INTO tokens(hash, user_id, created) VALUES(?, ?, ?)", (sha(token), uid, now()))
    db.execute("""DELETE FROM tokens WHERE user_id=? AND hash NOT IN
                  (SELECT hash FROM tokens WHERE user_id=? ORDER BY created DESC LIMIT 5)""", (uid, uid))
    since = db.execute("SELECT since FROM users WHERE id=?", (uid,)).fetchone()[0]
    return web.json_response({"token": token, "id": uid, "since": since})


def profile_json(row):
    uid, since, views, banner, video, ver = row
    sc = db.execute("SELECT ver FROM showcases WHERE user_id=?", (uid,)).fetchone()
    out = {"id": uid, "since": since, "views": views, "banner": None, "showcase": sc[0] if sc else 0,
           "clan": clan_brief(uid), "playlist": None}
    pl = db.execute("SELECT name, count, ver FROM public_playlists WHERE user_id=?", (uid,)).fetchone()
    if pl:
        out["playlist"] = {"name": pl[0], "count": pl[1], "ver": pl[2]}
    if banner:
        out["banner"] = {"url": f"{PUBLIC_URL}/media/b/{banner}", "video": bool(video), "ver": ver}
    extra = db.execute("SELECT ach, status, status_text, status_until, wear, anon FROM users WHERE id=?", (uid,)).fetchone()
    reported = []
    try:
        reported = json.loads(extra[0]) if extra and extra[0] else []
    except ValueError:
        pass
    out["ach"] = achievements_of(uid, since, views, reported)
    if extra:
        status = public_status(extra[1], extra[2], extra[3])
        if status:
            out["status"] = status
        if extra[4]:
            out["wear"] = json.loads(extra[4])
        if extra[5]:
            out["anon"] = extra[5]
    return out


def public_status(s, text, until):
    if until and until <= now():
        return None
    if s in (STATUS_IDLE, STATUS_DND) or s == STATUS_ONLINE and text:
        return {"s": s, "text": text or "", "until": until}
    return None


CLIENT_ACH = ("dj", "listener", "nfc", "meloman")
EARLY_UNTIL = 1796083200
LIBRE_EPOCH = 1791331200


def achievements_of(uid, since, views, reported):
    out = [{"id": "libre"}]
    if since and since < EARLY_UNTIL:
        out.append({"id": "early"})
    for a in reported:
        if isinstance(a, dict) and a.get("id") in CLIENT_ACH:
            out.append({"id": a["id"], "desc": clean(a.get("desc"), 120)})
    clan = db.execute("SELECT name FROM clans WHERE owner=?", (uid,)).fetchone()
    if clan:
        out.append({"id": "founder", "desc": f"Основал клан «{clan[0]}»"})
    posts = db.execute("SELECT COUNT(*) FROM posts WHERE author=?", (uid,)).fetchone()[0]
    if posts >= 10:
        out.append({"id": "wall", "desc": f"Написал {posts} записей на стенах"})
    if views >= 1000:
        out.append({"id": "popular", "desc": f"{views} просмотров профиля"})
    return out


@need_user
async def put_achievements(request: web.Request):
    uid = request["uid"]
    if limited(("ach", uid), 10):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
        items = body.get("list")
        if not isinstance(items, list) or len(items) > 20:
            raise ValueError
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    keep = [{"id": a["id"], "desc": clean(a.get("desc"), 120)} for a in items
            if isinstance(a, dict) and a.get("id") in CLIENT_ACH]
    db.execute("UPDATE users SET ach=? WHERE id=?", (json.dumps(keep, ensure_ascii=False), uid))
    try:
        since = int(body.get("since", 0))
    except (TypeError, ValueError):
        since = 0
    if LIBRE_EPOCH <= since < now():
        db.execute("UPDATE users SET since=MIN(since, ?) WHERE id=?", (since, uid))
    return web.json_response({"ok": True})


STATUS_ONLINE, STATUS_IDLE, STATUS_DND, STATUS_INVISIBLE = 0, 1, 2, 3


@need_user
async def put_status(request: web.Request):
    uid = request["uid"]
    if limited(("status", uid), 20):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
        s = int(body.get("s", 0))
        until = int(body.get("until", 0))
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    if s not in (STATUS_ONLINE, STATUS_IDLE, STATUS_DND, STATUS_INVISIBLE) or until < 0:
        return web.json_response({"error": "bad request"}, status=400)
    text = clean(body.get("text"), 64).replace("\n", " ")
    db.execute("UPDATE users SET status=?, status_text=?, status_until=? WHERE id=?", (s, text, until, uid))
    return web.json_response({"ok": True})


@need_user
async def presence(request: web.Request):
    if limited(("presence", request["uid"]), 40):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
        ids = [int(x) for x in body.get("ids", [])][:200]
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    out = {}
    if ids:
        marks = ",".join("?" * len(ids))
        for uid, s, text, until, wear in db.execute(
                f"SELECT id, status, status_text, status_until, wear FROM users WHERE id IN ({marks})", ids):
            entry = {}
            status = public_status(s, text, until)
            if status:
                entry["status"] = status
            if wear:
                entry["wear"] = json.loads(wear)
            out[str(uid)] = entry
    return web.json_response({"users": out})


WEAR_TITLE = 64


@need_user
async def put_wear(request: web.Request):
    uid = request["uid"]
    if limited(("wear", uid), 20):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    if not body or not body.get("doc"):
        db.execute("UPDATE users SET wear=NULL WHERE id=?", (uid,))
        return web.json_response({"ok": True})
    try:
        colors = [int(c) & 0xFFFFFFFF for c in body.get("colors", [])][:4]
        wear = {
            "id": int(body["id"]),
            "doc": int(body["doc"]),
            "pattern": int(body.get("pattern") or 0),
            "colors": colors + [0] * (4 - len(colors)),
            "title": clean(body.get("title"), WEAR_TITLE).replace("\n", " "),
            "num": max(0, int(body.get("num") or 0)),
        }
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    db.execute("UPDATE users SET wear=? WHERE id=?", (json.dumps(wear, separators=(",", ":")), uid))
    return web.json_response({"ok": True})


ANON_NUMBER = re.compile(r"888\d{4,10}")


@need_user
async def put_anon(request: web.Request):
    uid = request["uid"]
    if limited(("anon", uid), 10):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    phone = "".join(ch for ch in str(body.get("phone") or "") if ch.isdigit())
    if phone and not ANON_NUMBER.fullmatch(phone):
        return web.json_response({"error": "not an anonymous number"}, status=400)
    db.execute("UPDATE users SET anon=? WHERE id=?", (phone or None, uid))
    return web.json_response({"ok": True})


@need_user
async def get_profile(request: web.Request):
    try:
        target = int(request.match_info["id"])
    except ValueError:
        return web.json_response({"error": "bad id"}, status=400)
    row = db.execute("SELECT id, since, views, banner, banner_video, banner_ver FROM users WHERE id=?", (target,)).fetchone()
    if not row:
        return web.json_response({"error": "not a libre user"}, status=404)
    return web.json_response(profile_json(row))


@need_user
async def view(request: web.Request):
    uid = request["uid"]
    try:
        target = int(request.match_info["id"])
    except ValueError:
        return web.json_response({"error": "bad id"}, status=400)
    if target == uid:
        return web.json_response({"ok": True})
    viewer = hmac.new(VIEW_SALT, str(uid).encode(), hashlib.sha256).hexdigest()[:20]
    day = now() // 86400
    cur = db.execute("INSERT OR IGNORE INTO seen(target, viewer, day) VALUES(?, ?, ?)", (target, viewer, day))
    if cur.rowcount:
        db.execute("UPDATE users SET views = views + 1 WHERE id=?", (target,))
    return web.json_response({"ok": True})


def kind_of(data: bytes):
    if data[:3] == b"\xff\xd8\xff":
        return "jpg", False
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png", False
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp", False
    if data[4:8] == b"ftyp":
        return "mp4", True
    return None, False


def drop_banner_file(name):
    if name:
        try:
            os.remove(os.path.join(BANNERS, name))
        except OSError:
            pass


@need_user
async def put_banner(request: web.Request):
    uid = request["uid"]
    if limited(("banner", uid), 6):
        return web.json_response({"error": "slow down"}, status=429)
    data = await request.read()
    if not data or len(data) > MAX_BANNER:
        return web.json_response({"error": "too big"}, status=413)
    ext, video = kind_of(data)
    if not ext:
        return web.json_response({"error": "not an image or mp4"}, status=415)
    name = f"{uid}_{secrets.token_hex(8)}.{ext}"
    tmp = os.path.join(BANNERS, name + ".part")
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, os.path.join(BANNERS, name))
    old = db.execute("SELECT banner FROM users WHERE id=?", (uid,)).fetchone()
    db.execute("UPDATE users SET banner=?, banner_video=?, banner_ver=banner_ver+1 WHERE id=?", (name, int(video), uid))
    if old:
        drop_banner_file(old[0])
    row = db.execute("SELECT id, since, views, banner, banner_video, banner_ver FROM users WHERE id=?", (uid,)).fetchone()
    return web.json_response(profile_json(row))


@need_user
async def delete_banner(request: web.Request):
    uid = request["uid"]
    old = db.execute("SELECT banner FROM users WHERE id=?", (uid,)).fetchone()
    db.execute("UPDATE users SET banner=NULL, banner_video=0, banner_ver=banner_ver+1 WHERE id=?", (uid,))
    if old:
        drop_banner_file(old[0])
    return web.json_response({"ok": True})


@need_user
async def delete_me(request: web.Request):
    uid = request["uid"]
    old = db.execute("SELECT banner FROM users WHERE id=?", (uid,)).fetchone()
    if old:
        drop_banner_file(old[0])
    db.execute("DELETE FROM users WHERE id=?", (uid,))
    db.execute("DELETE FROM tokens WHERE user_id=?", (uid,))
    db.execute("DELETE FROM seen WHERE target=?", (uid,))
    db.execute("DELETE FROM gifts WHERE to_id=? OR from_id=?", (uid, uid))
    forget_wall_user(uid)
    db.execute("DELETE FROM showcases WHERE user_id=?", (uid,))
    db.execute("DELETE FROM cards WHERE from_id=? OR to_id=?", (uid, uid))
    db.execute("DELETE FROM public_playlists WHERE user_id=?", (uid,))
    db.execute("DELETE FROM relay_wait WHERE user=?", (uid,))
    db.execute("DELETE FROM nft_numbers WHERE owner=?", (uid,))
    leave_clan(uid)
    drop_backup(uid)
    return web.json_response({"ok": True})


MAX_GIFT = 8 * 1024


@need_user
async def send_gift(request: web.Request):
    uid = request["uid"]
    if limited(("gift", uid), 20):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
        to = int(body["to"])
        payload = json.dumps(body["gift"], ensure_ascii=False, separators=(",", ":"))
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    if to == uid or len(payload) > MAX_GIFT:
        return web.json_response({"error": "bad request"}, status=400)
    if not db.execute("SELECT 1 FROM users WHERE id=?", (to,)).fetchone():
        return web.json_response({"error": "not a libre user"}, status=404)
    if db.execute("SELECT COUNT(*) FROM gifts WHERE to_id=?", (to,)).fetchone()[0] >= 200:
        return web.json_response({"error": "inbox full"}, status=409)
    move_number(body["gift"], uid, to)
    card = db.execute("INSERT INTO cards(from_id, to_id, payload, created) VALUES(?, ?, ?, ?)", (uid, to, payload, now())).lastrowid
    cur = db.execute("INSERT INTO gifts(from_id, to_id, payload, created) VALUES(?, ?, ?, ?)",
                     (uid, to, json.dumps({**body["gift"], "card": card}, ensure_ascii=False, separators=(",", ":")), now()))
    return web.json_response({"ok": True, "id": cur.lastrowid, "card": card})


@need_user
async def get_card(request: web.Request):
    uid = request["uid"]
    try:
        cid = int(request.match_info["id"])
    except ValueError:
        return web.json_response({"error": "bad id"}, status=400)
    row = db.execute("SELECT from_id, to_id, payload, created FROM cards WHERE id=?", (cid,)).fetchone()
    if not row or uid not in (row[0], row[1]):
        return web.json_response({"error": "not found"}, status=404)
    return web.json_response({"id": cid, "from": row[0], "to": row[1], "gift": json.loads(row[2]), "created": row[3]})


MAX_SHOWCASE = 1024 * 1024


@need_user
async def put_showcase(request: web.Request):
    uid = request["uid"]
    if limited(("showcase", uid), 10):
        return web.json_response({"error": "slow down"}, status=429)
    data = await request.read()
    if len(data) > MAX_SHOWCASE:
        return web.json_response({"error": "too big"}, status=413)
    try:
        gifts = json.loads(data.decode("utf-8")).get("gifts")
        if not isinstance(gifts, list) or len(gifts) > 150:
            raise ValueError
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    fixes = settle_numbers(uid, gifts)
    payload = json.dumps(gifts, ensure_ascii=False, separators=(",", ":"))
    if not gifts:
        db.execute("DELETE FROM showcases WHERE user_id=?", (uid,))
        return web.json_response({"ver": 0, "fixes": fixes})
    db.execute("""INSERT INTO showcases(user_id, payload, ver) VALUES(?, ?, 1)
                  ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload, ver=showcases.ver+1""", (uid, payload))
    ver = db.execute("SELECT ver FROM showcases WHERE user_id=?", (uid,)).fetchone()[0]
    return web.json_response({"ver": ver, "fixes": fixes})


@need_user
async def get_showcase(request: web.Request):
    try:
        target = int(request.match_info["id"])
    except ValueError:
        return web.json_response({"error": "bad id"}, status=400)
    row = db.execute("SELECT payload, ver FROM showcases WHERE user_id=?", (target,)).fetchone()
    if not row:
        return web.json_response({"gifts": [], "ver": 0})
    return web.json_response({"gifts": json.loads(row[0]), "ver": row[1]})


@need_user
async def gift_inbox(request: web.Request):
    rows = db.execute("SELECT id, from_id, payload, created FROM gifts WHERE to_id=? ORDER BY id LIMIT 50", (request["uid"],)).fetchall()
    return web.json_response({"gifts": [{"id": r[0], "from": r[1], "gift": json.loads(r[2]), "created": r[3]} for r in rows]})


@need_user
async def gift_ack(request: web.Request):
    try:
        gid = int(request.match_info["id"])
    except ValueError:
        return web.json_response({"error": "bad id"}, status=400)
    db.execute("DELETE FROM gifts WHERE id=? AND to_id=?", (gid, request["uid"]))
    return web.json_response({"ok": True})


MAX_POST = 2000
MAX_COMMENT = 500
MAX_NAME = 64
MAX_PHOTO = 6 * 1024 * 1024
WALL_ALL, WALL_CONTACTS, WALL_NOBODY = 0, 1, 2
PAGE = 20


def wall_of(owner):
    if owner < 0:
        return WALL_ALL, False
    row = db.execute("SELECT who, hidden FROM walls WHERE owner=?", (owner,)).fetchone()
    return (row[0], bool(row[1])) if row else (WALL_ALL, False)


def is_blocked(owner, uid):
    if owner < 0:
        return False
    return db.execute("SELECT 1 FROM wall_blocked WHERE owner=? AND user=?", (owner, uid)).fetchone() is not None


def can_see(owner, uid):
    return uid == owner or not wall_of(owner)[1]


def can_post(owner, uid, mutual):
    if owner < 0:
        return clan_role(-owner, uid) is not None
    if uid == owner:
        return True
    who, hidden = wall_of(owner)
    if hidden or who == WALL_NOBODY or (who == WALL_CONTACTS and not mutual):
        return False
    return not is_blocked(owner, uid)


def can_comment(owner, uid):
    if owner < 0:
        return clan_role(-owner, uid) is not None
    return uid == owner or (not wall_of(owner)[1] and not is_blocked(owner, uid))


def photo_url(name):
    return f"{PUBLIC_URL}/media/w/{name}" if name else None


POST_COLS = "p.id, p.owner, p.author, p.name, p.text, p.photo, p.created, p.likes, p.comments, (l.user IS NOT NULL)"


def post_rows(where, params, uid):
    return db.execute(f"SELECT {POST_COLS} FROM posts p LEFT JOIN likes l ON l.post=p.id AND l.user=? WHERE {where}",
                      (uid, *params)).fetchall()


def post_json(r):
    return {"id": r[0], "owner": r[1], "author": r[2], "name": r[3] or "", "text": r[4], "photo": photo_url(r[5]),
            "date": r[6], "likes": r[7], "comments": r[8], "liked": bool(r[9])}


def comment_json(r):
    return {"id": r[0], "author": r[1], "name": r[2] or "", "text": r[3], "date": r[4]}


def drop_wall_photo(name):
    if name:
        try:
            os.remove(os.path.join(WALL_PHOTOS, name))
        except OSError:
            pass


def drop_posts(where, params):
    rows = db.execute(f"SELECT id, photo FROM posts WHERE {where}", params).fetchall()
    for pid, photo in rows:
        db.execute("DELETE FROM comments WHERE post=?", (pid,))
        db.execute("DELETE FROM likes WHERE post=?", (pid,))
        db.execute("DELETE FROM reports WHERE post=?", (pid,))
        db.execute("DELETE FROM posts WHERE id=?", (pid,))
        drop_wall_photo(photo)


def recount(post_ids):
    for pid in set(post_ids):
        db.execute("""UPDATE posts SET likes=(SELECT COUNT(*) FROM likes WHERE post=?),
                      comments=(SELECT COUNT(*) FROM comments WHERE post=?) WHERE id=?""", (pid, pid, pid))


def clean(text, limit):
    text = str(text or "").replace("\r\n", "\n").strip()
    while "\n\n\n" in text:
        text = text.replace("\n\n\n", "\n\n")
    return text[:limit]


def int_arg(request, key):
    try:
        return int(request.match_info[key])
    except (KeyError, ValueError):
        return None


def wall_exists(owner):
    if owner < 0:
        return db.execute("SELECT 1 FROM clans WHERE id=?", (-owner,)).fetchone() is not None
    return db.execute("SELECT 1 FROM users WHERE id=?", (owner,)).fetchone() is not None


def wall_moderator(owner, uid):
    if owner < 0:
        role = clan_role(-owner, uid)
        return role is not None and role >= ROLE_OFFICER
    return uid == owner


def get_post(pid):
    return db.execute("SELECT id, owner, author, photo FROM posts WHERE id=?", (pid,)).fetchone()


@need_user
async def wall(request: web.Request):
    uid = request["uid"]
    owner = int_arg(request, "owner")
    if owner is None:
        return web.json_response({"error": "bad id"}, status=400)
    if not wall_exists(owner):
        return web.json_response({"error": "not a libre user"}, status=404)
    who, hidden = wall_of(owner)
    mutual = request.query.get("mutual") == "1"
    out = {"who": who, "hidden": hidden, "canPost": can_post(owner, uid, mutual), "canComment": can_comment(owner, uid)}
    if not can_see(owner, uid):
        out.update(posts=[], total=0)
        return web.json_response(out)
    try:
        before = int(request.query.get("before", "0"))
    except ValueError:
        before = 0
    if before > 0:
        rows = post_rows("p.owner=? AND p.id<? ORDER BY p.id DESC LIMIT ?", (owner, before, PAGE), uid)
    else:
        rows = post_rows("p.owner=? ORDER BY p.id DESC LIMIT ?", (owner, PAGE), uid)
    out["posts"] = []
    for r in rows:
        pj = post_json(r)
        if r[8]:
            last = db.execute("SELECT id, author, name, text, created FROM comments WHERE post=? ORDER BY id DESC LIMIT 2", (r[0],)).fetchall()
            pj["last"] = [comment_json(c) for c in reversed(last)]
        out["posts"].append(pj)
    out["total"] = db.execute("SELECT COUNT(*) FROM posts WHERE owner=?", (owner,)).fetchone()[0]
    if owner == uid:
        out["blocked"] = [r[0] for r in db.execute("SELECT user FROM wall_blocked WHERE owner=?", (owner,))]
    out["moderator"] = wall_moderator(owner, uid)
    return web.json_response(out)


@need_user
async def put_wall_photo(request: web.Request):
    uid = request["uid"]
    if limited(("photo", uid), 10):
        return web.json_response({"error": "slow down"}, status=429)
    data = await request.read()
    if not data or len(data) > MAX_PHOTO:
        return web.json_response({"error": "too big"}, status=413)
    ext, video = kind_of(data)
    if not ext or video:
        return web.json_response({"error": "not an image"}, status=415)
    name = f"{uid}_{secrets.token_hex(10)}.{ext}"
    tmp = os.path.join(WALL_PHOTOS, name + ".part")
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, os.path.join(WALL_PHOTOS, name))
    return web.json_response({"photo": name, "url": photo_url(name)})


@need_user
async def add_post(request: web.Request):
    uid = request["uid"]
    owner = int_arg(request, "owner")
    if owner is None:
        return web.json_response({"error": "bad id"}, status=400)
    if limited(("post", uid), 15):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    text = clean(body.get("text"), MAX_POST)
    photo = body.get("photo") or None
    if photo is not None:
        photo = str(photo)
        if (not photo.startswith(f"{uid}_") or "/" in photo or ".." in photo
                or not os.path.exists(os.path.join(WALL_PHOTOS, photo))
                or db.execute("SELECT 1 FROM posts WHERE photo=?", (photo,)).fetchone()):
            return web.json_response({"error": "bad photo"}, status=400)
    if not text and not photo:
        return web.json_response({"error": "empty"}, status=400)
    if not wall_exists(owner):
        return web.json_response({"error": "not a libre user"}, status=404)
    if not can_post(owner, uid, bool(body.get("mutual"))):
        return web.json_response({"error": "not allowed"}, status=403)
    cur = db.execute("INSERT INTO posts(owner, author, name, text, photo, created) VALUES(?, ?, ?, ?, ?, ?)",
                     (owner, uid, clean(body.get("name"), MAX_NAME), text, photo, now()))
    return web.json_response(post_json(post_rows("p.id=?", (cur.lastrowid,), uid)[0]))


@need_user
async def delete_post(request: web.Request):
    uid = request["uid"]
    p = get_post(int_arg(request, "id") or 0)
    if not p:
        return web.json_response({"ok": True})
    if uid != p[2] and not wall_moderator(p[1], uid):
        return web.json_response({"error": "not allowed"}, status=403)
    drop_posts("id=?", (p[0],))
    return web.json_response({"ok": True})


@need_user
async def like_post(request: web.Request):
    uid = request["uid"]
    p = get_post(int_arg(request, "id") or 0)
    if not p:
        return web.json_response({"error": "gone"}, status=404)
    if not can_see(p[1], uid):
        return web.json_response({"error": "not allowed"}, status=403)
    if limited(("like", uid), 60):
        return web.json_response({"error": "slow down"}, status=429)
    cur = db.execute("INSERT OR IGNORE INTO likes(post, user) VALUES(?, ?)", (p[0], uid))
    liked = True
    if not cur.rowcount:
        db.execute("DELETE FROM likes WHERE post=? AND user=?", (p[0], uid))
        liked = False
    recount([p[0]])
    likes = db.execute("SELECT likes FROM posts WHERE id=?", (p[0],)).fetchone()[0]
    return web.json_response({"likes": likes, "liked": liked})


@need_user
async def get_post_full(request: web.Request):
    uid = request["uid"]
    pid = int_arg(request, "id") or 0
    rows = post_rows("p.id=?", (pid,), uid)
    if not rows:
        return web.json_response({"error": "gone"}, status=404)
    owner = rows[0][1]
    if not can_see(owner, uid):
        return web.json_response({"error": "not allowed"}, status=403)
    comments = db.execute("SELECT id, author, name, text, created FROM comments WHERE post=? ORDER BY id LIMIT 300", (pid,)).fetchall()
    return web.json_response({"post": post_json(rows[0]), "comments": [comment_json(c) for c in comments],
                              "canComment": can_comment(owner, uid)})


@need_user
async def add_comment(request: web.Request):
    uid = request["uid"]
    p = get_post(int_arg(request, "id") or 0)
    if not p:
        return web.json_response({"error": "gone"}, status=404)
    if limited(("comment", uid), 30):
        return web.json_response({"error": "slow down"}, status=429)
    if not can_comment(p[1], uid):
        return web.json_response({"error": "not allowed"}, status=403)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    text = clean(body.get("text"), MAX_COMMENT)
    if not text:
        return web.json_response({"error": "empty"}, status=400)
    if db.execute("SELECT COUNT(*) FROM comments WHERE post=?", (p[0],)).fetchone()[0] >= 300:
        return web.json_response({"error": "too many comments"}, status=409)
    cur = db.execute("INSERT INTO comments(post, author, name, text, created) VALUES(?, ?, ?, ?, ?)",
                     (p[0], uid, clean(body.get("name"), MAX_NAME), text, now()))
    recount([p[0]])
    row = db.execute("SELECT id, author, name, text, created FROM comments WHERE id=?", (cur.lastrowid,)).fetchone()
    return web.json_response(comment_json(row))


@need_user
async def delete_comment(request: web.Request):
    uid = request["uid"]
    row = db.execute("SELECT c.id, c.post, c.author, p.owner FROM comments c JOIN posts p ON p.id=c.post WHERE c.id=?",
                     (int_arg(request, "id") or 0,)).fetchone()
    if not row:
        return web.json_response({"ok": True})
    if uid != row[2] and not wall_moderator(row[3], uid):
        return web.json_response({"error": "not allowed"}, status=403)
    db.execute("DELETE FROM comments WHERE id=?", (row[0],))
    recount([row[1]])
    return web.json_response({"ok": True})


@need_user
async def wall_settings(request: web.Request):
    uid = request["uid"]
    try:
        body = await request.json()
        who = int(body.get("who", WALL_ALL))
        hidden = 1 if body.get("hidden") else 0
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    if who not in (WALL_ALL, WALL_CONTACTS, WALL_NOBODY):
        return web.json_response({"error": "bad request"}, status=400)
    db.execute("INSERT INTO walls(owner, who, hidden) VALUES(?, ?, ?) ON CONFLICT(owner) DO UPDATE SET who=excluded.who, hidden=excluded.hidden",
               (uid, who, hidden))
    return web.json_response({"who": who, "hidden": bool(hidden)})


@need_user
async def wall_block(request: web.Request):
    uid = request["uid"]
    try:
        body = await request.json()
        user = int(body["user"])
        block = bool(body.get("block", True))
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    if user == uid:
        return web.json_response({"error": "bad request"}, status=400)
    if block:
        db.execute("INSERT OR IGNORE INTO wall_blocked(owner, user) VALUES(?, ?)", (uid, user))
        drop_posts("owner=? AND author=?", (uid, user))
        touched = [r[0] for r in db.execute("SELECT DISTINCT c.post FROM comments c JOIN posts p ON p.id=c.post WHERE p.owner=? AND c.author=?", (uid, user))]
        db.execute("DELETE FROM comments WHERE author=? AND post IN (SELECT id FROM posts WHERE owner=?)", (user, uid))
        recount(touched)
    else:
        db.execute("DELETE FROM wall_blocked WHERE owner=? AND user=?", (uid, user))
    return web.json_response({"ok": True})


@need_user
async def report_post(request: web.Request):
    uid = request["uid"]
    p = get_post(int_arg(request, "id") or 0)
    if p and uid != p[2]:
        db.execute("INSERT OR IGNORE INTO reports(post, reporter, created) VALUES(?, ?, ?)", (p[0], uid, now()))
    return web.json_response({"ok": True})


def forget_wall_user(uid):
    drop_posts("owner=? OR author=?", (uid, uid))
    touched = [r[0] for r in db.execute("SELECT DISTINCT post FROM comments WHERE author=?", (uid,))]
    touched += [r[0] for r in db.execute("SELECT post FROM likes WHERE user=?", (uid,))]
    db.execute("DELETE FROM comments WHERE author=?", (uid,))
    db.execute("DELETE FROM likes WHERE user=?", (uid,))
    db.execute("DELETE FROM reports WHERE reporter=?", (uid,))
    db.execute("DELETE FROM walls WHERE owner=?", (uid,))
    db.execute("DELETE FROM wall_blocked WHERE owner=? OR user=?", (uid, uid))
    recount(touched)


def drop_orphan_photos():
    used = {r[0] for r in db.execute("SELECT photo FROM posts WHERE photo IS NOT NULL")}
    cutoff = time.time() - 86400
    for name in os.listdir(WALL_PHOTOS):
        path = os.path.join(WALL_PHOTOS, name)
        try:
            if name not in used and os.path.getmtime(path) < cutoff:
                os.remove(path)
        except OSError:
            pass


def parse_num(v):
    try:
        a, b = str(v).split("/")
        return max(1, int(a)), max(1, int(b))
    except (ValueError, TypeError):
        return None


def free_number(gift_id, total):
    taken = {r[0] for r in db.execute("SELECT num FROM nft_numbers WHERE gift_id=?", (gift_id,))}
    if len(taken) >= total:
        return None
    for _ in range(64):
        n = 1 + secrets.randbelow(total)
        if n not in taken:
            return n
    for n in range(1, total + 1):
        if n not in taken:
            return n
    return None


def settle_numbers(uid, gifts):
    fixes, kept = [], set()
    for g in gifts:
        if not isinstance(g, dict):
            continue
        try:
            gift_id = int(g.get("giftId", 0))
        except (ValueError, TypeError):
            continue
        nt = parse_num(g.get("num"))
        if not gift_id or not nt:
            continue
        num, total = nt
        row = db.execute("SELECT owner FROM nft_numbers WHERE gift_id=? AND num=?", (gift_id, num)).fetchone()
        if row and row[0] == uid and (gift_id, num) not in kept:
            kept.add((gift_id, num))
            continue
        if row is None and num <= total:
            db.execute("INSERT INTO nft_numbers(gift_id, num, owner) VALUES(?, ?, ?)", (gift_id, num, uid))
            kept.add((gift_id, num))
            continue
        new = free_number(gift_id, total)
        if new is None:
            continue
        db.execute("INSERT INTO nft_numbers(gift_id, num, owner) VALUES(?, ?, ?)", (gift_id, new, uid))
        kept.add((gift_id, new))
        g["num"] = f"{new}/{total}"
        fixes.append({"inst": g.get("inst"), "num": g["num"]})
    for gift_id, num in db.execute("SELECT gift_id, num FROM nft_numbers WHERE owner=?", (uid,)).fetchall():
        if (gift_id, num) not in kept:
            db.execute("DELETE FROM nft_numbers WHERE gift_id=? AND num=?", (gift_id, num))
    return fixes


def move_number(gift, from_id, to_id):
    try:
        nt = parse_num(gift.get("num"))
        gift_id = int(gift.get("giftId", 0))
    except (AttributeError, ValueError, TypeError):
        return
    if nt and gift_id:
        db.execute("UPDATE nft_numbers SET owner=? WHERE gift_id=? AND num=? AND owner=?", (to_id, gift_id, nt[0], from_id))


ROLE_MEMBER, ROLE_OFFICER, ROLE_HEAD = 0, 1, 2
MAX_CLAN = 200


def clan_role(clan_id, uid):
    row = db.execute("SELECT role FROM clan_members WHERE user=? AND clan=?", (uid, clan_id)).fetchone()
    return row[0] if row else None


def clan_rating(clan_id):
    members = db.execute("SELECT COUNT(*) FROM clan_members WHERE clan=?", (clan_id,)).fetchone()[0]
    posts, likes = db.execute("SELECT COUNT(*), COALESCE(SUM(likes), 0) FROM posts WHERE owner=?", (-clan_id,)).fetchone()
    return members * 100 + posts * 10 + likes


def clan_json(row, full=False, uid=None):
    cid, name, tag, color, owner, created = row
    members = db.execute("SELECT COUNT(*) FROM clan_members WHERE clan=?", (cid,)).fetchone()[0]
    out = {"id": cid, "name": name, "tag": tag, "color": color, "owner": owner, "created": created,
           "members": members, "rating": clan_rating(cid)}
    out["level"] = 1 + min(9, out["rating"] // 1000)
    if full:
        out["list"] = [{"user": r[0], "role": r[1], "joined": r[2], "name": r[3] or ""} for r in db.execute(
            "SELECT user, role, joined, name FROM clan_members WHERE clan=? ORDER BY role DESC, joined LIMIT 300", (cid,))]
        out["myRole"] = clan_role(cid, uid) if uid else None
    return out


def clan_row(clan_id):
    return db.execute("SELECT id, name, tag, color, owner, created FROM clans WHERE id=?", (clan_id,)).fetchone()


def clan_brief(uid):
    row = db.execute("""SELECT c.id, c.name, c.tag, c.color, m.role FROM clan_members m JOIN clans c ON c.id=m.clan
                        WHERE m.user=?""", (uid,)).fetchone()
    return {"id": row[0], "name": row[1], "tag": row[2], "color": row[3], "role": row[4]} if row else None


def clan_text(v, lo, hi):
    v = " ".join(str(v or "").split())
    return v if lo <= len(v) <= hi else None


def drop_clan(clan_id):
    drop_posts("owner=?", (-clan_id,))
    db.execute("DELETE FROM clan_members WHERE clan=?", (clan_id,))
    db.execute("DELETE FROM clan_invites WHERE clan=?", (clan_id,))
    db.execute("DELETE FROM clans WHERE id=?", (clan_id,))


def leave_clan(uid):
    row = db.execute("SELECT clan, role FROM clan_members WHERE user=?", (uid,)).fetchone()
    if not row:
        return
    clan_id, role = row
    db.execute("DELETE FROM clan_members WHERE user=?", (uid,))
    if role != ROLE_HEAD:
        return
    heir = db.execute("SELECT user FROM clan_members WHERE clan=? ORDER BY role DESC, joined LIMIT 1", (clan_id,)).fetchone()
    if not heir:
        drop_clan(clan_id)
        return
    db.execute("UPDATE clan_members SET role=? WHERE user=?", (ROLE_HEAD, heir[0]))
    db.execute("UPDATE clans SET owner=? WHERE id=?", (heir[0], clan_id))


@need_user
async def create_clan(request: web.Request):
    uid = request["uid"]
    if limited(("clan", uid), 5):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    name = clan_text(body.get("name"), 2, 32)
    tag = clan_text(body.get("tag"), 2, 5)
    if not name or not tag or not all(ch.isalnum() for ch in tag):
        return web.json_response({"error": "bad name or tag"}, status=400)
    if db.execute("SELECT 1 FROM clan_members WHERE user=?", (uid,)).fetchone():
        return web.json_response({"error": "already in a clan"}, status=409)
    if db.execute("SELECT 1 FROM clans WHERE tag_key=?", (tag.upper(),)).fetchone():
        return web.json_response({"error": "tag taken"}, status=409)
    try:
        color = int(body.get("color", 0)) & 0xFFFFFFFF
        if color >= 1 << 31:
            color -= 1 << 32
    except (ValueError, TypeError):
        color = 0
    cid = db.execute("INSERT INTO clans(name, tag, tag_key, color, owner, created) VALUES(?, ?, ?, ?, ?, ?)",
                     (name, tag, tag.upper(), color, uid, now())).lastrowid
    db.execute("INSERT INTO clan_members(user, clan, role, joined, name) VALUES(?, ?, ?, ?, ?)",
               (uid, cid, ROLE_HEAD, now(), clean(body.get("my_name"), MAX_NAME)))
    return web.json_response(clan_json(clan_row(cid), True, uid))


@need_user
async def get_clan(request: web.Request):
    row = clan_row(int_arg(request, "id") or 0)
    if not row:
        return web.json_response({"error": "not found"}, status=404)
    return web.json_response(clan_json(row, True, request["uid"]))


@need_user
async def my_clan(request: web.Request):
    brief = clan_brief(request["uid"])
    if not brief:
        return web.json_response({"clan": None})
    return web.json_response({"clan": clan_json(clan_row(brief["id"]), True, request["uid"])})


@need_user
async def clan_invite(request: web.Request):
    uid = request["uid"]
    cid = int_arg(request, "id") or 0
    role = clan_role(cid, uid)
    if role is None or role < ROLE_OFFICER:
        return web.json_response({"error": "not allowed"}, status=403)
    row = db.execute("SELECT code FROM clan_invites WHERE clan=? ORDER BY created DESC LIMIT 1", (cid,)).fetchone()
    code = row[0] if row else None
    if not code:
        code = secrets.token_urlsafe(6).replace("-", "x").replace("_", "y")
        db.execute("INSERT INTO clan_invites(code, clan, created) VALUES(?, ?, ?)", (code, cid, now()))
    return web.json_response({"code": code, "link": f"{PUBLIC_URL}/c/{code}"})


@need_user
async def clan_join(request: web.Request):
    uid = request["uid"]
    if limited(("join", uid), 10):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
        code = str(body.get("code", "")).strip().split("/")[-1]
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    row = db.execute("SELECT clan FROM clan_invites WHERE code=?", (code,)).fetchone()
    if not row or not clan_row(row[0]):
        return web.json_response({"error": "bad code"}, status=404)
    cid = row[0]
    cur = db.execute("SELECT clan FROM clan_members WHERE user=?", (uid,)).fetchone()
    if cur and cur[0] == cid:
        return web.json_response(clan_json(clan_row(cid), True, uid))
    if cur:
        return web.json_response({"error": "already in a clan"}, status=409)
    if db.execute("SELECT COUNT(*) FROM clan_members WHERE clan=?", (cid,)).fetchone()[0] >= MAX_CLAN:
        return web.json_response({"error": "clan full"}, status=409)
    db.execute("INSERT INTO clan_members(user, clan, role, joined, name) VALUES(?, ?, ?, ?, ?)",
               (uid, cid, ROLE_MEMBER, now(), clean(body.get("my_name"), MAX_NAME)))
    return web.json_response(clan_json(clan_row(cid), True, uid))


@need_user
async def clan_hello(request: web.Request):
    try:
        name = clean((await request.json()).get("my_name"), MAX_NAME)
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    if name:
        db.execute("UPDATE clan_members SET name=? WHERE user=?", (name, request["uid"]))
    return web.json_response({"ok": True})


@need_user
async def clan_preview(request: web.Request):
    row = db.execute("SELECT clan FROM clan_invites WHERE code=?", (request.match_info.get("code", ""),)).fetchone()
    c = clan_row(row[0]) if row else None
    if not c:
        return web.json_response({"error": "bad code"}, status=404)
    return web.json_response(clan_json(c))


@need_user
async def clan_leave(request: web.Request):
    leave_clan(request["uid"])
    return web.json_response({"ok": True})


@need_user
async def clan_kick(request: web.Request):
    uid = request["uid"]
    cid = int_arg(request, "id") or 0
    try:
        user = int((await request.json())["user"])
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    mine, theirs = clan_role(cid, uid), clan_role(cid, user)
    if mine is None or theirs is None or user == uid or mine < ROLE_OFFICER or theirs >= mine:
        return web.json_response({"error": "not allowed"}, status=403)
    db.execute("DELETE FROM clan_members WHERE user=?", (user,))
    return web.json_response({"ok": True})


@need_user
async def clan_set_role(request: web.Request):
    uid = request["uid"]
    cid = int_arg(request, "id") or 0
    try:
        body = await request.json()
        user, role = int(body["user"]), int(body["role"])
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    if clan_role(cid, uid) != ROLE_HEAD or user == uid or clan_role(cid, user) is None or role not in (ROLE_MEMBER, ROLE_OFFICER, ROLE_HEAD):
        return web.json_response({"error": "not allowed"}, status=403)
    if role == ROLE_HEAD:
        db.execute("UPDATE clan_members SET role=? WHERE user=?", (ROLE_OFFICER, uid))
        db.execute("UPDATE clans SET owner=? WHERE id=?", (user, cid))
    db.execute("UPDATE clan_members SET role=? WHERE user=?", (role, user))
    return web.json_response(clan_json(clan_row(cid), True, uid))


@need_user
async def clan_edit(request: web.Request):
    uid = request["uid"]
    cid = int_arg(request, "id") or 0
    if clan_role(cid, uid) != ROLE_HEAD:
        return web.json_response({"error": "not allowed"}, status=403)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    name = clan_text(body.get("name"), 2, 32)
    if name:
        db.execute("UPDATE clans SET name=? WHERE id=?", (name, cid))
    if "color" in body:
        try:
            color = int(body["color"]) & 0xFFFFFFFF
            db.execute("UPDATE clans SET color=? WHERE id=?", (color - (1 << 32) if color >= 1 << 31 else color, cid))
        except (ValueError, TypeError):
            pass
    return web.json_response(clan_json(clan_row(cid), True, uid))


@need_user
async def clan_disband(request: web.Request):
    uid = request["uid"]
    cid = int_arg(request, "id") or 0
    if clan_role(cid, uid) != ROLE_HEAD:
        return web.json_response({"error": "not allowed"}, status=403)
    drop_clan(cid)
    return web.json_response({"ok": True})


@need_user
async def clans_top(request: web.Request):
    rows = db.execute("SELECT id, name, tag, color, owner, created FROM clans").fetchall()
    out = sorted((clan_json(r) for r in rows), key=lambda c: -c["rating"])[:50]
    return web.json_response({"clans": out})


INVITE_PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Приглашение в клан Libre</title>
<style>body{margin:0;font:16px/1.5 system-ui,sans-serif;background:#161618;color:#f2f0eb;display:grid;place-items:center;min-height:100vh}
main{max-width:340px;padding:24px}h1{font-size:22px;margin:0 0 8px}p{color:#8d8b91;margin:0 0 10px}code{background:#222225;padding:2px 8px;border-radius:6px;color:#f2f0eb}</style></head>
<body><main><h1>Приглашение в клан</h1><p>Откройте эту ссылку в Libre — или в Libre: хаб → «Мой клан» → «Вступить по коду».</p><p>Код: <code>%s</code></p></main></body></html>"""


async def invite_page(request):
    code = "".join(ch for ch in request.match_info.get("code", "") if ch.isalnum())[:16]
    return web.Response(text=INVITE_PAGE % code, content_type="text/html")


MAX_PLAYLIST = 3 * 1024 * 1024


@need_user
async def put_playlist(request: web.Request):
    uid = request["uid"]
    if limited(("playlist", uid), 10):
        return web.json_response({"error": "slow down"}, status=429)
    data = await request.read()
    if len(data) > MAX_PLAYLIST:
        return web.json_response({"error": "too big"}, status=413)
    try:
        body = json.loads(data.decode("utf-8"))
        name = clan_text(body.get("name"), 1, 64)
        tracks = body.get("tracks")
        if not name or not isinstance(tracks, list) or len(tracks) > 500:
            raise ValueError
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    need = []
    for t in tracks:
        if not isinstance(t, dict):
            continue
        if not SOURCE.fullmatch(str(t.get("src") or "")):
            t.pop("src", None)
        doc, size = int_or0(t.get("doc")), int_or0(t.get("size"))
        if t.get("src") or not doc:
            continue
        if db.execute("SELECT 1 FROM relay WHERE doc=?", (doc,)).fetchone():
            continue
        if MUSIC_CHANNEL and size > 0 and len(need) < 100:
            db.execute("INSERT OR REPLACE INTO relay_wait(user, size, doc, created) VALUES(?, ?, ?, ?)", (uid, size, doc, now()))
            need.append(doc)
    payload = json.dumps(tracks, ensure_ascii=False, separators=(",", ":"))
    db.execute("""INSERT INTO public_playlists(user_id, name, count, payload, ver) VALUES(?, ?, ?, ?, 1)
                  ON CONFLICT(user_id) DO UPDATE SET name=excluded.name, count=excluded.count, payload=excluded.payload,
                  ver=public_playlists.ver+1""", (uid, name, len(tracks), payload))
    return web.json_response({"ok": True, "need": need})


@need_user
async def delete_playlist(request: web.Request):
    db.execute("DELETE FROM public_playlists WHERE user_id=?", (request["uid"],))
    return web.json_response({"ok": True})


@need_user
async def get_playlist(request: web.Request):
    row = db.execute("SELECT name, count, payload, ver FROM public_playlists WHERE user_id=?", (int_arg(request, "id") or 0,)).fetchone()
    if not row:
        return web.json_response({"error": "not found"}, status=404)
    tracks = json.loads(row[2])
    for t in tracks:
        if isinstance(t, dict) and not t.get("src") and t.get("doc"):
            r = db.execute("SELECT src FROM relay WHERE doc=?", (int_or0(t.get("doc")),)).fetchone()
            if r:
                t["src"] = r[0]
    return web.json_response({"name": row[0], "count": row[1], "tracks": tracks, "ver": row[3]})


SOURCE = re.compile(r"[A-Za-z][A-Za-z0-9_]{3,31}/\d{1,10}")


def int_or0(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


MAX_BACKUP = 12 * 1024 * 1024


def backup_path(uid):
    return os.path.join(BACKUPS, f"{uid}.bin")


def drop_backup(uid):
    try:
        os.remove(backup_path(uid))
    except OSError:
        pass


@need_user
async def put_backup(request: web.Request):
    uid = request["uid"]
    if limited(("backup", uid), 4):
        return web.json_response({"error": "slow down"}, status=429)
    data = await request.read()
    if not data or len(data) > MAX_BACKUP:
        return web.json_response({"error": "too big"}, status=413)
    tmp = backup_path(uid) + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, backup_path(uid))
    return web.json_response({"size": len(data), "date": now()})


@need_user
async def get_backup(request: web.Request):
    path = backup_path(request["uid"])
    if not os.path.exists(path):
        return web.json_response({"error": "none"}, status=404)
    if request.query.get("info") == "1":
        return web.json_response({"size": os.path.getsize(path), "date": int(os.path.getmtime(path))})
    with open(path, "rb") as f:
        return web.Response(body=f.read(), content_type="application/octet-stream",
                            headers={"X-Backup-Date": str(int(os.path.getmtime(path)))})


@need_user
async def delete_backup(request: web.Request):
    drop_backup(request["uid"])
    return web.json_response({"ok": True})


@need_user
async def export_me(request: web.Request):
    uid = request["uid"]
    user = db.execute("SELECT id, since, views, banner, banner_video FROM users WHERE id=?", (uid,)).fetchone()
    q = lambda sql, *a: db.execute(sql, a).fetchall()
    walls = db.execute("SELECT who, hidden FROM walls WHERE owner=?", (uid,)).fetchone()
    sc = db.execute("SELECT payload FROM showcases WHERE user_id=?", (uid,)).fetchone()
    pl = db.execute("SELECT name, count FROM public_playlists WHERE user_id=?", (uid,)).fetchone()
    extra = db.execute("SELECT wear, anon FROM users WHERE id=?", (uid,)).fetchone()
    bpath = backup_path(uid)
    out = {
        "telegram_id": uid,
        "in_libre_since": user[1] if user else None,
        "profile_views": user[2] if user else 0,
        "banner": (f"{PUBLIC_URL}/media/b/{user[3]}" if user and user[3] else None),
        "sessions": db.execute("SELECT COUNT(*) FROM tokens WHERE user_id=?", (uid,)).fetchone()[0],
        "wall_settings": {"who": walls[0], "hidden": bool(walls[1])} if walls else {"who": 0, "hidden": False},
        "wall_blocked": [r[0] for r in q("SELECT user FROM wall_blocked WHERE owner=?", uid)],
        "my_posts": [{"id": r[0], "wall": r[1], "text": r[2], "photo": photo_url(r[3]), "date": r[4]}
                     for r in q("SELECT id, owner, text, photo, created FROM posts WHERE author=? ORDER BY id", uid)],
        "my_comments": [{"id": r[0], "post": r[1], "text": r[2], "date": r[3]}
                        for r in q("SELECT id, post, text, created FROM comments WHERE author=? ORDER BY id", uid)],
        "posts_i_liked": [r[0] for r in q("SELECT post FROM likes WHERE user=?", uid)],
        "posts_on_my_wall": db.execute("SELECT COUNT(*) FROM posts WHERE owner=?", (uid,)).fetchone()[0],
        "my_reports": [r[0] for r in q("SELECT post FROM reports WHERE reporter=?", uid)],
        "clan": clan_brief(uid),
        "showcase_nft": json.loads(sc[0]) if sc else [],
        "nft_numbers": [{"gift": r[0], "num": r[1]} for r in q("SELECT gift_id, num FROM nft_numbers WHERE owner=?", uid)],
        "public_playlist": {"name": pl[0], "tracks": pl[1]} if pl else None,
        "worn_gift": json.loads(extra[0]) if extra and extra[0] else None,
        "anonymous_number": extra[1] if extra else None,
        "gifts_waiting_for_me": db.execute("SELECT COUNT(*) FROM gifts WHERE to_id=?", (uid,)).fetchone()[0],
        "gift_cards": [{"id": r[0], "from": r[1], "to": r[2], "date": r[3]}
                       for r in q("SELECT id, from_id, to_id, created FROM cards WHERE from_id=? OR to_id=?", uid, uid)],
        "cloud_copy": {"size": os.path.getsize(bpath), "date": int(os.path.getmtime(bpath))} if os.path.exists(bpath) else None,
        "not_stored": ["переписка", "контакты", "номер телефона", "юзернейм", "IP-адреса и журналы запросов",
                       "кто смотрел ваш профиль"],
    }
    return web.json_response(out)


TOGETHER_TTL = 45
_together: dict = {}


def together_key(request):
    try:
        return int(request.match_info["host"]), int(request.match_info["sid"])
    except (KeyError, ValueError):
        return None


def together_fresh(key):
    room = _together.get(key)
    if not room:
        return {}
    cutoff = now() - TOGETHER_TTL
    for uid in [u for u, t in room.items() if t < cutoff]:
        room.pop(uid, None)
    if not room:
        _together.pop(key, None)
    return room


@need_user
async def together_here(request: web.Request):
    uid = request["uid"]
    key = together_key(request)
    if key is None or key[0] == uid:
        return web.json_response({"error": "bad request"}, status=400)
    if len(_together) > 20000 and key not in _together:
        return web.json_response({"error": "busy"}, status=503)
    room = together_fresh(key)
    room[uid] = now()
    _together[key] = room
    return web.json_response({"listeners": [u for u in room if u != uid]})


@need_user
async def together_leave(request: web.Request):
    key = together_key(request)
    room = _together.get(key) if key else None
    if room:
        room.pop(request["uid"], None)
        if not room:
            _together.pop(key, None)
    return web.json_response({"ok": True})


@need_user
async def together_room(request: web.Request):
    uid = request["uid"]
    key = together_key(request)
    if key is None:
        return web.json_response({"error": "bad request"}, status=400)
    room = together_fresh(key)
    if uid != key[0] and uid not in room:
        return web.json_response({"error": "not allowed"}, status=403)
    return web.json_response({"listeners": [u for u in room if u != uid]})


TG_API = os.environ.get("TG_API", "https://api.telegram.org").rstrip("/")
MAX_FWD_TEXT = 3500
_tg_session = None


async def tg_send(chat_id, text):
    global _tg_session
    import aiohttp
    if _tg_session is None or _tg_session.closed:
        _tg_session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=20))
    try:
        async with _tg_session.post(f"{TG_API}/bot{BOT_TOKEN}/sendMessage",
                                    json={"chat_id": chat_id, "text": text, "disable_web_page_preview": True}) as r:
            data = await r.json(content_type=None)
            return r.status, data
    except Exception as e:
        log.warning("tg send: %s", type(e).__name__)
        return -1, {}


@need_user
async def max_forward(request: web.Request):
    uid = request["uid"]
    if limited(("maxfwd", uid), 60):
        return web.json_response({"error": "slow down"}, status=429)
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "bad request"}, status=400)
    chat = clean(body.get("chat"), 120)
    sender = clean(body.get("from"), 120)
    text = clean(body.get("text"), MAX_FWD_TEXT)
    if not text:
        return web.json_response({"error": "empty"}, status=400)
    head = "MAX · " + (chat or sender or "сообщение")
    line = f"{sender}: {text}" if sender and sender != chat else text
    status, data = await tg_send(uid, head + "\n" + line)
    if status == 200 and data.get("ok"):
        return web.json_response({"ok": True})
    if status == 403 or status == 400 and "chat not found" in str(data.get("description", "")):
        return web.json_response({"error": "start bot"}, status=409)
    return web.json_response({"error": "telegram"}, status=502)


MUSIC_CHANNEL = os.environ.get("MUSIC_CHANNEL", "").strip()


async def tg_call(method, **params):
    global _tg_session
    import aiohttp
    if _tg_session is None or _tg_session.closed:
        _tg_session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=70))
    try:
        async with _tg_session.post(f"{TG_API}/bot{BOT_TOKEN}/{method}", json=params) as r:
            data = await r.json(content_type=None)
            return r.status, data
    except Exception as e:
        log.warning("tg %s: %s", method, type(e).__name__)
        return -1, {}


async def relay_track(msg):
    sender = (msg.get("from") or {}).get("id")
    audio = msg.get("audio") or msg.get("document")
    if not sender or not audio or not audio.get("file_size"):
        return
    wait = db.execute("SELECT doc FROM relay_wait WHERE user=? AND size=?", (sender, audio["file_size"])).fetchone()
    if not wait:
        return
    doc = wait[0]
    method, field = ("sendAudio", "audio") if msg.get("audio") else ("sendDocument", "document")
    status, data = await tg_call(method, chat_id=MUSIC_CHANNEL, disable_notification=True, **{field: audio["file_id"]})
    posted = data.get("result") if status == 200 and data.get("ok") else None
    username = ((posted or {}).get("chat") or {}).get("username")
    if posted and username:
        db.execute("INSERT OR REPLACE INTO relay(doc, src, created) VALUES(?, ?, ?)", (doc, f"{username}/{posted['message_id']}", now()))
        db.execute("DELETE FROM relay_wait WHERE doc=?", (doc,))
    else:
        log.warning("relay: can't post to %s (%s)", MUSIC_CHANNEL, data.get("description"))
    await tg_call("deleteMessage", chat_id=msg["chat"]["id"], message_id=msg["message_id"])


async def bot_updates(app):
    if not MUSIC_CHANNEL:
        return

    async def loop():
        offset = 0
        while True:
            status, data = await tg_call("getUpdates", offset=offset, timeout=50, allowed_updates=["message"])
            if status == 409:
                log.warning("relay: the bot has a webhook or another reader - private tracks can't be relayed")
                await asyncio.sleep(600)
                continue
            if status != 200 or not data.get("ok"):
                await asyncio.sleep(5)
                continue
            for upd in data.get("result", []):
                offset = max(offset, upd["update_id"] + 1)
                msg = upd.get("message")
                if msg and (msg.get("chat") or {}).get("type") == "private":
                    try:
                        await relay_track(msg)
                    except Exception as e:
                        log.warning("relay: %s", e)
    app["bot"] = asyncio.create_task(loop())


APP_PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Libre</title>
<style>body{margin:0;font:16px/1.5 system-ui,sans-serif;background:#161618;color:#f2f0eb;display:grid;place-items:center;min-height:100vh}
main{max-width:340px;padding:24px}h1{font-size:22px;margin:0 0 8px}p{color:#8d8b91;margin:0}</style></head>
<body><main><h1>Libre</h1><p>Этот бот обслуживает профили в приложении Libre: баннеры, просмотры и стены. Писать ему ничего не нужно.</p></main></body></html>"""


PRIVACY_PAGE = """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Libre — конфиденциальность</title>
<style>body{margin:0;font:16px/1.6 system-ui,sans-serif;background:#161618;color:#f2f0eb}
main{max-width:640px;margin:0 auto;padding:32px 20px}h1{font-size:24px}h2{font-size:18px;margin-top:28px}
p,li{color:#c9c7cc}code{background:#222225;padding:1px 6px;border-radius:6px}</style></head><body><main>
<h1>Политика конфиденциальности Libre Cloud</h1>
<p>Libre Cloud — небольшой сервер, через который пользователи приложения Libre видят баннеры, стены, дату появления в Libre и число просмотров профилей друг друга.</p>
<h2>Что хранится</h2>
<ul>
<li>ваш числовой ID в Telegram;</li>
<li>дата, когда вы впервые вошли в Libre Cloud;</li>
<li>число просмотров вашего профиля (только число);</li>
<li>баннер профиля, если вы его установили;</li>
<li>записи и комментарии на стенах, которые вы сами написали (с вашим именем на момент записи), фото к ним и лайки;</li>
<li>настройки вашей стены: кто может писать и кому вы это запретили;</li>
<li>NFT, которые вы улучшили в Libre (их вид и номер), — чтобы другие видели их в вашем профиле, и вид подарков, которые вы передали друзьям (для карточки в чате, хранится год);</li>
<li>ваш клан: название, тег, цвет и участники;</li>
<li>плейлист, который вы сами открыли в профиле;</li>
<li>облачная копия Libre (настройки и плейлисты), только если вы её сделали; прочитать её можете только вы.</li>
</ul>
<p>Во время «Слушать вместе» сервер держит в оперативной памяти, кто сейчас подключён к эфиру, чтобы ведущий видел слушателей. На диск это не пишется и забывается через 45 секунд после выхода.</p>
<h2>Что не хранится и не передаётся</h2>
<ul>
<li>переписка, контакты, номер телефона и юзернейм;</li>
<li>список тех, кто смотрел ваш профиль: для подсчёта хранится только необратимый хеш, и только двое суток;</li>
<li>журналы запросов и IP-адреса — они отключены.</li>
<li>какую музыку вы слушаете. Запросы клипов, текстов и курсов валют идут без входа, а в кэше лежит только сам клип или текст, без связи с человеком.</li>
</ul>
<h2>Как удалить данные</h2>
<p>Посмотреть всё, что хранится о вас: хаб Libre → «Что сервер знает обо мне».</p>
<p>В Libre: хаб Libre → «Сервер Libre» → «Удалить мои данные». Сервер сразу и полностью удалит запись о вас, баннер, вашу стену, все ваши записи, комментарии и лайки.</p>
<h2>Вход</h2>
<p>Пароли не используются: Telegram подписывает данные мини-приложения бота Libre, сервер проверяет подпись. Сообщения боту отправлять не нужно.</p>
</main></body></html>"""


async def privacy_page(request):
    return web.Response(text=PRIVACY_PAGE, content_type="text/html")


async def app_page(request):
    return web.Response(text=APP_PAGE, content_type="text/html")


async def update_info(request):
    try:
        with open(os.path.join(DATA, "media", "app", "latest.json"), encoding="utf-8") as f:
            info = json.load(f)
    except (OSError, ValueError):
        info = {}
    return web.json_response(info, headers={"Cache-Control": "no-store"})


async def health(request):
    return web.json_response({"ok": True, "users": db.execute("SELECT COUNT(*) FROM users").fetchone()[0]})


UPSTREAM_TIMEOUT = 12
HIT_TTL = 45 * 86400
MISS_TTL = 86400
_side_session = None
_side_locks: dict = {}
_rates = {"at": 0, "body": None}

db.executescript("""
CREATE TABLE IF NOT EXISTS clip_cache (
    key     TEXT PRIMARY KEY,
    file    TEXT,
    exact   INTEGER NOT NULL DEFAULT 0,
    created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS lyrics_cache (
    key     TEXT PRIMARY KEY,
    body    TEXT,
    created INTEGER NOT NULL
);
""")


def side_session():
    global _side_session
    import aiohttp
    if _side_session is None or _side_session.closed:
        _side_session = aiohttp.ClientSession(
            timeout=aiohttp.ClientTimeout(total=UPSTREAM_TIMEOUT),
            headers={"User-Agent": "Libre/1 (+https://github.com/dimkiy/Libre-Telegram)"})
    return _side_session


def side_lock(key):
    lock = _side_locks.get(key)
    if lock is None:
        if len(_side_locks) > 2000:
            _side_locks.clear()
        lock = _side_locks[key] = asyncio.Lock()
    return lock


def hit_key(request, scope):
    ip = request.headers.get("X-Real-IP", request.remote or "")
    return scope, hashlib.sha256(VIEW_SALT + ip.encode()).hexdigest()[:16]


def track_key(artist, title):
    return sha(squash(artist) + "|" + squash(title))


def squash(text):
    return re.sub(r"[\W_]+", "", (text or "").lower(), flags=re.UNICODE)


def track_args(request):
    artist = (request.query.get("artist") or "").strip()[:160]
    title = (request.query.get("title") or "").strip()[:200]
    return artist, title


async def side_json(url, params):
    try:
        async with side_session().get(url, params=params) as r:
            if r.status != 200:
                return None
            return await r.json(content_type=None)
    except Exception as e:
        log.warning("side request: %s", type(e).__name__)
        return None


def pick_clip(rows, artist, title):
    want_title, want_artist = squash(title), squash(artist)
    fallback = None
    for row in rows or []:
        preview = row.get("previewUrl") or ""
        if not preview:
            continue
        has_artist = squash(row.get("artistName"))
        if not has_artist or not (want_artist in has_artist or has_artist in want_artist):
            continue
        has_title = squash(row.get("trackName"))
        if has_title.startswith(want_title) or (len(has_title) > 3 and want_title.startswith(has_title)):
            return preview, True
        fallback = fallback or preview
    return (fallback, False) if fallback else (None, False)


async def find_clip(artist, title):
    base = "https://itunes.apple.com/search"
    data = await side_json(base, {"entity": "musicVideo", "limit": 12, "term": f"{artist} {title}"})
    url, exact = pick_clip((data or {}).get("results"), artist, title)
    if url and exact:
        return url, True
    data = await side_json(base, {"entity": "musicVideo", "limit": 12, "attribute": "artistTerm", "term": artist})
    more, more_exact = pick_clip((data or {}).get("results"), artist, title)
    return (more, more_exact) if more else (url, exact)


async def save_clip(url):
    import aiohttp
    name = sha(url)[:32] + ".m4v"
    path = os.path.join(CLIPS, name)
    if os.path.exists(path):
        return name
    part = path + ".part"
    total = 0
    try:
        async with side_session().get(url, timeout=aiohttp.ClientTimeout(total=45)) as r:
            if r.status != 200:
                return None
            with open(part, "wb") as f:
                async for chunk in r.content.iter_chunked(64 * 1024):
                    total += len(chunk)
                    if total > CLIP_MAX:
                        raise ValueError("too big")
                    f.write(chunk)
        os.replace(part, path)
        return name
    except Exception as e:
        log.warning("clip save: %s", type(e).__name__)
        try:
            os.remove(part)
        except OSError:
            pass
        return None


def clip_reply(name, exact):
    if not name:
        return web.json_response({"url": ""})
    return web.json_response({"url": f"{PUBLIC_URL}/media/c/{name}", "exact": bool(exact)})


async def get_clip(request: web.Request):
    artist, title = track_args(request)
    if not artist or not title:
        return web.json_response({"url": ""})
    if limited(hit_key(request, "clip"), 40):
        return web.json_response({"error": "slow down"}, status=429)
    key = track_key(artist, title)
    async with side_lock("c" + key):
        row = db.execute("SELECT file, exact, created FROM clip_cache WHERE key=?", (key,)).fetchone()
        if row:
            alive = row[0] and os.path.exists(os.path.join(CLIPS, row[0]))
            if alive and now() - row[2] < HIT_TTL:
                return clip_reply(row[0], row[1])
            if not row[0] and now() - row[2] < MISS_TTL:
                return clip_reply(None, 0)
        url, exact = await find_clip(artist, title)
        name = await save_clip(url) if url else None
        db.execute("INSERT OR REPLACE INTO clip_cache(key, file, exact, created) VALUES(?,?,?,?)",
                   (key, name, 1 if exact else 0, now()))
        return clip_reply(name, exact)


async def find_lyrics(artist, title, duration):
    base = "https://lrclib.net/api"
    params = {"track_name": title, "artist_name": artist}
    if duration > 0:
        params["duration"] = duration
    data = await side_json(f"{base}/get", params)
    if isinstance(data, dict) and (data.get("syncedLyrics") or data.get("plainLyrics") or data.get("instrumental")):
        return data
    rows = await side_json(f"{base}/search", {"q": f"{title} {artist}"})
    for row in rows if isinstance(rows, list) else []:
        if row.get("syncedLyrics") or row.get("plainLyrics") or row.get("instrumental"):
            return row
    return None


async def get_lyrics(request: web.Request):
    artist, title = track_args(request)
    if not title:
        return web.json_response({})
    if limited(hit_key(request, "lyrics"), 60):
        return web.json_response({"error": "slow down"}, status=429)
    duration = int_or0(request.query.get("dur"))
    key = track_key(artist, title)
    async with side_lock("l" + key):
        row = db.execute("SELECT body, created FROM lyrics_cache WHERE key=?", (key,)).fetchone()
        if row and now() - row[1] < (HIT_TTL if row[0] else MISS_TTL):
            return web.json_response(json.loads(row[0]) if row[0] else {})
        data = await find_lyrics(artist, title, duration)
        body = None
        if data:
            body = json.dumps({"syncedLyrics": data.get("syncedLyrics"), "plainLyrics": data.get("plainLyrics"),
                               "instrumental": bool(data.get("instrumental"))}, ensure_ascii=False)
        db.execute("INSERT OR REPLACE INTO lyrics_cache(key, body, created) VALUES(?,?,?)", (key, body, now()))
        return web.json_response(json.loads(body) if body else {})


async def get_rates(request: web.Request):
    if limited(hit_key(request, "rates"), 30):
        return web.json_response({"error": "slow down"}, status=429)
    if not _rates["body"] or now() - _rates["at"] > 6 * 3600:
        data = await side_json("https://open.er-api.com/v6/latest/USD", {})
        if isinstance(data, dict) and isinstance(data.get("rates"), dict):
            _rates["body"] = {"base": "USD", "rates": data["rates"]}
            _rates["at"] = now()
    return web.json_response(_rates["body"] or {"rates": {}}, headers={"Cache-Control": "public, max-age=3600"})


def trim_clips():
    stamp = now()
    db.execute("DELETE FROM lyrics_cache WHERE created < ?", (stamp - HIT_TTL,))
    db.execute("DELETE FROM clip_cache WHERE created < ?", (stamp - HIT_TTL,))
    keep = {r[0] for r in db.execute("SELECT file FROM clip_cache WHERE file IS NOT NULL")}
    files = []
    for name in os.listdir(CLIPS):
        path = os.path.join(CLIPS, name)
        if name not in keep and not name.endswith(".part"):
            os.remove(path)
        elif name in keep:
            info = os.stat(path)
            files.append((info.st_mtime, info.st_size, name, path))
    total = sum(f[1] for f in files)
    for _, size, name, path in sorted(files):
        if total <= CLIPS_TOTAL:
            break
        os.remove(path)
        db.execute("DELETE FROM clip_cache WHERE file=?", (name,))
        total -= size


async def housekeeping(app):
    async def loop():
        while True:
            try:
                day = now() // 86400
                db.execute("DELETE FROM seen WHERE day < ?", (day - 1,))
                db.execute("DELETE FROM tokens WHERE created < ?", (now() - TOKEN_TTL,))
                db.execute("DELETE FROM gifts WHERE created < ?", (now() - 60 * 86400,))
                db.execute("DELETE FROM cards WHERE created < ?", (now() - 365 * 86400,))
                db.execute("DELETE FROM relay_wait WHERE created < ?", (now() - 86400,))
                drop_orphan_photos()
                trim_clips()
                for key in list(_together):
                    together_fresh(key)
            except Exception as e:
                log.warning("housekeeping: %s", e)
            await asyncio.sleep(3600)
    app["hk"] = asyncio.create_task(loop())


def main():
    app = web.Application(client_max_size=MAX_BANNER + 1024 * 1024)
    app.router.add_post("/v1/auth", auth)
    app.router.add_get("/v1/profile/{id}", get_profile)
    app.router.add_post("/v1/view/{id}", view)
    app.router.add_put("/v1/banner", put_banner)
    app.router.add_delete("/v1/banner", delete_banner)
    app.router.add_delete("/v1/me", delete_me)
    app.router.add_post("/v1/gift/send", send_gift)
    app.router.add_get("/v1/gift/inbox", gift_inbox)
    app.router.add_post("/v1/gift/ack/{id}", gift_ack)
    app.router.add_get("/v1/card/{id}", get_card)
    app.router.add_post("/v1/clan", create_clan)
    app.router.add_get("/v1/clan/my", my_clan)
    app.router.add_get("/v1/clan/code/{code}", clan_preview)
    app.router.add_post("/v1/clan/join", clan_join)
    app.router.add_post("/v1/clan/leave", clan_leave)
    app.router.add_post("/v1/clan/hello", clan_hello)
    app.router.add_put("/v1/achievements", put_achievements)
    app.router.add_put("/v1/status", put_status)
    app.router.add_post("/v1/presence", presence)
    app.router.add_put("/v1/wear", put_wear)
    app.router.add_put("/v1/anon", put_anon)
    app.router.add_post("/v1/max/forward", max_forward)
    app.router.add_get("/v1/clans/top", clans_top)
    app.router.add_get("/v1/clan/{id}", get_clan)
    app.router.add_post("/v1/clan/{id}/invite", clan_invite)
    app.router.add_post("/v1/clan/{id}/kick", clan_kick)
    app.router.add_post("/v1/clan/{id}/role", clan_set_role)
    app.router.add_post("/v1/clan/{id}/edit", clan_edit)
    app.router.add_delete("/v1/clan/{id}", clan_disband)
    app.router.add_get("/c/{code}", invite_page)
    app.router.add_put("/v1/playlist", put_playlist)
    app.router.add_delete("/v1/playlist", delete_playlist)
    app.router.add_get("/v1/playlist/{id}", get_playlist)
    app.router.add_put("/v1/backup", put_backup)
    app.router.add_get("/v1/backup", get_backup)
    app.router.add_delete("/v1/backup", delete_backup)
    app.router.add_get("/v1/me/export", export_me)
    app.router.add_put("/v1/showcase", put_showcase)
    app.router.add_get("/v1/showcase/{id}", get_showcase)
    app.router.add_get("/v1/wall/{owner}", wall)
    app.router.add_post("/v1/wall/{owner}", add_post)
    app.router.add_put("/v1/wall-photo", put_wall_photo)
    app.router.add_get("/v1/post/{id}", get_post_full)
    app.router.add_delete("/v1/post/{id}", delete_post)
    app.router.add_post("/v1/post/{id}/like", like_post)
    app.router.add_post("/v1/post/{id}/comments", add_comment)
    app.router.add_post("/v1/post/{id}/report", report_post)
    app.router.add_delete("/v1/comment/{id}", delete_comment)
    app.router.add_put("/v1/wall-settings", wall_settings)
    app.router.add_post("/v1/wall-block", wall_block)
    app.router.add_post("/v1/together/{host}/{sid}/here", together_here)
    app.router.add_delete("/v1/together/{host}/{sid}/here", together_leave)
    app.router.add_get("/v1/together/{host}/{sid}", together_room)
    app.router.add_get("/app", app_page)
    app.router.add_get("/privacy", privacy_page)
    app.router.add_get("/health", health)
    app.router.add_get("/v1/update", update_info)
    app.router.add_get("/v1/clip", get_clip)
    app.router.add_get("/v1/lyrics", get_lyrics)
    app.router.add_get("/v1/rates", get_rates)
    app.on_startup.append(housekeeping)
    app.on_startup.append(bot_updates)
    log.info("Libre Cloud on 127.0.0.1:%s, public %s", PORT, PUBLIC_URL)
    web.run_app(app, host="127.0.0.1", port=PORT, access_log=None, print=None)


if __name__ == "__main__":
    main()
