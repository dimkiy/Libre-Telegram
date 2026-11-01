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
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * Prizma: a playlist someone opened in their Libre profile — listen to it, or keep a copy.
 */
public final class PrizmaPublicPlaylistSheet {

    private PrizmaPublicPlaylistSheet() {}

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
        final ArrayList<MessageObject> tracks = new ArrayList<>();
        final ArrayList<String[]> labels = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            MessageObject mo = PrizmaPlaylist.fromPublic(t.optString("data", ""));
            if (mo == null || mo.getDocument() == null) continue; // nothing to play — the player would choke on it
            tracks.add(mo);
            labels.add(new String[]{t.optString("t", ""), t.optString("a", "")});
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
        sub.setText("Плейлист " + (owner != null ? UserObject.getFirstName(owner) : "") + " · " + tracks.size() + " "
                + PrizmaSocial.plural(tracks.size(), "трек", "трека", "треков"));
        sub.setTextColor(gray);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 2, 4, 12));

        final BottomSheet[] ref = new BottomSheet[1];
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView play = pill(ctx, "Слушать", true);
        play.setOnClickListener(x -> {
            if (tracks.isEmpty()) return;
            if (ref[0] != null) ref[0].dismiss();
            playFrom(ctx, tracks, 0);
        });
        buttons.addView(play, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 6, 0));
        TextView keep = pill(ctx, "Сохранить себе", false);
        keep.setOnClickListener(x -> {
            if (tracks.isEmpty()) return;
            PrizmaPlaylist.importTracks(name + " · " + ownerName, tracks);
            Toast.makeText(ctx, "Плейлист сохранён в «Мои плейлисты»", Toast.LENGTH_SHORT).show();
            if (ref[0] != null) ref[0].dismiss();
        });
        buttons.addView(keep, LayoutHelper.createLinear(0, 44, 1f, 6, 0, 0, 0));
        v.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 10));

        for (int i = 0; i < tracks.size(); i++) {
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
            t.setText(labels.get(i)[0].isEmpty() ? "Трек" : labels.get(i)[0]);
            t.setTextColor(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            t.setTypeface(AndroidUtilities.bold());
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(t);
            TextView a = new TextView(ctx);
            a.setText(labels.get(i)[1]);
            a.setTextColor(gray);
            a.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            a.setSingleLine(true);
            a.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(a);
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            row.setOnClickListener(x -> playFrom(ctx, tracks, index));
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
    }

    /** Someone else's track may be gone or unreachable — that's a toast, never a crash. */
    private static void playFrom(Context ctx, ArrayList<MessageObject> tracks, int index) {
        try {
            if (!MediaController.getInstance().setPlaylist(new ArrayList<>(tracks), tracks.get(index), 0)) {
                Toast.makeText(ctx, "Этот трек сейчас недоступен", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            Toast.makeText(ctx, "Не удалось включить трек", Toast.LENGTH_SHORT).show();
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
