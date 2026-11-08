package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Arrays;

public class PrizmaPostMedia extends LinearLayout {

    public interface Delegate {
        void onMedia(int index);

        void onLink(String url);

        void onVote(int option);

        void onFile();
    }

    private final Grid grid;
    private final TextView domain;
    private final LinearLayout card;
    private final BackupImageView cardImage;
    private final TextView cardSite, cardTitle, cardText;
    private final LinearLayout poll;
    private final LinearLayout file;
    private final TextView fileName, fileInfo;
    private Delegate delegate;
    private final ArrayList<MessageObject> visual = new ArrayList<>();

    public PrizmaPostMedia(Context context) {
        super(context);
        setOrientation(VERTICAL);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

        grid = new Grid(context);
        addView(grid, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        domain = new TextView(context);
        domain.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        domain.setTextColor(gray);
        domain.setSingleLine(true);
        domain.setEllipsize(TextUtils.TruncateAt.END);
        addView(domain, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        card = new LinearLayout(context);
        card.setOrientation(HORIZONTAL);
        card.setBackground(new Rim(Theme.getColor(Theme.key_divider), AndroidUtilities.dp(16)));
        card.setOutlineProvider(new Round(16));
        PrizmaFeedKit.press(card, 0.97f);
        card.setClipToOutline(true);
        cardImage = new BackupImageView(context);
        card.addView(cardImage, LayoutHelper.createLinear(96, 96));
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        cardSite = new TextView(context);
        cardSite.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        cardSite.setTextColor(gray);
        cardSite.setSingleLine(true);
        cardSite.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(cardSite);
        cardTitle = new TextView(context);
        cardTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        cardTitle.setTextColor(ink);
        cardTitle.setMaxLines(2);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(cardTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        cardText = new TextView(context);
        cardText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        cardText.setTextColor(gray);
        cardText.setMaxLines(2);
        cardText.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(cardText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        card.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        poll = new LinearLayout(context);
        poll.setOrientation(VERTICAL);
        addView(poll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        file = new LinearLayout(context);
        file.setOrientation(HORIZONTAL);
        file.setGravity(Gravity.CENTER_VERTICAL);
        file.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        file.setBackground(new Rim(Theme.getColor(Theme.key_divider), AndroidUtilities.dp(16)));
        View fileIcon = new View(context);
        PrizmaFeedIcons fi = new PrizmaFeedIcons(PrizmaFeedIcons.FILE, 22).setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        fileIcon.setBackground(new LayerFill(Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.12f), fi));
        file.addView(fileIcon, LayoutHelper.createLinear(42, 42));
        LinearLayout fcol = new LinearLayout(context);
        fcol.setOrientation(VERTICAL);
        fileName = new TextView(context);
        fileName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        fileName.setTextColor(ink);
        fileName.setSingleLine(true);
        fileName.setEllipsize(TextUtils.TruncateAt.END);
        fcol.addView(fileName);
        fileInfo = new TextView(context);
        fileInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        fileInfo.setTextColor(gray);
        fileInfo.setSingleLine(true);
        fcol.addView(fileInfo);
        file.addView(fcol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        file.setOnClickListener(v -> {
            if (delegate != null) delegate.onFile();
        });
        PrizmaFeedKit.press(file, 0.97f);
        addView(file, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    private static boolean isVisual(MessageObject mo) {
        TLRPC.MessageMedia m = mo.messageOwner.media;
        if (m instanceof TLRPC.TL_messageMediaPhoto && m.photo != null) return true;
        return m instanceof TLRPC.TL_messageMediaDocument && m.document != null && (mo.isVideo() || mo.isGif() || mo.isRoundVideo());
    }

    public boolean bind(ArrayList<MessageObject> group, int avail) {
        visual.clear();
        for (MessageObject mo : group) if (isVisual(mo)) visual.add(mo);
        grid.setVisibility(GONE);
        domain.setVisibility(GONE);
        card.setVisibility(GONE);
        poll.setVisibility(GONE);
        file.setVisibility(GONE);
        MessageObject first = group.get(0);
        TLRPC.MessageMedia m = first.messageOwner.media;
        if (!visual.isEmpty()) {
            grid.bindMessages(visual, avail);
            setVisibility(VISIBLE);
            return true;
        }
        if (m instanceof TLRPC.TL_messageMediaWebPage && m.webpage instanceof TLRPC.TL_webPage) {
            bindWeb(m.webpage, avail);
            setVisibility(VISIBLE);
            return true;
        }
        if (m instanceof TLRPC.TL_messageMediaPoll) {
            bindPoll((TLRPC.TL_messageMediaPoll) m);
            setVisibility(VISIBLE);
            return true;
        }
        if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null && !first.isSticker() && !first.isAnimatedSticker()) {
            bindFile(first);
            setVisibility(VISIBLE);
            return true;
        }
        setVisibility(GONE);
        return false;
    }

    private static String host(TLRPC.WebPage w) {
        String s = !TextUtils.isEmpty(w.display_url) ? w.display_url : w.url;
        if (s == null) return "";
        try {
            Uri u = Uri.parse(s.contains("://") ? s : "https://" + s);
            if (u.getHost() != null) s = u.getHost();
        } catch (Throwable ignore) {
        }
        return s.startsWith("www.") ? s.substring(4) : s;
    }

    private void bindWeb(TLRPC.WebPage w, int avail) {
        String url = w.url;
        String site = host(w);
        String title = !TextUtils.isEmpty(w.title) ? w.title : !TextUtils.isEmpty(w.site_name) ? w.site_name : site;
        TLRPC.PhotoSize big = w.photo != null ? FileLoader.getClosestPhotoSizeWithSize(w.photo.sizes, AndroidUtilities.getPhotoSize()) : null;
        boolean telegram = w.type != null && w.type.startsWith("telegram_");
        boolean large = big != null && big.w >= 400 && !telegram && !"telegram_channel".equals(w.type);
        if (large) {
            TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(w.photo.sizes, 40);
            grid.bindPhoto(w.photo, big, small, title, avail);
            domain.setText("с " + site);
            domain.setVisibility(VISIBLE);
            grid.setOnClickListener(v -> {
                if (delegate != null) delegate.onLink(url);
            });
            return;
        }
        card.setVisibility(VISIBLE);
        if (w.photo != null && big != null) {
            TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(w.photo.sizes, 40);
            cardImage.setVisibility(VISIBLE);
            cardImage.setImage(ImageLocation.getForObject(big, w.photo), "96_96", ImageLocation.getForObject(small, w.photo), "b", 0, w);
        } else {
            cardImage.setVisibility(GONE);
        }
        cardSite.setText(!TextUtils.isEmpty(w.site_name) ? w.site_name : site);
        cardTitle.setText(title);
        cardText.setText(w.description);
        cardText.setVisibility(TextUtils.isEmpty(w.description) || cardImage.getVisibility() == VISIBLE && !TextUtils.isEmpty(w.title) ? GONE : VISIBLE);
        card.setOnClickListener(v -> {
            if (delegate != null) delegate.onLink(url);
        });
    }

    private void bindPoll(TLRPC.TL_messageMediaPoll media) {
        poll.removeAllViews();
        poll.setVisibility(VISIBLE);
        TLRPC.Poll p = media.poll;
        TLRPC.PollResults r = media.results;
        boolean voted = false;
        int total = r != null ? r.total_voters : 0;
        if (r != null && r.results != null) for (TLRPC.PollAnswerVoters v : r.results) if (v.chosen) voted = true;
        boolean reveal = voted || p.closed;
        int max = 0;
        int[] votes = new int[p.answers.size()];
        boolean[] chosen = new boolean[p.answers.size()];
        for (int i = 0; i < p.answers.size(); i++) {
            TLRPC.PollAnswer a = p.answers.get(i);
            if (r != null && r.results != null) {
                for (TLRPC.PollAnswerVoters v : r.results) {
                    if (Arrays.equals(v.option, a.option)) {
                        votes[i] = v.voters;
                        chosen[i] = v.chosen;
                    }
                }
            }
            max = Math.max(max, votes[i]);
        }
        for (int i = 0; i < p.answers.size(); i++) {
            TLRPC.PollAnswer a = p.answers.get(i);
            String label = a.text != null ? a.text.text : "";
            int percent = total > 0 ? Math.round(votes[i] * 100f / total) : 0;
            Option o = new Option(getContext(), label, percent, reveal, reveal && votes[i] == max && max > 0, chosen[i]);
            final int index = i;
            o.setOnClickListener(v -> {
                if (delegate != null) delegate.onVote(index);
            });
            PrizmaFeedKit.press(o, 0.97f);
            poll.addView(o, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, i == 0 ? 0 : 8, 0, 0));
        }
        TextView foot = new TextView(getContext());
        foot.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        foot.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        String who = total == 1 ? " голос" : total % 10 >= 2 && total % 10 <= 4 && (total % 100 < 12 || total % 100 > 14) ? " голоса" : total % 10 == 1 && total % 100 != 11 ? " голос" : " голосов";
        foot.setText(PrizmaFeedKit.count(Math.max(total, 0)).isEmpty() ? "Пока нет голосов" + (p.closed ? " · Опрос завершён" : "")
                : PrizmaFeedKit.count(total) + who + (p.closed ? " · Опрос завершён" : p.quiz ? " · Викторина" : ""));
        poll.addView(foot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
    }

    private void bindFile(MessageObject mo) {
        file.setVisibility(VISIBLE);
        TLRPC.Document d = mo.getDocument();
        if (mo.isMusic()) {
            fileName.setText(mo.getMusicTitle());
            fileInfo.setText(mo.getMusicAuthor() + " · " + AndroidUtilities.formatShortDuration((int) mo.getDuration()));
        } else if (mo.isVoice()) {
            fileName.setText("Голосовое сообщение");
            fileInfo.setText(AndroidUtilities.formatShortDuration((int) mo.getDuration()));
        } else {
            String n = FileLoader.getDocumentFileName(d);
            fileName.setText(TextUtils.isEmpty(n) ? "Файл" : n);
            fileInfo.setText(AndroidUtilities.formatFileSize(d != null ? d.size : 0));
        }
    }

    private static final class Round extends ViewOutlineProvider {
        private final int r;

        Round(int dp) {
            r = AndroidUtilities.dp(dp);
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
        }
    }

    private static final class Rim extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float r;

        Rim(int color, float r) {
            this.r = r;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(0.66f)));
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            float h = paint.getStrokeWidth() / 2f;
            rect.set(getBounds());
            rect.inset(h, h);
            canvas.drawRoundRect(rect, r, r, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static final class LayerFill extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.drawable.Drawable icon;

        LayerFill(int color, android.graphics.drawable.Drawable icon) {
            paint.setColor(color);
            this.icon = icon;
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()) / 2f, paint);
            int s = AndroidUtilities.dp(22);
            icon.setBounds(b.centerX() - s / 2, b.centerY() - s / 2, b.centerX() + s / 2, b.centerY() + s / 2);
            icon.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static final class Option extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint pct = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final String label;
        private final int percent;
        private final boolean reveal, top, mine;
        private float progress;

        Option(Context context, String label, int percent, boolean reveal, boolean top, boolean mine) {
            super(context);
            this.label = label;
            this.percent = percent;
            this.reveal = reveal;
            this.top = top;
            this.mine = mine;
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            fill.setColor(top ? Theme.multAlpha(accent, 0.28f) : Theme.multAlpha(gray, 0.18f));
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(AndroidUtilities.dp(1));
            rim.setColor(accent);
            text.setTextSize(AndroidUtilities.dp(15));
            text.setColor(reveal ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText) : accent);
            if (top || !reveal) text.setTypeface(AndroidUtilities.bold());
            pct.setTextSize(AndroidUtilities.dp(15));
            pct.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            pct.setTypeface(top ? AndroidUtilities.bold() : null);
            progress = reveal ? 0f : 1f;
            if (reveal) {
                android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(420);
                a.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                a.addUpdateListener(an -> {
                    progress = (float) an.getAnimatedValue();
                    invalidate();
                });
                post(a::start);
            }
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = AndroidUtilities.dp(reveal ? 6 : 19);
            int w = getWidth(), h = getHeight();
            if (reveal) {
                rect.set(0, 0, Math.max(r * 2, w * percent / 100f * progress), h);
                canvas.drawRoundRect(rect, r, r, fill);
                String p = percent + "%";
                float pw = pct.measureText(p);
                canvas.drawText(p, w - pw - AndroidUtilities.dp(12), h / 2f - (pct.descent() + pct.ascent()) / 2f, pct);
                String l = TextUtils.ellipsize((mine ? "✓ " : "") + label, text, w - pw - AndroidUtilities.dp(36), TextUtils.TruncateAt.END).toString();
                canvas.drawText(l, AndroidUtilities.dp(12), h / 2f - (text.descent() + text.ascent()) / 2f, text);
            } else {
                rect.set(rim.getStrokeWidth() / 2f, rim.getStrokeWidth() / 2f, w - rim.getStrokeWidth() / 2f, h - rim.getStrokeWidth() / 2f);
                canvas.drawRoundRect(rect, r, r, rim);
                String l = TextUtils.ellipsize(label, text, w - AndroidUtilities.dp(32), TextUtils.TruncateAt.END).toString();
                float lw = text.measureText(l);
                canvas.drawText(l, (w - lw) / 2f, h / 2f - (text.descent() + text.ascent()) / 2f, text);
            }
        }
    }

    private final class Grid extends ViewGroup {
        private final BackupImageView[] tiles = new BackupImageView[4];
        private final int[] durations = new int[4];
        private final boolean[] gifs = new boolean[4];
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint chip = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint plus = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final android.graphics.Path tri = new android.graphics.Path();
        private int count, extra, gw, gh;
        private String overlay;

        Grid(Context context) {
            super(context);
            setWillNotDraw(false);
            setOutlineProvider(new Round(16));
            setClipToOutline(true);
            for (int i = 0; i < 4; i++) {
                tiles[i] = new BackupImageView(context);
                tiles[i].setBackgroundColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.12f));
                final int index = i;
                tiles[i].setOnClickListener(v -> {
                    if (overlay != null) {
                        performClick();
                    } else if (delegate != null) {
                        delegate.onMedia(index);
                    }
                });
                PrizmaFeedKit.press(tiles[i], 0.96f);
                addView(tiles[i]);
            }
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1, AndroidUtilities.dp(0.66f)));
            shade.setColor(0x99000000);
            white.setColor(0xffffffff);
            chip.setColor(0xffffffff);
            chip.setTextSize(AndroidUtilities.dp(12.5f));
            chip.setTypeface(AndroidUtilities.bold());
            plus.setColor(0xffffffff);
            plus.setTextSize(AndroidUtilities.dp(26));
            plus.setTypeface(AndroidUtilities.bold());
        }

