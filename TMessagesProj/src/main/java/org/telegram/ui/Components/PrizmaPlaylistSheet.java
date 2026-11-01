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
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaPlaylist;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Prizma: multi-playlist music library, styled like a modern music app — playlist cards with
 * their own covers and names, create/rename/delete, add tracks across playlists.
 */
public class PrizmaPlaylistSheet {

    public static final int COVER_REQUEST = 48216;

    // card gradient palette (used when a playlist has no cover image)
    private static final int[][] PALETTE = {
            {0xff7C5CFF, 0xff4D2BD6}, {0xffFF6B9D, 0xffC13584},
            {0xff26C281, 0xff0E8C5A}, {0xffFF8A3C, 0xffE8534B},
            {0xff35C3F3, 0xff2A74E8}, {0xffF7B733, 0xffFC4A1A},
    };

    private static BottomSheet sheet;
    private static Context pickCtx;
    private static long coverTargetId;

    // ======================= HOME: all playlists =======================

    public static void show(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(14));

        ArrayList<PrizmaPlaylist.Playlist> pls = PrizmaPlaylist.playlists();

        // header
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("Моя музыка");
        title.setTextColor(textColor);
        title.setTextSize(26);
        title.setTypeface(AndroidUtilities.bold());
        header.addView(title);
        TextView subtitle = new TextView(context);
        subtitle.setText(pls.size() + " " + plPlural(pls.size()));
        subtitle.setTextColor(grayColor);
        subtitle.setTextSize(13);
        header.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // grid of playlist cards + a "create" card
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        int cols = 2;
        int totalW = AndroidUtilities.displaySize.x - AndroidUtilities.dp(16 * 2);
        int gap = AndroidUtilities.dp(12);
        int cardW = (totalW - gap * (cols - 1)) / cols;

