package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaLyrics;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class PrizmaLyricsSheet {
    public static void show(Context context) {
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) {
            return;
        }
        new PrizmaLyricsSheet(context, mo).build();
    }

    private final Context context;
    private final MessageObject messageObject;
    private final int durationMs;

    private BottomSheet sheet;
    private ScrollView scrollView;
    private LinearLayout container;
    private TextView statusView;

    private final ArrayList<TextView> lineViews = new ArrayList<>();
    private PrizmaLyrics.Lyrics lyrics;
    private int currentLine = -1;
    private boolean running;

    private final int textColor;
    private final int dimColor;
    private final int accentColor;

    private PrizmaLyricsSheet(Context context, MessageObject mo) {
        this.context = context;
        this.messageObject = mo;
        this.durationMs = (int) (mo.getDuration() * 1000);
        this.textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        this.dimColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        this.accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            updateHighlight();
            AndroidUtilities.runOnUIThread(this, 180);
        }
    };

    private void build() {
        FrameLayout root = new FrameLayout(context);
        root.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(10));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        String t = messageObject.getMusicTitle();
        title.setText((t == null || t.isEmpty()) ? "Текст песни" : t);
        title.setTextColor(textColor);
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(title);

        String au = messageObject.getMusicAuthor();
        TextView author = new TextView(context);
        author.setText((au == null) ? "" : au);
        author.setTextColor(dimColor);
        author.setTextSize(13);
        author.setMaxLines(1);
        author.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(author, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 12));

        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(container, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        statusView = new TextView(context);
        statusView.setText("Загрузка текста…");
        statusView.setTextColor(dimColor);
        statusView.setTextSize(15);
        statusView.setPadding(0, AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20));
        container.addView(statusView);

        column.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.5f)));

        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setCustomView(root);
        sheet = builder.create();
        sheet.setOnHideListener(dialog -> stop());
        sheet.show();
        running = true;

        PrizmaLyrics.fetch(messageObject.getMusicTitle(), messageObject.getMusicAuthor(),
                (int) messageObject.getDuration(), this::onLyrics);
    }

    private void onLyrics(PrizmaLyrics.Lyrics result) {
        if (!running) {
            return;
        }
        lyrics = result;
        container.removeAllViews();
        lineViews.clear();
        currentLine = -1;

        if (result == null) {
            statusView.setText("Текст не найден.\nПроверьте подключение к интернету.");
            container.addView(statusView);
            return;
        }

        for (int i = 0; i < result.lines.size(); i++) {
            final int index = i;
            PrizmaLyrics.Line line = result.lines.get(i);
            TextView tv = new TextView(context);
            tv.setText(line.text.isEmpty() ? " " : line.text);
            tv.setTextSize(result.synced ? 17 : 15);
            tv.setTextColor(result.synced ? dimColor : textColor);
            tv.setPadding(0, AndroidUtilities.dp(result.synced ? 7 : 4), 0, AndroidUtilities.dp(result.synced ? 7 : 4));
            tv.setLineSpacing(AndroidUtilities.dp(2), 1f);
            if (result.synced && line.timeMs >= 0 && durationMs > 0) {
                tv.setOnClickListener(v -> {
                    MediaController.getInstance().seekToProgress(messageObject, line.timeMs / (float) durationMs);
                    highlightLine(index, true);
                });
            }
            container.addView(tv);
            lineViews.add(tv);
        }

        if (result.synced) {
            AndroidUtilities.runOnUIThread(ticker, 120);
        }
    }

    private void updateHighlight() {
        if (lyrics == null || !lyrics.synced || lineViews.isEmpty() || durationMs <= 0) {
            return;
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        long posMs = (long) (playing.audioProgress * durationMs);
        int idx = 0;
        for (int i = 0; i < lyrics.lines.size(); i++) {
            if (lyrics.lines.get(i).timeMs <= posMs) {
                idx = i;
            } else {
                break;
            }
        }
        if (idx != currentLine) {
            highlightLine(idx, true);
        }
    }

    private void highlightLine(int idx, boolean smooth) {
        for (int i = 0; i < lineViews.size(); i++) {
            TextView tv = lineViews.get(i);
            boolean active = i == idx;
            tv.setTextColor(active ? accentColor : dimColor);
            tv.setTypeface(active ? AndroidUtilities.bold() : Typeface.DEFAULT);
            tv.setAlpha(active ? 1f : 0.45f);
        }
        currentLine = idx;
        if (idx >= 0 && idx < lineViews.size()) {
            final TextView tv = lineViews.get(idx);
            scrollView.post(() -> {
                int target = tv.getTop() - scrollView.getHeight() / 2 + tv.getHeight() / 2;
                if (target < 0) target = 0;
                if (smooth) {
                    scrollView.smoothScrollTo(0, target);
                } else {
                    scrollView.scrollTo(0, target);
                }
            });
        }
    }

    private void stop() {
        running = false;
        AndroidUtilities.cancelRunOnUIThread(ticker);
    }
}
