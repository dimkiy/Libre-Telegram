package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaPlaylist;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class PrizmaPlaylistSheet {
    public static final int COVER_REQUEST = 48216;

    static final int[][] PALETTE = {
            {0xff7C5CFF, 0xff4D2BD6}, {0xffFF6B9D, 0xffC13584},
            {0xff26C281, 0xff0E8C5A}, {0xffFF8A3C, 0xffE8534B},
            {0xff35C3F3, 0xff2A74E8}, {0xffF7B733, 0xffFC4A1A},
    };

    private static long coverTargetId;

    public static void show(Context context) {
        PrizmaLibrary.open(context, 0);
    }

    public static void openPlaylist(Context context, long id) {
        PrizmaLibrary.open(context, id);
    }

    public static void showAddToPlaylist(Context context, View behind, int accent, MessageObject mo, Runnable onChanged) {
        if (context == null || mo == null) return;
        if (accent == 0) accent = PrizmaGlass.DEFAULT_ACCENT;
        final int acc = accent;
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(22), AndroidUtilities.dp(18), AndroidUtilities.dp(16));

        TextView title = new TextView(context);
        title.setText("Добавить в плейлист");
        title.setTextColor(PrizmaGlass.TEXT);
        title.setTextSize(24);
        title.setTypeface(AndroidUtilities.bold());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        String t = mo.getMusicTitle(), a = mo.getMusicAuthor();
        TextView track = new TextView(context);
        track.setText((t == null || t.isEmpty() ? "Трек" : t) + (a == null || a.isEmpty() ? "" : " — " + a));
        track.setTextColor(PrizmaGlass.TEXT_2);
        track.setTextSize(14);
        track.setMaxLines(2);
        track.setEllipsize(android.text.TextUtils.TruncateAt.END);
        root.addView(track, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 3, 4, 14));

        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final PrizmaGlass.Sheet[] ref = new PrizmaGlass.Sheet[1];
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            list.addView(chooserRow(context, null, "Новый плейлист", null, false, acc, () -> {
                AlertDialog.Builder b = new AlertDialog.Builder(context);
                b.setTitle("Новый плейлист");
                final EditText input = new EditText(context);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                input.setHint("Название");
                input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
                FrameLayout wrap = new FrameLayout(context);
                wrap.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);
                wrap.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                b.setView(wrap);
                b.setPositiveButton("Создать", (d, w) -> {
                    String nm = input.getText().toString().trim();
                    long id = PrizmaPlaylist.createPlaylist(nm.isEmpty() ? "Новый плейлист" : nm, "");
                    PrizmaPlaylist.addTo(id, mo);
                    fill[0].run();
                    if (onChanged != null) onChanged.run();
                });
                b.setNegativeButton("Отмена", null);
                b.show();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        input.requestFocus();
                        AndroidUtilities.showKeyboard(input);
                    } catch (Throwable ignore) {
                    }
                }, 120);
            }));
            for (PrizmaPlaylist.Playlist p : PrizmaPlaylist.playlists()) {
                final long id = p.id;
                final boolean in = PrizmaPlaylist.containsIn(id, mo);
                String count = p.count + " " + plural(p.count);
                list.addView(chooserRow(context, p, p.name, count, in, acc, () -> {
                    if (PrizmaPlaylist.containsIn(id, mo)) {
                        PrizmaPlaylist.removeFrom(id, mo);
                    } else {
                        PrizmaPlaylist.addTo(id, mo);
                    }
                    try { list.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignore) {}
                    fill[0].run();
                    if (onChanged != null) onChanged.run();
                }));
            }
        };
        fill[0].run();

        TextView done = new TextView(context);
        done.setText("Готово");
        done.setTextColor(0xff111114);
        done.setTextSize(16);
        done.setTypeface(AndroidUtilities.bold());
        done.setGravity(Gravity.CENTER);
        done.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(24)).setGlassTint(0xf2ffffff));
        done.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        PrizmaGlass.pressable(done);
        root.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 0, 0));

        ref[0] = new PrizmaGlass.Sheet(context, behind, root);
        ref[0].show();
    }

    private static View chooserRow(Context context, PrizmaPlaylist.Playlist p, String name, String sub, boolean checked, int accent, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(7), AndroidUtilities.dp(4), AndroidUtilities.dp(7));

        View lead;
        if (p != null) {
            lead = coverView(context, p.cover, p.name, AndroidUtilities.dp(46), AndroidUtilities.dp(12));
        } else {
            FrameLayout plus = new FrameLayout(context);
            plus.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(12)));
            ImageView pi = new ImageView(context);
            pi.setImageResource(R.drawable.msg_add);
            pi.setColorFilter(0xffffffff);
            pi.setScaleType(ImageView.ScaleType.CENTER);
            plus.addView(pi, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            lead = plus;
        }
        row.addView(lead, LayoutHelper.createLinear(46, 46, Gravity.CENTER_VERTICAL));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(context);
        n.setText(name);
        n.setTextColor(PrizmaGlass.TEXT);
        n.setTextSize(16);
        n.setTypeface(AndroidUtilities.bold());
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(n);
        if (sub != null) {
            TextView s = new TextView(context);
            s.setText(sub);
            s.setTextColor(PrizmaGlass.TEXT_3);
            s.setTextSize(13);
            texts.addView(s, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 14, 0, 10, 0));

        if (p != null) {
            row.addView(new CheckMark(context, checked, accent), LayoutHelper.createLinear(26, 26, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        }
        row.setOnClickListener(v -> onClick.run());
        PrizmaGlass.pressable(row);
        return row;
    }

    private static class CheckMark extends View {
        private final boolean checked;
        private final int accent;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path tick = new android.graphics.Path();

        CheckMark(Context c, boolean checked, int accent) {
            super(c);
            this.checked = checked;
            this.accent = accent;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - AndroidUtilities.dpf2(1f);
            float cx = w / 2f, cy = h / 2f;
            if (checked) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(accent);
                canvas.drawCircle(cx, cy, r, paint);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dpf2(2.2f));
                paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                paint.setColor(0xffffffff);
                tick.rewind();
                tick.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                tick.lineTo(cx - r * 0.1f, cy + r * 0.34f);
                tick.lineTo(cx + r * 0.45f, cy - r * 0.3f);
                canvas.drawPath(tick, paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dpf2(1.6f));
                paint.setColor(0x73ffffff);
                canvas.drawCircle(cx, cy, r, paint);
            }
        }
    }

    static void pickCover(Context context, long id) {
        if (!(context instanceof Activity)) {
            android.widget.Toast.makeText(context, "Открой из приложения", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        coverTargetId = id;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            ((Activity) context).startActivityForResult(Intent.createChooser(intent, "Обложка плейлиста"), COVER_REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void handleCoverPicked(Activity activity, Uri uri) {
        if (activity == null || uri == null) return;
        try {
            File dst = new File(org.telegram.messenger.ApplicationLoader.getFilesDirFixed(), "prizma_pl_" + coverTargetId + ".jpg");
            dst.delete();
            InputStream in = activity.getContentResolver().openInputStream(uri);
            OutputStream os = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            os.close();
            in.close();
            PrizmaPlaylist.setCover(coverTargetId, dst.getAbsolutePath());
            AndroidUtilities.runOnUIThread(PrizmaLibrary::reload, 120);
        } catch (Throwable e) {
            FileLog.e(e);
            android.widget.Toast.makeText(activity, "Не удалось загрузить обложку", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static FrameLayout coverView(Context context, String path, String name, int size, int radius) {
        FrameLayout box = new FrameLayout(context);
        box.setClipToOutline(true);
        final int rad = radius;
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
            }
        });

        Bitmap bmp = decodeCover(path, size);
        if (bmp != null) {
            ImageView iv = new ImageView(context);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(iv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        } else {
            int[] pair = PALETTE[Math.abs((name == null ? "" : name).hashCode()) % PALETTE.length];
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, pair);
            box.setBackground(g);
            ImageView glyph = new ImageView(context);
            glyph.setImageResource(R.drawable.msg_played);
            glyph.setColorFilter(0x55ffffff);
            glyph.setScaleType(ImageView.ScaleType.CENTER);
            box.addView(glyph, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        return box;
    }

    static Bitmap decodeCover(String path, int reqPx) {
        if (path == null || path.length() == 0) return null;
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int ss = 1;
            int target = Math.max(1, reqPx);
            while (o.outWidth / ss > target * 2 || o.outHeight / ss > target * 2) ss *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = ss;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable e) {
            return null;
        }
    }

    static String plural(int n) {
        int n100 = n % 100, n10 = n % 10;
        if (n100 >= 11 && n100 <= 14) return "треков";
        if (n10 == 1) return "трек";
        if (n10 >= 2 && n10 <= 4) return "трека";
        return "треков";
    }

    static String plPlural(int n) {
        int n100 = n % 100, n10 = n % 10;
        if (n100 >= 11 && n100 <= 14) return "плейлистов";
        if (n10 == 1) return "плейлист";
        if (n10 >= 2 && n10 <= 4) return "плейлиста";
        return "плейлистов";
    }
}