        // items: all playlists, then the "create" card
        int itemCount = pls.size() + 1;
        final ArrayList<View> cards = new ArrayList<>();
        LinearLayout rowLayout = null;
        for (int i = 0; i < itemCount; i++) {
            if (i % cols == 0) {
                rowLayout = new LinearLayout(context);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(rowLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, gap / AndroidUtilities.density));
            }
            View card;
            if (i < pls.size()) {
                card = playlistCard(context, pls.get(i), cardW);
            } else {
                card = createCard(context, cardW);
            }
            cards.add(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, LayoutHelper.WRAP_CONTENT);
            if (i % cols != cols - 1) lp.rightMargin = gap;
            rowLayout.addView(card, lp);
        }

        int h = (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.62f);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, h));

        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setCustomView(root);
        sheet = builder.create();
        try { sheet.fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        sheet.show();

        // staggered entrance
        for (int i = 0; i < cards.size(); i++) {
            final View c = cards.get(i);
            c.setAlpha(0f);
            c.setTranslationY(AndroidUtilities.dp(16));
            c.animate().alpha(1f).translationY(0f)
                    .setStartDelay(35L * i).setDuration(280)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }

    private static View playlistCard(Context context, PrizmaPlaylist.Playlist p, int cardW) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);

        FrameLayout coverBox = coverView(context, p.cover, p.name, cardW, AndroidUtilities.dp(16));
        card.addView(coverBox, new LinearLayout.LayoutParams(cardW, cardW));

        TextView name = new TextView(context);
        name.setText(p.name);
        name.setTextColor(textColor);
        name.setTextSize(15);
        name.setTypeface(AndroidUtilities.bold());
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        TextView sub = new TextView(context);
        sub.setText(p.count + " " + plural(p.count));
        sub.setTextColor(grayColor);
        sub.setTextSize(12);
        card.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        final long id = p.id;
        card.setOnClickListener(v -> {
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(new OvershootInterpolator(2f)).start()).start();
            openPlaylist(context, id);
        });
        card.setOnLongClickListener(v -> {
            playlistMenu(context, p);
            return true;
        });
        return card;
    }

    private static View createCard(Context context, int cardW) {
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);

        FrameLayout box = new FrameLayout(context);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.multAlpha(grayColor, 0.12f));
        g.setCornerRadius(AndroidUtilities.dp(16));
        g.setStroke(AndroidUtilities.dp(1.5f), Theme.multAlpha(grayColor, 0.4f));
        box.setBackground(g);
        ImageView plus = new ImageView(context);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(grayColor);
        plus.setScaleType(ImageView.ScaleType.CENTER);
        box.addView(plus, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        card.addView(box, new LinearLayout.LayoutParams(cardW, cardW));

        TextView name = new TextView(context);
        name.setText("Создать плейлист");
        name.setTextColor(grayColor);
        name.setTextSize(15);
        name.setTypeface(AndroidUtilities.bold());
        card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        card.setOnClickListener(v -> promptCreate(context));
        return card;
    }

    private static void promptCreate(Context context) {
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle("Новый плейлист");
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("Название");
        input.setText("");
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        FrameLayout wrap = new FrameLayout(context);
        wrap.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);
        wrap.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        b.setView(wrap);
        b.setPositiveButton("Создать", (d, w) -> {
            String nm = input.getText().toString().trim();
            long id = PrizmaPlaylist.createPlaylist(nm.isEmpty() ? "Новый плейлист" : nm, "");
            if (sheet != null) sheet.dismiss();
            openPlaylist(context, id);
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
    }

    private static void playlistMenu(Context context, PrizmaPlaylist.Playlist p) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        root.addView(menuRow(context, textColor, R.drawable.msg_edit, "Переименовать", () -> {
            if (ref[0] != null) ref[0].dismiss();
            promptRename(context, p.id, p.name);
        }));
        root.addView(menuRow(context, textColor, R.drawable.msg_photo_settings, "Сменить обложку", () -> {
            if (ref[0] != null) ref[0].dismiss();
            pickCover(context, p.id);
        }));
        if (p.cover != null && p.cover.length() > 0) {
            root.addView(menuRow(context, textColor, R.drawable.msg_delete, "Убрать обложку", () -> {
                PrizmaPlaylist.setCover(p.id, "");
                if (ref[0] != null) ref[0].dismiss();
                reopenHome(context);
            }));
        }
        // open this playlist to everyone in Libre (one at a time)
        final boolean isPublic = PrizmaPlaylist.publicId() == p.id;
        root.addView(menuRow(context, textColor, isPublic ? R.drawable.msg_archive_hide : R.drawable.msg_views,
                isPublic ? "Скрыть из профиля Libre" : "Показывать в профиле Libre", () -> {
            if (ref[0] != null) ref[0].dismiss();
            if (!org.telegram.messenger.PrizmaCloud.enabled()) {
                android.widget.Toast.makeText(context, "Это работает через сервер Libre — включите его в хабе Libre", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            PrizmaPlaylist.setPublic(isPublic ? 0 : p.id);
            android.widget.Toast.makeText(context, isPublic ? "Плейлист больше не виден в профиле"
                    : "«" + p.name + "» теперь видно в вашем профиле Libre — другие смогут послушать и сохранить его", android.widget.Toast.LENGTH_LONG).show();
        }));
        if (p.id != PrizmaPlaylist.DEFAULT_ID) {
            root.addView(menuRow(context, 0xffE8534B, R.drawable.msg_delete, "Удалить плейлист", () -> {
                AlertDialog.Builder cb = new AlertDialog.Builder(context);
                cb.setTitle("Удалить плейлист?");
                cb.setMessage(p.name);
                cb.setPositiveButton("Удалить", (d, w) -> {
                    PrizmaPlaylist.deletePlaylist(p.id);
                    if (ref[0] != null) ref[0].dismiss();
                    reopenHome(context);
                });
                cb.setNegativeButton("Отмена", null);
                cb.show();
            }));
        }

        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static void promptRename(Context context, long id, String cur) {
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle("Переименовать");
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(cur == null ? "" : cur);
        input.setSelection(input.getText().length());
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        FrameLayout wrap = new FrameLayout(context);
        wrap.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);
        wrap.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        b.setView(wrap);
        b.setPositiveButton("Сохранить", (d, w) -> {
            PrizmaPlaylist.renamePlaylist(id, input.getText().toString());
            reopenHome(context);
        });
        b.setNegativeButton("Отмена", null);
        b.show();
    }

    private static void reopenHome(Context context) {
        if (sheet != null) sheet.dismiss();
        show(context);
    }

    // ======================= one playlist =======================

    public static void openPlaylist(Context context, long id) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        PrizmaPlaylist.Playlist p = PrizmaPlaylist.get(id);
        if (p == null) { show(context); return; }
        ArrayList<PrizmaPlaylist.Track> tracks = PrizmaPlaylist.tracks(id);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));

        // top: back + ⋮
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(textColor);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setOnClickListener(v -> reopenHome(context));
        top.addView(back, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));
        top.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
        // find single tracks anywhere in Telegram and add them here
        ImageView find = new ImageView(context);
        find.setImageResource(R.drawable.msg_search);
        find.setColorFilter(textColor);
        find.setScaleType(ImageView.ScaleType.CENTER);
        find.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, AndroidUtilities.dp(18)));
        find.setOnClickListener(v -> openSearch(context, id));
        top.addView(find, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        ImageView more = new ImageView(context);
        more.setImageResource(R.drawable.ic_ab_other);
        more.setColorFilter(textColor);
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setOnClickListener(v -> playlistMenu(context, p));
        top.addView(more, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL));
        root.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        // header: big cover + name + count + play-all
        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int coverSz = AndroidUtilities.dp(96);
        FrameLayout cover = coverView(context, p.cover, p.name, coverSz, AndroidUtilities.dp(16));
        head.addView(cover, LayoutHelper.createLinear(96, 96, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        LinearLayout headTexts = new LinearLayout(context);
        headTexts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setText(p.name);
        name.setTextColor(textColor);
        name.setTextSize(22);
        name.setTypeface(AndroidUtilities.bold());
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headTexts.addView(name);
        TextView sub = new TextView(context);
        sub.setText(tracks.isEmpty() ? "пока пусто" : (tracks.size() + " " + plural(tracks.size())));
        sub.setTextColor(grayColor);
        sub.setTextSize(13);
        headTexts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        head.addView(headTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        if (!tracks.isEmpty()) {
            FrameLayout playAll = new FrameLayout(context);
            GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, PALETTE[(int) (Math.abs(id) % PALETTE.length)]);
            pg.setShape(GradientDrawable.OVAL);
            playAll.setBackground(pg);
            ImageView pi = new ImageView(context);
            PlayPauseDrawable d = new PlayPauseDrawable(24);
            d.setPause(false, false);
            pi.setImageDrawable(d);
            pi.setColorFilter(0xffffffff);
            pi.setScaleType(ImageView.ScaleType.CENTER);
            playAll.addView(pi, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            playAll.setOnClickListener(v -> playFrom(context, id, 0));
            head.addView(playAll, LayoutHelper.createLinear(50, 50, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }
        root.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // tracks — RecyclerView with drag-to-reorder
        int h = (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.48f);
        if (tracks.isEmpty()) {
            ScrollView scroll = new ScrollView(context);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setNestedScrollingEnabled(true);
            LinearLayout empty = (LinearLayout) emptyState(context, grayColor, id == PrizmaPlaylist.DEFAULT_ID);
            TextView findButton = new TextView(context);
            findButton.setText("Найти треки");
            findButton.setTextColor(0xffffffff);
            findButton.setTextSize(15);
            findButton.setTypeface(AndroidUtilities.bold());
            findButton.setGravity(Gravity.CENTER);
            findButton.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), 0);
            GradientDrawable fb = new GradientDrawable(GradientDrawable.Orientation.TL_BR, PALETTE[(int) (Math.abs(id) % PALETTE.length)]);
            fb.setCornerRadius(AndroidUtilities.dp(20));
            findButton.setBackground(fb);
            findButton.setOnClickListener(v -> openSearch(context, id));
            empty.addView(findButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER, 0, 16, 0, 0));
            scroll.addView(empty, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, h));
        } else {
            long curDid0 = 0; int curMid0 = 0;
            MessageObject c = MediaController.getInstance().getPlayingMessageObject();
            if (c != null) { curDid0 = c.getDialogId(); curMid0 = c.getId(); }
            final long curDid = curDid0;
            final int curMid = curMid0;
            final ArrayList<PrizmaPlaylist.Track> data = tracks; // mutable, reordered on drag

            final RecyclerView rv = new RecyclerView(context);
            rv.setLayoutManager(new LinearLayoutManager(context));
            rv.setVerticalScrollBarEnabled(false);
            final ItemTouchHelper[] helperRef = new ItemTouchHelper[1];

            final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
                    FrameLayout box = new FrameLayout(context);
                    box.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                    return new RecyclerView.ViewHolder(box) {};
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    FrameLayout box = (FrameLayout) holder.itemView;
                    box.removeAllViews();
                    // full width — without it the row wraps its text and the handle drifts after short titles
                    box.addView(buildReorderRow(context, id, data.get(position), textColor, grayColor,
                            curDid, curMid, holder, helperRef[0]), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }

                @Override
                public int getItemCount() {
                    return data.size();
                }
            };
            rv.setAdapter(adapter);

            ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                @Override
                public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                    int from = vh.getAdapterPosition();
                    int to = target.getAdapterPosition();
                    if (from < 0 || to < 0 || from >= data.size() || to >= data.size()) return false;
                    PrizmaPlaylist.Track m = data.remove(from);
                    data.add(to, m);
                    PrizmaPlaylist.moveTrack(id, from, to);
                    adapter.notifyItemMoved(from, to);
                    return true;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {}

                @Override
                public boolean isLongPressDragEnabled() {
                    return false; // drag via the handle only (long-press opens the menu)
                }

                @Override
                public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                    super.onSelectedChanged(viewHolder, actionState);
                    if (viewHolder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder.itemView.setAlpha(0.85f);
                        viewHolder.itemView.setScaleX(1.02f);
                        viewHolder.itemView.setScaleY(1.02f);
                    }
                }

                @Override
                public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder viewHolder) {
                    super.clearView(r, viewHolder);
                    viewHolder.itemView.setAlpha(1f);
                    viewHolder.itemView.setScaleX(1f);
                    viewHolder.itemView.setScaleY(1f);
                    // refresh the position numbers after a reorder (posted so we don't notify mid-layout)
                    r.post(() -> {
                        try { adapter.notifyItemRangeChanged(0, adapter.getItemCount()); } catch (Throwable ignore) {}
                    });
                }
            });
            helperRef[0] = helper;
            helper.attachToRecyclerView(rv);

            root.addView(rv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, h));
        }

        if (sheet != null) sheet.dismiss();
        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setCustomView(root);
        sheet = builder.create();
        // a fast fling up the track list must not read as "swipe the sheet away": the sheet only
        // follows the list's own overscroll (nested scrolling), i.e. once it's at the very top
        sheet.setCanDismissWithSwipe(false);
        try { sheet.fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        sheet.show();
    }

    /** Global music search; the playlist reopens with whatever was added. */
    private static void openSearch(Context context, long id) {
        if (sheet != null) sheet.dismiss();
        PrizmaMusicSearchSheet.show(context, id, () -> openPlaylist(context, id));
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private static View buildReorderRow(Context context, long plId, PrizmaPlaylist.Track tr,
                                        int textColor, int grayColor, long curDid, int curMid,
                                        RecyclerView.ViewHolder holder, ItemTouchHelper helper) {
        final boolean current = tr.did == curDid && tr.mid == curMid;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(9), AndroidUtilities.dp(6), AndroidUtilities.dp(9));
        int accent = PALETTE[(int) (Math.abs(plId) % PALETTE.length)][0];
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(14));
        bg.setColor(current ? ((accent & 0x00ffffff) | 0x22000000) : 0x00000000);
        row.setBackground(bg);

        FrameLayout lead = new FrameLayout(context);
        if (current) {
            PrizmaWaveBars bars = new PrizmaWaveBars(context);
            bars.setBarColor(accent);
            lead.addView(bars, LayoutHelper.createFrame(22, 18, Gravity.CENTER));
        } else {
            TextView num = new TextView(context);
            num.setText(String.valueOf(holder.getAdapterPosition() + 1));
            num.setTextColor(grayColor);
            num.setTextSize(14);
            num.setGravity(Gravity.CENTER);
            lead.addView(num, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        row.addView(lead, LayoutHelper.createLinear(28, 30, Gravity.CENTER_VERTICAL));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        // full title: wraps onto more lines instead of being cut at the first one
        TextView t = new TextView(context);
        t.setText(tr.title == null || tr.title.isEmpty() ? "Трек" : tr.title);
        t.setTextColor(current ? accent : textColor);
        t.setTextSize(15);
        t.setTypeface(AndroidUtilities.bold());
        t.setMaxLines(4);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setLineSpacing(AndroidUtilities.dp(1), 1f);
        texts.addView(t);
        TextView a = new TextView(context);
        a.setText(tr.author == null ? "" : tr.author);
        a.setTextColor(grayColor);
        a.setTextSize(12);
        a.setMaxLines(2);
        a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(a, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 6, 0));

        // drag handle
        ImageView handle = new ImageView(context);
        handle.setImageResource(R.drawable.list_reorder);
        handle.setColorFilter(grayColor);
        handle.setScaleType(ImageView.ScaleType.CENTER);
        handle.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && helper != null) {
                helper.startDrag(holder);
            }
            return false;
        });
        row.addView(handle, LayoutHelper.createLinear(34, 40, Gravity.CENTER_VERTICAL));

        row.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos < 0) return;
            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(new OvershootInterpolator(2f)).start()).start();
            playFrom(context, plId, pos);
        });
        row.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos < 0) return true;
            trackMenu(context, plId, pos, tr);
            return true;
        });
        return row;
    }

    // ===================== "+" in the player: choose playlists =====================

    /**
     * Glass sheet over the player: every playlist with a check — tap toggles the current track in it,
     * "Новый плейлист" creates one and puts the track there. onChanged runs after every change.
     */
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

    /** One row of the chooser: cover (or a glass "+" for a new playlist), name, count, check on the right. */
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

    /** Round check: an empty glass ring, or an accent disc with a tick. */
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

    private static void trackMenu(Context context, long plId, int index, PrizmaPlaylist.Track tr) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        root.addView(menuRow(context, textColor, R.drawable.msg_add, "Добавить в плейлист…", () -> {
            if (ref[0] != null) ref[0].dismiss();
            chooseTargetPlaylist(context, plId, index, tr);
        }));
        root.addView(menuRow(context, 0xffE8534B, R.drawable.msg_delete, "Убрать из этого плейлиста", () -> {
            PrizmaPlaylist.removeTrackAt(plId, index);
            if (ref[0] != null) ref[0].dismiss();
            openPlaylist(context, plId);
        }));

        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static void chooseTargetPlaylist(Context context, long fromId, int index, PrizmaPlaylist.Track tr) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        TextView head = new TextView(context);
        head.setText("Добавить в…");
        head.setTextColor(textColor);
        head.setTextSize(16);
        head.setTypeface(AndroidUtilities.bold());
        head.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        root.addView(head);

        // need a real MessageObject to add — rebuild from the source playlist, match by did/mid
        final ArrayList<MessageObject> src = PrizmaPlaylist.buildMessages(fromId);
        MessageObject moFound = null;
        for (MessageObject m : src) {
            if (m != null && m.getDialogId() == tr.did && m.getId() == tr.mid) {
                moFound = m;
                break;
            }
        }
        final MessageObject mo = moFound;

        for (PrizmaPlaylist.Playlist p : PrizmaPlaylist.playlists()) {
            if (p.id == fromId) continue;
            final long target = p.id;
            final String nm = p.name;
            root.addView(menuRow(context, textColor, R.drawable.msg_fave, nm, () -> {
                boolean ok = mo != null && PrizmaPlaylist.addTo(target, mo);
                if (ref[0] != null) ref[0].dismiss();
                android.widget.Toast.makeText(context, ok ? ("Добавлено в «" + nm + "»") : "Не удалось добавить", android.widget.Toast.LENGTH_SHORT).show();
            }));
        }
        root.addView(menuRow(context, textColor, R.drawable.msg_add, "Новый плейлист…", () -> {
            if (ref[0] != null) ref[0].dismiss();
            AlertDialog.Builder bb = new AlertDialog.Builder(context);
            bb.setTitle("Новый плейлист");
            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setHint("Название");
            input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            FrameLayout wrap = new FrameLayout(context);
            wrap.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);
            wrap.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            bb.setView(wrap);
            bb.setPositiveButton("Создать", (d, w) -> {
                long id = PrizmaPlaylist.createPlaylist(input.getText().toString(), "");
                boolean ok = mo != null && PrizmaPlaylist.addTo(id, mo);
                android.widget.Toast.makeText(context, ok ? "Добавлено" : "Создано", android.widget.Toast.LENGTH_SHORT).show();
            });
            bb.setNegativeButton("Отмена", null);
            bb.show();
        }));

        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static void playFrom(Context context, long plId, int index) {
        try {
            ArrayList<PrizmaPlaylist.Track> meta = PrizmaPlaylist.tracks(plId);
            if (meta.isEmpty() || index < 0 || index >= meta.size()) {
                android.widget.Toast.makeText(context, "Трек недоступен", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            PrizmaPlaylist.Track tr = meta.get(index);
            ArrayList<MessageObject> all = PrizmaPlaylist.buildMessages(plId);
            // match by did/mid — buildMessages may skip undecodable tracks, so indices can differ
            MessageObject target = null;
            for (MessageObject m : all) {
                if (m != null && m.getDialogId() == tr.did && m.getId() == tr.mid) {
                    target = m;
                    break;
                }
            }
            if (target == null || target.getDocument() == null || target.getDuration() <= 0) {
                android.widget.Toast.makeText(context, "Трек не сохранён полностью — открой его в чате и нажми ♥ заново", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            // loadMusic = false: play exactly this playlist (it used to pull in the rest of the music
            // from the chat the track came from), looping it like Spotify
            try { MediaController.getInstance().setPlaylist(all, target, 0, false, null); } catch (Throwable ignore) {}
            boolean ok = false;
            try { ok = MediaController.getInstance().playMessage(target); } catch (Throwable ignore) {}
            if (!ok) {
                android.widget.Toast.makeText(context, "Не удалось воспроизвести", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (sheet != null) sheet.dismiss();
            PrizmaFullPlayer.show(context);
        } catch (Throwable e) {
            FileLog.e(e);
            android.widget.Toast.makeText(context, "Ошибка", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ======================= cover picker =======================

    private static void pickCover(Context context, long id) {
        if (!(context instanceof Activity)) {
            android.widget.Toast.makeText(context, "Открой из приложения", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        pickCtx = context;
        coverTargetId = id;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            ((Activity) context).startActivityForResult(Intent.createChooser(intent, "Обложка плейлиста"), COVER_REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Routed from LaunchActivity.onActivityResult. */
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
            Context c = pickCtx != null ? pickCtx : activity;
            AndroidUtilities.runOnUIThread(() -> {
                if (sheet != null) sheet.dismiss();
                openPlaylist(c, coverTargetId);
            }, 120);
        } catch (Throwable e) {
            FileLog.e(e);
            android.widget.Toast.makeText(activity, "Не удалось загрузить обложку", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ======================= shared views/helpers =======================

    /** Rounded cover: the playlist image if set, otherwise a per-name gradient with a music glyph. */
    private static FrameLayout coverView(Context context, String path, String name, int size, int radius) {
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

    private static Bitmap decodeCover(String path, int reqPx) {
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

    private static LinearLayout menuRow(Context c, int color, int icon, String text, Runnable onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(13), AndroidUtilities.dp(14), AndroidUtilities.dp(13));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(color);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(iv, LayoutHelper.createLinear(26, 26, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(16);
        row.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static View emptyState(Context context, int grayColor, boolean isDefault) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, AndroidUtilities.dp(30), 0, AndroidUtilities.dp(30));

        ImageView heart = new ImageView(context);
        heart.setImageResource(R.drawable.media_like);
        heart.setColorFilter(grayColor);
        heart.setScaleType(ImageView.ScaleType.CENTER);
        box.addView(heart, LayoutHelper.createLinear(48, 48, Gravity.CENTER));

        TextView t = new TextView(context);
        t.setText("Пока пусто");
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        t.setTextSize(16);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        box.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 10, 0, 0));

        TextView d = new TextView(context);
        d.setText(isDefault ? "Открой трек в плеере и нажми ♥ — или найди любой трек" : "Найди любой трек или добавь долгим тапом из другого плейлиста");
        d.setTextColor(grayColor);
        d.setTextSize(13);
        d.setGravity(Gravity.CENTER);
        box.addView(d, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 4, 0, 0));
        return box;
    }

    private static String plural(int n) {
        int n100 = n % 100, n10 = n % 10;
        if (n100 >= 11 && n100 <= 14) return "треков";
        if (n10 == 1) return "трек";
        if (n10 >= 2 && n10 <= 4) return "трека";
        return "треков";
    }

    private static String plPlural(int n) {
        int n100 = n % 100, n10 = n % 10;
        if (n100 >= 11 && n100 <= 14) return "плейлистов";
        if (n10 == 1) return "плейлист";
        if (n10 >= 2 && n10 <= 4) return "плейлиста";
        return "плейлистов";
    }
}
