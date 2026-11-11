package org.telegram.messenger;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

public final class PrizmaHistory {
    public static final int DELETED = 0;
    public static final int EDITED = 1;

    public static final class Entry {
        public int kind;
        public long savedAt;
        public TLRPC.Message message;
    }

    private static final int MAX_ROWS = 20000;
    private static final int TRIM_EVERY = 200;

    private static Store store;
    private static int putsSinceTrim = TRIM_EVERY - 1;

    private PrizmaHistory() {
    }

    private static synchronized SQLiteDatabase db() {
        if (store == null) {
            store = new Store();
        }
        return store.getWritableDatabase();
    }

    public static void saveDeleted(int account, long channelId, ArrayList<Integer> ids) {
        if (!PrizmaConfig.keepHistory() || ids == null || ids.isEmpty()) return;
        final ArrayList<Integer> copy = new ArrayList<>(ids);
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            for (Integer id : copy) {
                if (id == null || id <= 0 || PrizmaAntiDelete.isSelfDeleted(id, channelId)) continue;
                TLRPC.Message m = readCached(account, id, channelId != 0 ? -channelId : 0);
                if (m != null) put(account, m, DELETED);
            }
        });
    }

    public static void saveEdits(int account, long dialogId, ArrayList<MessageObject> edited) {
        if (!PrizmaConfig.keepHistory() || edited == null || edited.isEmpty()) return;
        final ArrayList<TLRPC.Message> fresh = new ArrayList<>();
        for (MessageObject mo : edited) {
            if (mo != null && mo.messageOwner != null) fresh.add(mo.messageOwner);
        }
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            for (TLRPC.Message now : fresh) {
                TLRPC.Message old = readCached(account, now.id, dialogId);
                if (old == null) continue;
                if (TextUtils.equals(old.message, now.message) && sameMedia(old, now)) continue;
                put(account, old, EDITED);
            }
        });
    }

    private static boolean sameMedia(TLRPC.Message a, TLRPC.Message b) {
        if (a.media == null || b.media == null) return a.media == b.media;
        return a.media.getClass() == b.media.getClass();
    }

    static TLRPC.Message readCached(int account, int id, long dialogId) {
        SQLiteCursor cursor = null;
        try {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            String where = dialogId != 0 ? String.format(Locale.US, "mid = %d AND uid = %d", id, dialogId) : String.format(Locale.US, "mid = %d AND is_channel = 0", id);
            cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE " + where + " LIMIT 1");
            if (cursor.next()) {
                NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data != null) {
                    TLRPC.Message m = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (m != null) m.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                    data.reuse();
                    return m;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.dispose();
        }
        return null;
    }

    private static void put(int account, TLRPC.Message m, int kind) {
        try {
            SerializedData out = new SerializedData(m.getObjectSize());
            m.serializeToStream(out);
            ContentValues v = new ContentValues();
            v.put("account", account);
            v.put("dialog", MessageObject.getDialogId(m));
            v.put("mid", m.id);
            v.put("kind", kind);
            v.put("sent", m.date);
            v.put("saved", System.currentTimeMillis());
            v.put("data", out.toByteArray());
            out.cleanup();
            if (kind == DELETED) {
                db().delete("history", "account = ? AND dialog = ? AND mid = ? AND kind = 0",
                        new String[]{String.valueOf(account), String.valueOf(MessageObject.getDialogId(m)), String.valueOf(m.id)});
            }
            db().insert("history", null, v);
            if (++putsSinceTrim >= TRIM_EVERY) {
                putsSinceTrim = 0;
                db().execSQL("DELETE FROM history WHERE rowid IN (SELECT rowid FROM history ORDER BY saved DESC LIMIT -1 OFFSET " + MAX_ROWS + ")");
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static ArrayList<Entry> list(int account, long dialogId) {
        ArrayList<Entry> result = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT kind, saved, data FROM history WHERE account = ? AND dialog = ? ORDER BY saved DESC LIMIT 500",
                new String[]{String.valueOf(account), String.valueOf(dialogId)})) {
            while (c.moveToNext()) {
                byte[] bytes = c.getBlob(2);
                SerializedData in = new SerializedData(bytes);
                TLRPC.Message m = TLRPC.Message.TLdeserialize(in, in.readInt32(false), false);
                in.cleanup();
                if (m == null) continue;
                Entry e = new Entry();
                e.kind = c.getInt(0);
                e.savedAt = c.getLong(1);
                e.message = m;
                result.add(e);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    public static int count(int account, long dialogId) {
        try (Cursor c = db().rawQuery("SELECT COUNT(*) FROM history WHERE account = ? AND dialog = ?",
                new String[]{String.valueOf(account), String.valueOf(dialogId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public static void clear(int account, long dialogId) {
        try {
            db().delete("history", "account = ? AND dialog = ?", new String[]{String.valueOf(account), String.valueOf(dialogId)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static final class Store extends SQLiteOpenHelper {
        Store() {
            super(ApplicationLoader.applicationContext, "libre_history.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE history(account INTEGER, dialog INTEGER, mid INTEGER, kind INTEGER, sent INTEGER, saved INTEGER, data BLOB)");
            db.execSQL("CREATE INDEX history_dialog ON history(account, dialog, saved)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
