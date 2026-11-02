package org.telegram.messenger;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/*
 * Libre — что это за файл:
 * Антиудаление: сообщения, которые удалил собеседник, остаются в чате с пометкой «Удалено». Свои удаления не держим.
 */

/**
 * Prizma: remembers which messages the user deleted HIMSELF, so anti-delete only keeps
 * messages removed by someone else. Without it, your own deletions stayed on screen marked
 * "Удалено" with the toast "Собеседник удалил сообщение".
 *
 * Keys are "channelId:messageId" (private chats and basic groups share one id sequence per
 * account, channels have their own). Entries expire after a minute.
 */
public final class PrizmaAntiDelete {

    private static final long TTL_MS = 60_000L;
    private static final HashMap<String, Long> selfDeleted = new HashMap<>();

    private PrizmaAntiDelete() {}

    /** Called from MessagesController.deleteMessages — the single entry point of local deletions. */
    public static void markSelfDeleted(ArrayList<Integer> ids, long channelId) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (selfDeleted) {
            cleanup(now);
            for (int i = 0; i < ids.size(); i++) {
                Integer id = ids.get(i);
                if (id != null) {
                    selfDeleted.put(channelId + ":" + id, now);
                }
            }
        }
    }

    public static boolean isSelfDeleted(int id, long channelId) {
        synchronized (selfDeleted) {
            Long at = selfDeleted.get(channelId + ":" + id);
            return at != null && SystemClock.elapsedRealtime() - at < TTL_MS;
        }
    }

    // ===================== kept for good =====================
    // Up to 1.1.5 anti-delete only worked while the chat stayed open: the update had already removed
    // the message from the database, so after re-entering the chat it was gone. Now someone else's
    // deletions never reach the database — the message stays there and is listed here, and the chat
    // marks it «Удалено» every time it loads.

    private static final int MAX_KEPT = 5000;
    private static final java.util.HashMap<Integer, java.util.LinkedHashSet<String>> kept = new java.util.HashMap<>();

    private static android.content.SharedPreferences store(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_deleted_" + account, android.content.Context.MODE_PRIVATE);
    }

    private static java.util.LinkedHashSet<String> keptSet(int account) {
        java.util.LinkedHashSet<String> s = kept.get(account);
        if (s == null) {
            s = new java.util.LinkedHashSet<>();
            String csv = store(account).getString("ids", "");
            if (!csv.isEmpty()) java.util.Collections.addAll(s, csv.split(","));
            kept.put(account, s);
        }
        return s;
    }

    /**
     * MessagesController, before a deletion from someone else reaches the database: with anti-delete
     * on, returns only the ids that really go (our own deletions) and keeps the rest.
     * {@code channelId} is positive for channels/supergroups and 0 for chats and private messages.
     */
    public static ArrayList<Integer> filterRemoteDeletion(int account, long channelId, ArrayList<Integer> ids) {
        if (ids == null || ids.isEmpty() || !PrizmaConfig.antiDelete()) return ids;
        ArrayList<Integer> go = new ArrayList<>();
        synchronized (kept) {
            java.util.LinkedHashSet<String> s = keptSet(account);
            boolean changed = false;
            for (Integer id : ids) {
                if (id == null) continue;
                if (isSelfDeleted(id, channelId)) {
                    go.add(id);
                } else if (s.add(channelId + ":" + id)) {
                    changed = true;
                }
            }
            if (changed) {
                while (s.size() > MAX_KEPT) {
                    Iterator<String> it = s.iterator();
                    it.next();
                    it.remove();
                }
                store(account).edit().putString("ids", android.text.TextUtils.join(",", s)).apply();
            }
        }
        return go;
    }

    public static boolean isKept(int account, MessageObject mo) {
        if (mo == null || mo.messageOwner == null || mo.getId() <= 0) return false;
        long channelId = mo.messageOwner.peer_id != null ? mo.messageOwner.peer_id.channel_id : 0;
        synchronized (kept) {
            return keptSet(account).contains(channelId + ":" + mo.getId());
        }
    }

    /** Red trash icon + «Удалено» under the text (or caption). Returns true when it marked something. */
    public static boolean markDeleted(MessageObject m) {
        if (m == null || m.prizmaDeleted) return false;
        m.prizmaDeleted = true;
        try {
            android.text.SpannableStringBuilder mark = new android.text.SpannableStringBuilder("\n\n");
            try {
                android.graphics.drawable.Drawable trash = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.msg_delete).mutate();
                trash.setColorFilter(0xffE8534B, android.graphics.PorterDuff.Mode.SRC_IN);
                int sz = AndroidUtilities.dp(15);
                trash.setBounds(0, 0, sz, sz);
                mark.append(" ");
                mark.setSpan(new android.text.style.ImageSpan(trash, android.text.style.ImageSpan.ALIGN_BOTTOM),
                        mark.length() - 1, mark.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Throwable ignore) {
            }
            int markStart = mark.length();
            mark.append(" Удалено");
            mark.setSpan(new android.text.style.ForegroundColorSpan(0xffE8534B), markStart, mark.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (m.type == MessageObject.TYPE_TEXT || m.type == MessageObject.TYPE_EMOJIS) {
                CharSequence cur = m.messageText == null ? "" : m.messageText;
                m.messageText = new android.text.SpannableStringBuilder(cur).append(mark);
                m.generateLayout(null);
            } else if (m.caption != null && m.caption.length() > 0) {
                m.caption = new android.text.SpannableStringBuilder(m.caption).append(mark);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void cleanup(long now) {
        Iterator<Map.Entry<String, Long>> it = selfDeleted.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > TTL_MS) {
                it.remove();
            }
        }
    }
}
