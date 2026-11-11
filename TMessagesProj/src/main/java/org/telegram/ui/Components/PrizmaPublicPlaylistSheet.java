package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaPlaylist;
import org.telegram.messenger.PrizmaSocial;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;

public final class PrizmaPublicPlaylistSheet {
    private PrizmaPublicPlaylistSheet() {}

    private static class Entry {
        String title, author;
        String channel;
        int messageId;
        MessageObject track;
        boolean done;
        TextView subtitle;
        String hosted;
        long doc;
        int duration;
        boolean loading;
    }

    public static void show(Context ctx, int account, long userId) {
        PrizmaCloud.call(account, "GET", "/v1/playlist/" + userId, null, r -> {
            if (r.code != 200 || r.body == null) {
                Toast.makeText(ctx, r.code == 404 ? "Плейлист больше не открыт" : org.telegram.messenger.PrizmaWall.error(r.code, null), Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                JSONObject o = new JSONObject(r.body);
                render(ctx, account, userId, o.optString("name", "Плейлист"), o.getJSONArray("tracks"));
            } catch (Throwable e) {
                Toast.makeText(ctx, "Не удалось открыть плейлист", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void render(Context ctx, int account, long userId, String name, JSONArray arr) {
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        final ArrayList<Entry> entries = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            Entry e = new Entry();
            e.title = t.optString("t", "");
            e.author = t.optString("a", "");
            String src = t.optString("src", "");
            int slash = src.indexOf('/');
            if (slash > 0) {
                e.channel = src.substring(0, slash);
                e.messageId = parseIntOr0(src.substring(slash + 1));
            }
            String url = t.optString("url", "");
            e.hosted = url.startsWith("http") ? url : null;
            e.doc = t.optLong("doc", 0);
            e.duration = t.optInt("d", 0);
            entries.add(e);
        }
        TLRPC.User owner = MessagesController.getInstance(account).getUser(userId);
        final String ownerName = owner != null ? UserObject.getFirstName(owner) : "друга";

        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        TextView title = new TextView(ctx);
        title.setText(name);
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        TextView sub = new TextView(ctx);
        sub.setText("Плейлист " + (owner != null ? UserObject.getFirstName(owner) : "") + " · " + entries.size() + " "
                + PrizmaSocial.plural(entries.size(), "трек", "трека", "треков"));
        sub.setTextColor(gray);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 2, 4, 12));

        final BottomSheet[] ref = new BottomSheet[1];
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView play = pill(ctx, "Слушать", true);
        play.setOnClickListener(x -> playFrom(ctx, account, userId, entries, firstPlayable(entries), () -> {
            if (ref[0] != null) ref[0].dismiss();
        }));
        buttons.addView(play, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 6, 0));
        TextView keep = pill(ctx, "Сохранить себе", false);
        keep.setOnClickListener(x -> {
            ArrayList<MessageObject> ready = playable(entries);
            if (ready.isEmpty()) {
                Toast.makeText(ctx, stillLoading(entries) ? "Треки ещё загружаются — секунду" : "Здесь нет треков, которые можно сохранить", Toast.LENGTH_SHORT).show();
                return;
            }
            PrizmaPlaylist.importTracks(name + " · " + ownerName, ready);
            for (MessageObject mo : ready) {
                if (mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.attachPath)) {
                    org.telegram.messenger.PrizmaOffline.save(account, mo, null);
                }
            }
            Toast.makeText(ctx, ready.size() < entries.size()
                    ? "Сохранено треков: " + ready.size() + " из " + entries.size() + ". Остальные ещё не загружены или недоступны"
                    : "Плейлист сохранён в «Мои плейлисты»", Toast.LENGTH_LONG).show();
            if (ref[0] != null) ref[0].dismiss();
        });
        buttons.addView(keep, LayoutHelper.createLinear(0, 44, 1f, 6, 0, 0, 0));
        v.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 10));

