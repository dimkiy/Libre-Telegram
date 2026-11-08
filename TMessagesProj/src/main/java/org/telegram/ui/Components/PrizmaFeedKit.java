package org.telegram.ui.Components;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ProfileActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;

public final class PrizmaFeedKit {
    public static final int PINK = 0xfff91880;
    public static final int GREEN = 0xff00ba7c;
    private static final String HEART = "❤";

    private PrizmaFeedKit() {
    }

    public static SharedPreferences store() {
        return ApplicationLoader.applicationContext.getSharedPreferences("libre_feed", Context.MODE_PRIVATE);
    }

    public static HashSet<Long> hidden() {
        HashSet<Long> out = new HashSet<>();
        for (String s : store().getString("hidden", "").split(",")) {
            try {
                if (!s.isEmpty()) out.add(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
            }
        }
        return out;
    }

    public static void hide(long chatId) {
        HashSet<Long> h = hidden();
        h.add(chatId);
        store().edit().putString("hidden", TextUtils.join(",", h)).apply();
    }

    public static void unhideAll() {
        store().edit().remove("hidden").apply();
    }

    public static String key(MessageObject mo) {
        return mo.getDialogId() + ":" + mo.getId();
    }

    private static final android.view.animation.OvershootInterpolator SPRING = new android.view.animation.OvershootInterpolator(2.6f);

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public static void press(View v, float scale) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(scale).scaleY(scale).setDuration(110).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(SPRING).start();
                    break;
            }
            return false;
        });
    }

    public static void press(View v) {
        press(v, 0.9f);
    }

    private static HashSet<String> seenCache;

    private static HashSet<String> seenSet() {
        if (seenCache == null) seenCache = new HashSet<>(store().getStringSet("seen", new HashSet<>()));
        return seenCache;
    }

    public static boolean wasSeen(MessageObject mo) {
        return seenSet().contains(key(mo));
    }

    private static final HashSet<String> pendingSeen = new HashSet<>();
    private static boolean seenScheduled;

    public static void markSeen(MessageObject mo) {
        String k = key(mo);
        if (seenSet().contains(k) || !pendingSeen.add(k)) return;
        if (seenScheduled) return;
        seenScheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            seenScheduled = false;
            HashSet<String> all = seenSet();
            all.addAll(pendingSeen);
            pendingSeen.clear();
            if (all.size() > 3000) {
                ArrayList<String> list = new ArrayList<>(all);
                all.clear();
                all.addAll(list.subList(list.size() - 2000, list.size()));
            }
            store().edit().putStringSet("seen", new HashSet<>(all)).apply();
        }, 2500);
    }

    public static final class Cached {
        public final ArrayList<ArrayList<TLRPC.Message>> groups = new ArrayList<>();
        public final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        public int savedAt;
    }

    private static java.io.File cacheFile(int account, String mode) {
        return new java.io.File(ApplicationLoader.getFilesDirFixed(), "libre_feed_" + account + "_" + Math.abs(mode.hashCode()) + ".bin");
    }

    public static void saveCache(int account, String mode, ArrayList<ArrayList<MessageObject>> groups, ArrayList<TLRPC.Chat> chats) {
        final ArrayList<ArrayList<TLRPC.Message>> copy = new ArrayList<>();
        for (int i = 0; i < Math.min(groups.size(), 80); i++) {
            ArrayList<TLRPC.Message> g = new ArrayList<>();
            for (MessageObject mo : groups.get(i)) g.add(mo.messageOwner);
            copy.add(g);
        }
        final ArrayList<TLRPC.Chat> chatsCopy = new ArrayList<>(chats);
        final int now = (int) (System.currentTimeMillis() / 1000);
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                org.telegram.tgnet.SerializedData data = new org.telegram.tgnet.SerializedData(false);
                data.writeInt32(2);
                data.writeInt32(now);
                data.writeInt32(chatsCopy.size());
                for (TLRPC.Chat c : chatsCopy) c.serializeToStream(data);
                data.writeInt32(copy.size());
                for (ArrayList<TLRPC.Message> g : copy) {
                    data.writeInt32(g.size());
                    for (TLRPC.Message m : g) m.serializeToStream(data);
                }
                byte[] bytes = data.toByteArray();
                data.cleanup();
                java.io.File f = cacheFile(account, mode);
                java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    out.write(bytes);
                }
                if (!tmp.renameTo(f)) {
                    f.delete();
                    tmp.renameTo(f);
                }
            } catch (Throwable ignore) {
            }
        });
    }

    public static void loadCache(int account, String mode, org.telegram.messenger.Utilities.Callback<Cached> done) {
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            Cached out = null;
            try {
                java.io.File f = cacheFile(account, mode);
                if (f.exists() && f.length() > 0 && f.length() < 8 * 1024 * 1024) {
                    byte[] bytes = new byte[(int) f.length()];
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        int read = 0;
                        while (read < bytes.length) {
                            int n = in.read(bytes, read, bytes.length - read);
                            if (n < 0) break;
                            read += n;
                        }
                    }
                    org.telegram.tgnet.SerializedData data = new org.telegram.tgnet.SerializedData(bytes);
                    if (data.readInt32(false) == 2) {
                        Cached c = new Cached();
                        c.savedAt = data.readInt32(false);
                        int chats = data.readInt32(false);
                        for (int i = 0; i < chats; i++) {
                            TLRPC.Chat chat = TLRPC.Chat.TLdeserialize(data, data.readInt32(false), false);
                            if (chat != null) c.chats.add(chat);
                        }
                        int groups = data.readInt32(false);
                        for (int i = 0; i < groups; i++) {
                            int n = data.readInt32(false);
                            ArrayList<TLRPC.Message> g = new ArrayList<>();
                            for (int j = 0; j < n; j++) {
                                TLRPC.Message m = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                if (m != null) g.add(m);
                            }
                            if (!g.isEmpty()) c.groups.add(g);
                        }
                        out = c;
                    }
                    data.cleanup();
                }
            } catch (Throwable ignore) {
                out = null;
            }
            final Cached result = out;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    public static boolean isSaved(MessageObject mo) {
        return store().getStringSet("saved", new HashSet<>()).contains(key(mo));
    }

    public static boolean toggleSaved(BaseFragment fragment, ArrayList<MessageObject> group) {
        MessageObject mo = group.get(0);
        HashSet<String> set = new HashSet<>(store().getStringSet("saved", new HashSet<>()));
        boolean now = !set.contains(key(mo));
        if (now) {
            set.add(key(mo));
            long self = fragment.getUserConfig().getClientUserId();
            fragment.getSendMessagesHelper().sendMessage(new ArrayList<>(group), self, false, false, true, 0, 0);
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.saved_messages, "Пост сохранён в Избранное").show();
        } else {
            set.remove(key(mo));
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.saved_messages, "Закладка снята, копия в Избранном осталась").show();
        }
        store().edit().putStringSet("saved", set).apply();
        return now;
    }

    public static CharSequence text(TLRPC.Message m, Paint paint) {
        if (m == null) return null;
        return text(m.message, m.entities, paint);
    }

    public static boolean foreign(String s) {
        if (s == null || s.length() < 24) return false;
        if (!"ru".equals(LocaleController.getInstance().getCurrentLocale().getLanguage())) return false;
        int cyr = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) continue;
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) cyr++;
            else other++;
        }
        return other >= 20 && cyr * 4 < other;
    }

    public static CharSequence text(String message, ArrayList<TLRPC.MessageEntity> entities, Paint paint) {
        if (TextUtils.isEmpty(message)) return null;
        SpannableStringBuilder sb = new SpannableStringBuilder(message);
        ArrayList<TLRPC.MessageEntity> ents = new ArrayList<>();
        if (entities != null) {
            for (TLRPC.MessageEntity e : entities) {
                if (e instanceof TLRPC.TL_messageEntityCustomEmoji || e instanceof TLRPC.TL_messageEntitySpoiler
                        || e instanceof TLRPC.TL_messageEntityBlockquote || e instanceof TLRPC.TL_messageEntityPre) continue;
                ents.add(e);
            }
        }
        try {
            MessageObject.addEntitiesToText(sb, ents, false, true, false, false);
        } catch (Throwable ignore) {
        }
        return Emoji.replaceEmoji(sb, paint.getFontMetricsInt(), false);
    }

    public static String ago(int date) {
        long diff = System.currentTimeMillis() / 1000 - date;
        if (diff < 60) return "сейчас";
        if (diff < 3600) return (diff / 60) + " мин";
        if (diff < 86400) return (diff / 3600) + " ч";
        if (diff < 7 * 86400) return (diff / 86400) + " д";
        return LocaleController.getInstance().getFormatterDayMonth().format(date * 1000L);
    }

    public static String fullDate(int date) {
        Date d = new Date(date * 1000L);
        String time = LocaleController.getInstance().getFormatterDay().format(d);
        String day = new SimpleDateFormat("d MMM yy", LocaleController.getInstance().getCurrentLocale()).format(d);
        return time + " · " + day;
    }

    public static String count(int n) {
        return n <= 0 ? "" : LocaleController.formatShortNumber(n, null);
    }

    public static int replies(TLRPC.Message m) {
        return m.replies != null ? m.replies.replies : 0;
    }

    public static boolean hasComments(TLRPC.Message m) {
        return m.replies != null && m.replies.comments;
    }

    public static int reactions(TLRPC.Message m) {
        int total = 0;
        if (m.reactions != null) for (TLRPC.ReactionCount rc : m.reactions.results) total += rc.count;
        return total;
    }

    public static boolean liked(TLRPC.Message m) {
        if (m.reactions == null) return false;
        for (TLRPC.ReactionCount rc : m.reactions.results) {
            if (rc.chosen && !(rc.reaction instanceof TLRPC.TL_reactionPaid)) return true;
        }
        return false;
    }

    private static ReactionsLayoutInBubble.VisibleReaction preferred(TLRPC.Message m) {
        TLRPC.ReactionCount best = null;
        if (m.reactions != null) {
            for (TLRPC.ReactionCount rc : m.reactions.results) {
                if (rc.reaction instanceof TLRPC.TL_reactionPaid) continue;
                if (rc.reaction instanceof TLRPC.TL_reactionEmoji && HEART.equals(((TLRPC.TL_reactionEmoji) rc.reaction).emoticon)) {
                    return ReactionsLayoutInBubble.VisibleReaction.fromTL(rc.reaction);
                }
                if (best == null || rc.count > best.count) best = rc;
            }
        }
        if (best != null) return ReactionsLayoutInBubble.VisibleReaction.fromTL(best.reaction);
        return ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(HEART);
    }

    public static boolean toggleLike(BaseFragment fragment, MessageObject mo) {
        TLRPC.Message m = mo.messageOwner;
        ReactionsLayoutInBubble.VisibleReaction pick = null;
        boolean adding = !liked(m);
        if (adding) {
            pick = preferred(m);
        } else {
            for (TLRPC.ReactionCount rc : m.reactions.results) {
                if (rc.chosen && !(rc.reaction instanceof TLRPC.TL_reactionPaid)) {
                    pick = ReactionsLayoutInBubble.VisibleReaction.fromTL(rc.reaction);
                    break;
                }
            }
        }
        return react(fragment, mo, pick, adding);
    }

    public static boolean react(BaseFragment fragment, MessageObject mo, ReactionsLayoutInBubble.VisibleReaction pick, boolean adding) {
        if (pick == null) return liked(mo.messageOwner);
        try {
            mo.selectReaction(pick, false, false);
            fragment.getSendMessagesHelper().sendReaction(mo, mo.getChoosenReactions(), adding ? pick : null, false, false, fragment, null);
        } catch (Throwable ignore) {
        }
        return liked(mo.messageOwner);
    }

    public static String handle(TLRPC.Chat chat) {
        String u = chat != null ? ChatObject.getPublicUsername(chat) : null;
        return u != null ? "@" + u : null;
    }

    public static String link(TLRPC.Chat chat, int id) {
        if (chat == null) return null;
        String u = ChatObject.getPublicUsername(chat);
        return u != null ? "https://t.me/" + u + "/" + id : "https://t.me/c/" + chat.id + "/" + id;
    }

    public static void share(BaseFragment fragment, MessageObject mo) {
        if (fragment.getParentActivity() == null) return;
        fragment.showDialog(ShareAlert.createShareAlert(fragment.getParentActivity(), mo, null, false, null, false));
    }

    public static void openMedia(BaseFragment fragment, ArrayList<MessageObject> group, int index) {
        if (fragment.getParentActivity() == null || group.isEmpty()) return;
        try {
            PhotoViewer.getInstance().setParentActivity(fragment);
            PhotoViewer.getInstance().openPhoto(group, Math.max(0, Math.min(index, group.size() - 1)), group.get(0).getDialogId(), 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
        } catch (Throwable ignore) {
            openPost(fragment, group.get(0));
        }
    }

    public static void openPost(BaseFragment fragment, MessageObject mo) {
        Bundle args = new Bundle();
        args.putLong("chat_id", -mo.getDialogId());
        args.putInt("message_id", mo.getId());
        fragment.presentFragment(new ChatActivity(args));
    }

    public static void openChannel(BaseFragment fragment, long chatId) {
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        fragment.presentFragment(new ChatActivity(args));
    }

    public static void openLink(BaseFragment fragment, MessageObject mo, ClickableSpan span, View view) {
        if (span instanceof URLSpanUserMention) {
            long id = Utilities.parseLong(((URLSpanUserMention) span).getURL());
            if (id > 0) {
                fragment.presentFragment(ProfileActivity.of(id));
                return;
            }
        }
        if (span instanceof URLSpanNoUnderline) {
            String url = ((URLSpanNoUnderline) span).getURL();
            if (url != null && (url.startsWith("#") || url.startsWith("$"))) {
                Bundle args = new Bundle();
                args.putLong("chat_id", -mo.getDialogId());
                ChatActivity chat = new ChatActivity(args);
                fragment.presentFragment(chat);
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        chat.openHashtagSearch(url);
                    } catch (Throwable ignore) {
                    }
                }, 350);
                return;
            }
        }
        if (span instanceof URLSpan && !(span instanceof URLSpanNoUnderline)) {
            Browser.openUrl(view.getContext(), ((URLSpan) span).getURL());
            return;
        }
        span.onClick(view);
    }

    public static Drawable verified(Context context) {
        return new Verified(context);
    }

    private static final class Verified extends Drawable {
        private final Drawable area;
        private final Drawable check;

        Verified(Context context) {
            area = context.getResources().getDrawable(R.drawable.verified_area).mutate();
            check = context.getResources().getDrawable(R.drawable.verified_check).mutate();
            area.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedBackground), PorterDuff.Mode.SRC_IN));
            check.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_chats_verifiedCheck), PorterDuff.Mode.SRC_IN));
        }

        @Override
        public void draw(Canvas canvas) {
            area.setBounds(getBounds());
            check.setBounds(getBounds());
            area.draw(canvas);
            check.draw(canvas);
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(16);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(16);
        }

        @Override
        public void setAlpha(int alpha) {
            area.setAlpha(alpha);
            check.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
