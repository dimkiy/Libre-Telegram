package org.telegram.ui.Components;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/*
 * Libre — что это за файл:
 * Карточка Libre-подарка в чате (когда его передали другу).
 */

/**
 * Prizma: a handed-over Libre NFT shows up in the chat as Telegram's own gift card.
 *
 * The chat message is plain text for everyone ("🎁 Дарю тебе…"); at its end, in zero-width
 * characters, sits the number of a "card" on Libre Cloud — the gift's look (model, backdrop,
 * pattern, number). Libre on both sides turns that message into a TL_messageActionStarGiftUnique
 * service message in memory only — the database keeps the text — so the chat draws the very same
 * card Telegram draws for a real NFT transfer.
 */
public final class PrizmaGiftCard {

    private static final String PROTO = "LG1;";
    private static final char BIT0 = '‌', BIT1 = '‍';

    private PrizmaGiftCard() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_cards", Context.MODE_PRIVATE);
    }

    // ===================== the hidden number =====================

    /** Zero-width tail carrying the card number. */
    public static String hide(long cardId) {
        byte[] bytes = (PROTO + cardId).getBytes(StandardCharsets.US_ASCII);
        StringBuilder sb = new StringBuilder(bytes.length * 8);
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) sb.append(((b >> i) & 1) == 1 ? BIT1 : BIT0);
        }
        return sb.toString();
    }

    static long cardIdOf(String text) {
        if (text == null) return 0;
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == BIT0 || c == BIT1) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                start = -1; // only a run that reaches the end counts
            }
        }
        if (start < 0) return 0;
        int bits = text.length() - start;
        if (bits % 8 != 0 || bits > 8 * 32) return 0;
        byte[] out = new byte[bits / 8];
        for (int i = 0; i < out.length; i++) {
            int v = 0;
            for (int k = 0; k < 8; k++) v = (v << 1) | (text.charAt(start + i * 8 + k) == BIT1 ? 1 : 0);
            out[i] = (byte) v;
        }
        String s = new String(out, StandardCharsets.US_ASCII);
        if (!s.startsWith(PROTO)) return 0;
        try {
            return Long.parseLong(s.substring(PROTO.length()));
        } catch (Throwable e) {
            return 0;
        }
    }

    // ===================== card data =====================

    /** The sender (or the inbox) already knows the look — no need to ask the server later. */
    public static void remember(long cardId, JSONObject gift) {
        if (cardId <= 0 || gift == null) return;
        prefs().edit().putString("c" + cardId, gift.toString()).apply();
    }

    private static final HashSet<Long> loading = new HashSet<>();
    private static final HashSet<Long> failed = new HashSet<>();
    private static final HashMap<Long, ArrayList<TLRPC.Message>> waiting = new HashMap<>();

    private static void request(int account, long cardId, TLRPC.Message m) {
        AndroidUtilities.runOnUIThread(() -> {
            if (failed.contains(cardId) || !PrizmaCloud.configured()) return;
            ArrayList<TLRPC.Message> list = waiting.get(cardId);
            if (list == null) waiting.put(cardId, list = new ArrayList<>());
            list.add(m);
            if (!loading.add(cardId)) return;
            PrizmaCloud.call(account, "GET", "/v1/card/" + cardId, null, r -> {
                loading.remove(cardId);
                ArrayList<TLRPC.Message> msgs = waiting.remove(cardId);
                if (r.code != 200 || r.body == null) {
                    if (r.code == 404) failed.add(cardId); // not ours to see, or expired — stay a text message
                    return;
                }
                try {
                    remember(cardId, new JSONObject(r.body).getJSONObject("gift"));
                } catch (Throwable e) {
                    failed.add(cardId);
                    return;
                }
                if (msgs == null) return;
                // the chat swaps the text bubble for the card
                HashMap<Long, ArrayList<MessageObject>> byDialog = new HashMap<>();
                for (TLRPC.Message msg : msgs) {
                    long did = MessageObject.getDialogId(msg);
                    ArrayList<MessageObject> l = byDialog.get(did);
                    if (l == null) byDialog.put(did, l = new ArrayList<>());
                    l.add(new MessageObject(account, msg, true, true));
                }
                for (java.util.Map.Entry<Long, ArrayList<MessageObject>> e : byDialog.entrySet()) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.replaceMessagesObjects, e.getKey(), e.getValue());
                }
            });
        });
    }

    // ===================== our own message, once it's sent =====================

    private static final HashSet<Integer> watching = new HashSet<>();

    /**
     * While our message is still sending it stays text (its id isn't final yet); as soon as the
     * server takes it, swap it for the card.
     */
    public static void watchSent(int account) {
        if (!watching.add(account)) return;
        NotificationCenter.getInstance(account).addObserver((id, acc, args) -> {
            if (args.length < 3 || !(args[2] instanceof TLRPC.Message)) return;
            final TLRPC.Message msg = (TLRPC.Message) args[2];
            if (cardIdOf(msg.message) <= 0) return;
            AndroidUtilities.runOnUIThread(() -> {
                ArrayList<MessageObject> list = new ArrayList<>();
                list.add(new MessageObject(acc, msg, true, true));
                NotificationCenter.getInstance(acc).postNotificationName(NotificationCenter.replaceMessagesObjects, MessageObject.getDialogId(msg), list);
            }, 100);
        }, NotificationCenter.messageReceivedByServer);
    }

    // ===================== text → card =====================

    /**
     * MessageObject's constructor: a Libre gift message becomes a gift-card service message.
     * Anything else (and anything still sending) comes back untouched. Any thread.
     */
    public static TLRPC.Message convert(int account, TLRPC.Message m) {
        if (!(m instanceof TLRPC.TL_message) || m.id <= 0 || m.message == null || m.message.length() < 40) return m;
        char last = m.message.charAt(m.message.length() - 1);
        if (last != BIT0 && last != BIT1) return m;
        long cardId = cardIdOf(m.message);
        if (cardId <= 0) return m;
        String json = prefs().getString("c" + cardId, null);
        if (json == null) {
            request(account, cardId, m);
            return m;
        }
        try {
            JSONObject g = new JSONObject(json);
            long giftId = g.optLong("giftId");
            TL_stars.StarGift base = PrizmaGifts.find(giftId);
            long lookId = -1_000_000_000_000_000L - cardId; // its own id space, never one of ours
            int[] nt = PrizmaGifts.parseNumber(g.optString("num", ""), lookId, base != null ? base : new TL_stars.TL_starGift());
            TL_stars.StarGift look = PrizmaGifts.buildUnique(base, lookId, PrizmaGifts.decodeAttrs(g.optString("attr", "")),
                    nt, g.optInt("style", 0), g.optBoolean("crafted", false), g.optString("title", ""));
            if (!(look instanceof TL_stars.TL_starGiftUnique)) return m;

            TLRPC.TL_messageActionStarGiftUnique a = new TLRPC.TL_messageActionStarGiftUnique();
            a.transferred = true;
            a.saved = true;
            a.gift = look;
            // "You transferred a gift to **un1**" / "**un1** transferred you a gift!"
            a.from_id = m.out ? m.peer_id : m.from_id;

            TLRPC.TL_messageService s = new TLRPC.TL_messageService();
            s.id = m.id;
            s.local_id = m.local_id;
            s.from_id = m.from_id;
            s.peer_id = m.peer_id;
            s.saved_peer_id = m.saved_peer_id;
            s.date = m.date;
            s.out = m.out;
            s.unread = m.unread;
            s.media_unread = m.media_unread;
            s.silent = m.silent;
            s.flags = m.flags & ~(TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_MARKUP | TLRPC.MESSAGE_FLAG_HAS_ENTITIES);
            s.dialog_id = m.dialog_id;
            s.send_state = m.send_state;
            s.message = m.message; // keeps the marker, so the card can be recognised later
            s.action = a;
            return s;
        } catch (Throwable e) {
            return m;
        }
    }

    /** A card we drew ourselves (the gift exists only in Libre — open it locally, not via Telegram). */
    public static boolean isCard(MessageObject mo) {
        if (mo == null || !(mo.messageOwner instanceof TLRPC.TL_messageService)) return false;
        if (!(mo.messageOwner.action instanceof TLRPC.TL_messageActionStarGiftUnique)) return false;
        TL_stars.StarGift g = ((TLRPC.TL_messageActionStarGiftUnique) mo.messageOwner.action).gift;
        return g instanceof TL_stars.TL_starGiftUnique && g.slug != null && g.slug.startsWith("libre") && cardIdOf(mo.messageOwner.message) > 0;
    }
}