        for (int i = 0; i < entries.size(); i++) {
            final Entry e = entries.get(i);
            final int index = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(9), AndroidUtilities.dp(6), AndroidUtilities.dp(9));
            row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 12, 12));
            TextView num = new TextView(ctx);
            num.setText(String.valueOf(i + 1));
            num.setTextColor(gray);
            num.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            num.setGravity(Gravity.CENTER);
            row.addView(num, LayoutHelper.createLinear(28, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(ctx);
            t.setText(e.title.isEmpty() ? "Трек" : e.title);
            t.setTextColor(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            t.setTypeface(AndroidUtilities.bold());
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
            TextView a = new TextView(ctx);
            a.setText(e.author);
            a.setTextColor(gray);
            a.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            a.setSingleLine(true);
            a.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(a);
            e.subtitle = a;
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            row.setOnClickListener(x -> playFrom(ctx, account, userId, entries, index, () -> {
                if (ref[0] != null) ref[0].dismiss();
            }));
            v.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        ScrollView sv = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.78f), MeasureSpec.AT_MOST));
            }
        };
        sv.setNestedScrollingEnabled(true);
        sv.addView(v);
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(sv);
        ref[0] = b.create();
        ref[0].setCanDismissWithSwipe(false);
        ref[0].show();

        loadTracks(account, userId, entries);
    }

    private static final long MAX_HOSTED = 17L * 1024 * 1024;
    private static final long CACHE_LIMIT = 400L * 1024 * 1024;

    private static java.io.File hostedFile(Entry e) {
        java.io.File dir = new java.io.File(org.telegram.messenger.ApplicationLoader.applicationContext.getCacheDir(), "libre_tracks");
        dir.mkdirs();
        String ext = "mp3";
        int dot = e.hosted == null ? -1 : e.hosted.lastIndexOf('.');
        if (dot > 0 && e.hosted.length() - dot <= 6) {
            String tail = e.hosted.substring(dot + 1).toLowerCase();
            if (tail.matches("[a-z0-9]{2,5}")) ext = tail;
        }
        return new java.io.File(dir, e.doc + "." + ext);
    }

    private static void trimCache(java.io.File keep) {
        java.io.File[] files = keep.getParentFile() == null ? null : keep.getParentFile().listFiles();
        if (files == null) return;
        long total = 0;
        for (java.io.File f : files) total += f.length();
        if (total <= CACHE_LIMIT) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (java.io.File f : files) {
            if (total <= CACHE_LIMIT * 3 / 4) break;
            if (f.equals(keep)) continue;
            total -= f.length();
            f.delete();
        }
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".wav")) return "audio/wav";
        return "audio/mpeg";
    }

    private static MessageObject hostedMessage(int account, long ownerId, Entry e, java.io.File file) {
        try {
            TLRPC.TL_document doc = new TLRPC.TL_document();
            doc.id = e.doc;
            doc.file_reference = new byte[0];
            doc.date = (int) (System.currentTimeMillis() / 1000);
            doc.mime_type = mimeOf(file.getName());
            doc.size = file.length();
            TLRPC.TL_documentAttributeAudio audio = new TLRPC.TL_documentAttributeAudio();
            audio.duration = e.duration;
            audio.title = e.title;
            audio.performer = e.author;
            audio.flags |= 3;
            doc.attributes.add(audio);
            TLRPC.TL_documentAttributeFilename fileName = new TLRPC.TL_documentAttributeFilename();
            fileName.file_name = file.getName();
            doc.attributes.add(fileName);
            TLRPC.TL_message m = new TLRPC.TL_message();
            m.id = 1_000_000_000 + (int) (Math.abs(e.doc) % 1_000_000_000L);
            m.date = doc.date;
            m.message = "";
            m.peer_id = new TLRPC.TL_peerUser();
            m.peer_id.user_id = org.telegram.messenger.UserConfig.getInstance(account).getClientUserId();
            m.from_id = new TLRPC.TL_peerUser();
            m.from_id.user_id = ownerId;
            m.dialog_id = ownerId;
            m.media = new TLRPC.TL_messageMediaDocument();
            m.media.flags |= 1;
            m.media.document = doc;
            m.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
            m.attachPath = file.getAbsolutePath();
            return new MessageObject(account, m, false, true);
        } catch (Throwable t) {
            org.telegram.messenger.FileLog.e(t);
            return null;
        }
    }

    private static void label(Entry e, String state) {
        if (e.subtitle != null) e.subtitle.setText(TextUtils.isEmpty(e.author) ? state : e.author + " · " + state);
    }

    private static void prepare(int account, long ownerId, Entry e, Runnable ready) {
        if (e.track != null || e.hosted == null) {
            ready.run();
            return;
        }
        if (e.loading) return;
        e.loading = true;
        label(e, "загружается…");
        org.telegram.messenger.PrizmaJobs.net(() -> {
            final java.io.File f = hostedFile(e);
            final boolean ok = f.exists() && f.length() > 0 || org.telegram.messenger.PrizmaNet.download(e.hosted, f, MAX_HOSTED, 30000);
            if (ok) {
                f.setLastModified(System.currentTimeMillis());
                trimCache(f);
            }
            AndroidUtilities.runOnUIThread(() -> {
                e.loading = false;
                if (ok) e.track = hostedMessage(account, ownerId, e, f);
                label(e, e.track != null ? "из облака Libre" : "не загрузился");
                ready.run();
            });
        });
    }

    private static boolean ours(ArrayList<Entry> entries) {
        MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
        if (cur == null) return false;
        for (Entry e : entries) {
            if (e.track != null && e.track.getId() == cur.getId() && e.track.getDialogId() == cur.getDialogId()) return true;
        }
        return false;
    }

    private static void prefetch(int account, long ownerId, ArrayList<Entry> entries, int from, int checked) {
        if (checked >= entries.size() || org.telegram.messenger.PrizmaClips.metered() || !ours(entries)) return;
        final int at = from % entries.size();
        final Entry e = entries.get(at);
        if (e.track != null || e.hosted == null || e.loading) {
            prefetch(account, ownerId, entries, at + 1, checked + 1);
            return;
        }
        prepare(account, ownerId, e, () -> {
            if (e.track != null && ours(entries)) MediaController.getInstance().prizmaAppend(e.track);
            prefetch(account, ownerId, entries, at + 1, checked + 1);
        });
    }

    private static void loadTracks(int account, long ownerId, ArrayList<Entry> entries) {
        HashMap<String, ArrayList<Entry>> byChannel = new HashMap<>();
        for (Entry e : entries) {
            if (e.channel == null || e.messageId <= 0) {
                if (e.hosted != null && e.doc != 0) {
                    e.done = true;
                    java.io.File f = hostedFile(e);
                    if (f.exists() && f.length() > 0) e.track = hostedMessage(account, ownerId, e, f);
                    label(e, "из облака Libre");
                } else {
                    markUnavailable(e, "владелец ещё не выложил этот трек");
                }
                continue;
            }
            ArrayList<Entry> list = byChannel.get(e.channel);
            if (list == null) byChannel.put(e.channel, list = new ArrayList<>());
            list.add(e);
        }
        final MessagesController mc = MessagesController.getInstance(account);
        for (String channel : byChannel.keySet()) {
            final ArrayList<Entry> list = byChannel.get(channel);
            mc.getUserNameResolver().resolve(channel, peerId -> {
                TLRPC.Chat chat = peerId != null && peerId < 0 ? mc.getChat(-peerId) : null;
                if (chat == null) {
                    for (Entry e : list) markUnavailable(e, "канал с треком удалён или закрыт");
                    return;
                }
                TLRPC.TL_channels_getMessages req = new TLRPC.TL_channels_getMessages();
                req.channel = mc.getInputChannel(chat);
                for (Entry e : list) req.id.add(e.messageId);
                ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                    HashMap<Integer, TLRPC.Message> found = new HashMap<>();
                    if (res instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages m = (TLRPC.messages_Messages) res;
                        mc.putUsers(m.users, false);
                        mc.putChats(m.chats, false);
                        for (TLRPC.Message msg : m.messages) found.put(msg.id, msg);
                    }
                    for (Entry e : list) {
                        TLRPC.Message msg = found.get(e.messageId);
                        MessageObject mo = null;
                        if (msg != null && msg.media != null && msg.media.document != null) {
                            try {
                                mo = new MessageObject(account, msg, false, true);
                            } catch (Throwable ignore) {
                            }
                        }
                        if (mo != null && mo.getDocument() != null) {
                            e.track = mo;
                            e.done = true;
                        } else {
                            markUnavailable(e, "трек удалён из канала");
                        }
                    }
                }));
            });
        }
    }

    private static void markUnavailable(Entry e, String why) {
        e.done = true;
        e.track = null;
        if (e.subtitle != null) {
            e.subtitle.setText(TextUtils.isEmpty(e.author) ? why : e.author + " · " + why);
        }
    }

    private static ArrayList<MessageObject> playable(ArrayList<Entry> entries) {
        ArrayList<MessageObject> out = new ArrayList<>();
        for (Entry e : entries) if (e.track != null) out.add(e.track);
        return out;
    }

    private static boolean stillLoading(ArrayList<Entry> entries) {
        for (Entry e : entries) if (!e.done) return true;
        return false;
    }

    private static int firstPlayable(ArrayList<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).track != null) return i;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).hosted != null) return i;
        return 0;
    }

    private static void playFrom(Context ctx, int account, long ownerId, ArrayList<Entry> entries, int index, Runnable started) {
        if (index < 0 || index >= entries.size()) return;
        final Entry chosen = entries.get(index);
        if (chosen.track == null && chosen.hosted == null) {
            Toast.makeText(ctx, !chosen.done ? "Трек ещё загружается — секунду" : "Этот трек пока недоступен: владелец плейлиста ещё не выложил его", Toast.LENGTH_SHORT).show();
            return;
        }
        if (chosen.loading) {
            Toast.makeText(ctx, "Трек уже загружается — секунду", Toast.LENGTH_SHORT).show();
            return;
        }
        prepare(account, ownerId, chosen, () -> {
            if (chosen.track == null) {
                Toast.makeText(ctx, "Не удалось загрузить трек — проверьте интернет", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                ArrayList<MessageObject> list = playable(entries);
                if (!MediaController.getInstance().setPlaylist(list, chosen.track, 0)) {
                    Toast.makeText(ctx, "Этот трек сейчас недоступен", Toast.LENGTH_SHORT).show();
                    return;
                }
                started.run();
                PrizmaFullPlayer.show(ctx);
                prefetch(account, ownerId, entries, index + 1, 0);
            } catch (Throwable e) {
                org.telegram.messenger.FileLog.e(e);
                Toast.makeText(ctx, "Не удалось включить трек", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static int parseIntOr0(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static TextView pill(Context ctx, String label, boolean filled) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setTypeface(AndroidUtilities.bold());
        if (filled) {
            t.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            t.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 10));
        } else {
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(AndroidUtilities.dp(10));
            d.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.06f));
            t.setBackground(d);
        }
        return t;
    }
}