        void bindMessages(ArrayList<MessageObject> items, int avail) {
            setVisibility(VISIBLE);
            setOnClickListener(null);
            setClickable(false);
            overlay = null;
            count = Math.min(4, items.size());
            extra = items.size() - count;
            int fw = 0, fh = 0;
            for (int i = 0; i < 4; i++) {
                BackupImageView t = tiles[i];
                if (i >= count) {
                    t.setVisibility(GONE);
                    t.clearImage();
                    continue;
                }
                t.setVisibility(VISIBLE);
                MessageObject mo = items.get(i);
                TLRPC.MessageMedia m = mo.messageOwner.media;
                durations[i] = 0;
                gifs[i] = false;
                int size = count == 1 ? AndroidUtilities.getPhotoSize() : 640;
                if (m instanceof TLRPC.TL_messageMediaPhoto) {
                    TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, size);
                    TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, 40);
                    if (i == 0 && big != null) {
                        fw = big.w;
                        fh = big.h;
                    }
                    t.setImage(ImageLocation.getForObject(big, m.photo), null, ImageLocation.getForObject(small, m.photo), "b", 0, mo);
                } else {
                    TLRPC.Document doc = m.document;
                    for (TLRPC.DocumentAttribute a : doc.attributes) {
                        if (a instanceof TLRPC.TL_documentAttributeVideo && i == 0) {
                            fw = a.w;
                            fh = a.h;
                        }
                    }
                    durations[i] = mo.isGif() ? 0 : (int) Math.round(mo.getDuration());
                    gifs[i] = mo.isGif();
                    if (m.video_cover != null) {
                        TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(m.video_cover.sizes, size);
                        TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(m.video_cover.sizes, 40);
                        t.setImage(ImageLocation.getForObject(big, m.video_cover), null, ImageLocation.getForObject(small, m.video_cover), "b", 0, mo);
                    } else {
                        TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 640);
                        TLRPC.PhotoSize strip = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 40);
                        t.setImage(ImageLocation.getForDocument(thumb, doc), null, ImageLocation.getForDocument(strip, doc), "b", 0, mo);
                    }
                }
            }
            if (count == 1) {
                measureSingle(fw, fh, avail, 1.3f);
            } else {
                gw = avail;
                gh = Math.round(avail * 0.62f);
            }
            requestLayout();
            invalidate();
        }

        void bindPhoto(TLRPC.Photo photo, TLRPC.PhotoSize big, TLRPC.PhotoSize small, String title, int avail) {
            setVisibility(VISIBLE);
            count = 1;
            extra = 0;
            overlay = title;
            durations[0] = 0;
            gifs[0] = false;
            for (int i = 1; i < 4; i++) {
                tiles[i].setVisibility(GONE);
                tiles[i].clearImage();
            }
            tiles[0].setVisibility(VISIBLE);
            tiles[0].setImage(ImageLocation.getForObject(big, photo), null, ImageLocation.getForObject(small, photo), "b", 0, photo);
            gw = avail;
            float ratio = big.w > 0 ? (float) big.h / big.w : 0.52f;
            gh = Math.round(avail * Math.max(0.5f, Math.min(1f, ratio)));
            requestLayout();
            invalidate();
        }

        private void measureSingle(int w, int h, int avail, float maxRatio) {
            if (w <= 0 || h <= 0) {
                gw = avail;
                gh = avail * 9 / 16;
                return;
            }
            float ratio = (float) h / w;
            int maxH = Math.min(AndroidUtilities.dp(520), Math.round(avail * maxRatio));
            if (ratio * avail <= maxH) {
                gw = avail;
                gh = Math.max(AndroidUtilities.dp(120), Math.round(avail * ratio));
            } else {
                gh = maxH;
                gw = Math.max(Math.round(avail * 0.55f), Math.round(maxH / ratio));
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int g = AndroidUtilities.dp(2);
            int half = (gw - g) / 2;
            int halfH = (gh - g) / 2;
            for (int i = 0; i < count; i++) {
                int w, h;
                if (count == 1) {
                    w = gw;
                    h = gh;
                } else if (count == 2) {
                    w = half;
                    h = gh;
                } else if (count == 3) {
                    w = i == 0 ? half : gw - half - g;
                    h = i == 0 ? gh : (i == 1 ? halfH : gh - halfH - g);
                } else {
                    w = i % 2 == 0 ? half : gw - half - g;
                    h = i < 2 ? halfH : gh - halfH - g;
                }
                tiles[i].measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(gw, gh);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int g = AndroidUtilities.dp(2);
            for (int i = 0; i < count; i++) {
                int x, y;
                if (count == 1) {
                    x = 0;
                    y = 0;
                } else if (count == 2) {
                    x = i == 0 ? 0 : gw - tiles[i].getMeasuredWidth();
                    y = 0;
                } else if (count == 3) {
                    x = i == 0 ? 0 : gw - tiles[i].getMeasuredWidth();
                    y = i == 2 ? gh - tiles[i].getMeasuredHeight() : 0;
                } else {
                    x = i % 2 == 0 ? 0 : gw - tiles[i].getMeasuredWidth();
                    y = i < 2 ? 0 : gh - tiles[i].getMeasuredHeight();
                }
                tiles[i].layout(x, y, x + tiles[i].getMeasuredWidth(), y + tiles[i].getMeasuredHeight());
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            for (int i = 0; i < count; i++) {
                View t = tiles[i];
                if (durations[i] > 0 || gifs[i]) {
                    String label = gifs[i] ? "GIF" : AndroidUtilities.formatShortDuration(durations[i]);
                    float tw = chip.measureText(label);
                    float px = AndroidUtilities.dp(6), ph = AndroidUtilities.dp(20);
                    rect.set(t.getLeft() + AndroidUtilities.dp(8), t.getBottom() - AndroidUtilities.dp(8) - ph, t.getLeft() + AndroidUtilities.dp(8) + tw + px * 2, t.getBottom() - AndroidUtilities.dp(8));
                    canvas.drawRoundRect(rect, AndroidUtilities.dp(5), AndroidUtilities.dp(5), shade);
                    canvas.drawText(label, rect.left + px, rect.centerY() - (chip.descent() + chip.ascent()) / 2f, chip);
                }
                if (durations[i] > 0) {
                    float cx = t.getLeft() + t.getWidth() / 2f, cy = t.getTop() + t.getHeight() / 2f;
                    float rr = AndroidUtilities.dp(count == 1 ? 26 : 20);
                    canvas.drawCircle(cx, cy, rr, shade);
                    float s = rr * 0.42f;
                    tri.reset();
                    tri.moveTo(cx - s * 0.7f, cy - s);
                    tri.lineTo(cx + s, cy);
                    tri.lineTo(cx - s * 0.7f, cy + s);
                    tri.close();
                    canvas.drawPath(tri, white);
                }
            }
            if (extra > 0 && count == 4) {
                View t = tiles[3];
                canvas.drawRect(t.getLeft(), t.getTop(), t.getRight(), t.getBottom(), shade);
                String s = "+" + extra;
                float w = plus.measureText(s);
                canvas.drawText(s, t.getLeft() + (t.getWidth() - w) / 2f, t.getTop() + t.getHeight() / 2f - (plus.descent() + plus.ascent()) / 2f, plus);
            }
            if (overlay != null) {
                float pad = AndroidUtilities.dp(8);
                String s = TextUtils.ellipsize(overlay, chip, getWidth() - pad * 4, TextUtils.TruncateAt.END).toString();
                float tw = chip.measureText(s);
                rect.set(pad, getHeight() - pad - AndroidUtilities.dp(22), pad + tw + AndroidUtilities.dp(14), getHeight() - pad);
                canvas.drawRoundRect(rect, AndroidUtilities.dp(5), AndroidUtilities.dp(5), shade);
                canvas.drawText(s, rect.left + AndroidUtilities.dp(7), rect.centerY() - (chip.descent() + chip.ascent()) / 2f, chip);
            }
            rim.setColor(Theme.getColor(Theme.key_divider));
            float h = rim.getStrokeWidth() / 2f;
            rect.set(h, h, getWidth() - h, getHeight() - h);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(16), AndroidUtilities.dp(16), rim);
        }
    }
}
