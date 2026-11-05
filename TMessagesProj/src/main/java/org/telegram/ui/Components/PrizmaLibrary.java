package org.telegram.ui.Components;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaPlaylist;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;

public class PrizmaLibrary extends Dialog {
    private static final int BG = 0xff101114;
    private static final int SURFACE = 0xff1b1c21;
    private static final int TEXT = 0xfff2f1ee;
    private static final int TEXT_2 = 0x99f2f1ee;
    private static final int TEXT_3 = 0x66f2f1ee;
    private static final int PLAY_KEEP = 0, PLAY_ORDER = 1, PLAY_SHUFFLE = 2;

    private static PrizmaLibrary current;

    public static void open(Context context, long playlistId) {
        if (context == null) return;
        if (current != null) {
            if (playlistId != 0) current.showPlaylist(playlistId);
            return;
        }
        try {
            PrizmaLibrary d = new PrizmaLibrary(context, playlistId);
            d.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void reload() {
        if (current != null) current.refresh();
    }

    private final Context ctx;
    private final long startId;
    private FrameLayout root;
    private FrameLayout pages;
    private LinearLayout home;
    private PlaylistPage page;
    private HomeAdapter homeAdapter;
    private boolean closing;
    private final ArrayList<PrizmaPlaylist.Playlist> lists = new ArrayList<>();

    private PrizmaLibrary(Context context, long startId) {
        super(context, R.style.TransparentDialog);
        ctx = context;
        this.startId = startId;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = new FrameLayout(ctx);
        root.setBackgroundColor(BG);
        pages = new FrameLayout(ctx);
        root.addView(pages, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        home = buildHome();
        pages.addView(home, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        PrizmaGlass.fullBleed(getWindow());
        refresh();
        if (startId != 0) showPlaylist(startId);
        root.setTranslationY(AndroidUtilities.displaySize.y);
    }

    @Override
    public void show() {
        super.show();
        current = this;
        root.post(() -> new PrizmaSpring(root.getHeight(), 300f, 0.92f, root::setTranslationY).to(0f));
    }

    @Override
    public void onBackPressed() {
        if (PrizmaPopup.closeIfOpen()) return;
        if (page != null) {
            closePlaylist();
        } else {
            dismiss();
        }
    }

    @Override
    public void dismiss() {
        if (closing) return;
        closing = true;
        if (current == this) current = null;
        root.animate().cancel();
        root.animate().translationY(root.getHeight()).setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_IN)
                .withEndAction(() -> {
                    try {
                        super.dismiss();
                    } catch (Throwable ignore) {
                    }
                }).start();
    }

    private void refresh() {
        lists.clear();
        lists.addAll(PrizmaPlaylist.playlists());
        if (homeAdapter != null) homeAdapter.notifyDataSetChanged();
        if (page != null) page.reload();
    }

    private LinearLayout buildHome() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        FrameLayout bar = new FrameLayout(ctx);
        bar.addView(barButton(R.drawable.ic_ab_back, v -> dismiss()), LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        TextView title = new TextView(ctx);
        title.setText("Моя музыка");
        title.setTextColor(TEXT);
        title.setTextSize(21);
        title.setTypeface(AndroidUtilities.bold());
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 60, 0, 0, 0));
        box.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));

        RecyclerView grid = new RecyclerView(ctx);
        GridLayoutManager layout = new GridLayoutManager(ctx, 2);
        grid.setLayoutManager(layout);
        grid.setClipToPadding(false);
        grid.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(24) + AndroidUtilities.navigationBarHeight);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        homeAdapter = new HomeAdapter();
        grid.setAdapter(homeAdapter);
        box.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        return box;
    }

    private ImageView barButton(int icon, View.OnClickListener click) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(icon);
        v.setColorFilter(TEXT);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(Theme.createSelectorDrawable(0x22ffffff, 1, AndroidUtilities.dp(24)));
        v.setOnClickListener(click);
        return v;
    }

    private final class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(14));
            card.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(card) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LinearLayout card = (LinearLayout) holder.itemView;
            card.removeAllViews();
            if (position < lists.size()) {
                bindPlaylist(card, lists.get(position));
            } else {
                bindCreate(card);
            }
            card.setAlpha(0f);
            card.setTranslationY(AndroidUtilities.dp(14));
            card.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(240, 40L * position)).setDuration(300)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }

        @Override
        public int getItemCount() {
            return lists.size() + 1;
        }
    }

    private void bindPlaylist(LinearLayout card, PrizmaPlaylist.Playlist p) {
        int side = (AndroidUtilities.displaySize.x - AndroidUtilities.dp(44)) / 2;
        card.addView(new Mosaic(ctx, p, side), new LinearLayout.LayoutParams(side, side));
        TextView name = new TextView(ctx);
        name.setText(p.name);
        name.setTextColor(TEXT);
        name.setTextSize(15.5f);
        name.setTypeface(AndroidUtilities.bold());
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 9, 2, 0));
        TextView sub = new TextView(ctx);
        sub.setText(p.count + " " + PrizmaPlaylistSheet.plural(p.count));
        sub.setTextColor(TEXT_2);
        sub.setTextSize(13);
        card.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 1, 2, 0));
        final long id = p.id;
        card.setOnClickListener(v -> {
            bounce(v);
            showPlaylist(id);
        });
        card.setOnLongClickListener(v -> {
            playlistMenu(v, p, root);
            return true;
        });
    }

    private void bindCreate(LinearLayout card) {
        int side = (AndroidUtilities.displaySize.x - AndroidUtilities.dp(44)) / 2;
        FrameLayout tile = new FrameLayout(ctx);
        tile.setBackground(PrizmaShape.fill(SURFACE, AndroidUtilities.dp(34)));
        ImageView plus = new ImageView(ctx);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(TEXT_2);
        plus.setScaleType(ImageView.ScaleType.CENTER);
        tile.addView(plus, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        card.addView(tile, new LinearLayout.LayoutParams(side, side));
        TextView name = new TextView(ctx);
        name.setText("Новый плейлист");
        name.setTextColor(TEXT_2);
        name.setTextSize(15.5f);
        name.setTypeface(AndroidUtilities.bold());
        card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 9, 2, 0));
        card.setOnClickListener(v -> {
            bounce(v);
            promptName("Новый плейлист", "", "Создать", nm -> {
                long id = PrizmaPlaylist.createPlaylist(nm.isEmpty() ? "Новый плейлист" : nm, "");
                refresh();
                showPlaylist(id);
            });
        });
    }

    private static void bounce(View v) {
        v.animate().cancel();
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
    }

    private interface NameSink {
        void take(String name);
    }

    private void promptName(String head, String value, String action, NameSink sink) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(head);
        final EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("Название");
        input.setText(value);
        input.setSelection(input.getText().length());
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        FrameLayout wrap = new FrameLayout(ctx);
        wrap.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);
        wrap.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        b.setView(wrap);
        b.setPositiveButton(action, (d, w) -> sink.take(input.getText().toString().trim()));
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

    private void playlistMenu(View anchor, PrizmaPlaylist.Playlist p, ViewGroup host) {
        PrizmaPopup popup = new PrizmaPopup(host, 0xff24252b, TEXT);
        popup.item(R.drawable.msg_edit, "Переименовать", () -> promptName("Переименовать", p.name, "Сохранить", nm -> {
            PrizmaPlaylist.renamePlaylist(p.id, nm);
            refresh();
        }));
        popup.item(R.drawable.msg_photo_settings, "Сменить обложку", () -> PrizmaPlaylistSheet.pickCover(AndroidUtilities.findActivity(ctx), p.id));
        if (p.cover != null && p.cover.length() > 0) {
            popup.item(R.drawable.msg_delete, "Убрать обложку", () -> {
                PrizmaPlaylist.setCover(p.id, "");
                refresh();
            });
        }
        final boolean isPublic = PrizmaPlaylist.publicId() == p.id;
        popup.item(isPublic ? R.drawable.msg_archive_hide : R.drawable.msg_views, isPublic ? "Скрыть из профиля Libre" : "Показать в профиле Libre", () -> {
            if (!PrizmaCloud.enabled()) {
                Toast.makeText(ctx, "Это работает через сервер Libre. Включите его в настройках Libre", Toast.LENGTH_LONG).show();
                return;
            }
            PrizmaPlaylist.setPublic(isPublic ? 0 : p.id);
            Toast.makeText(ctx, isPublic ? "Плейлист больше не виден в профиле" : "«" + p.name + "» теперь видно в вашем профиле Libre", Toast.LENGTH_LONG).show();
        });
        if (p.id != PrizmaPlaylist.DEFAULT_ID) {
            popup.danger(R.drawable.msg_delete, "Удалить плейлист", () -> {
                AlertDialog.Builder b = new AlertDialog.Builder(ctx);
                b.setTitle("Удалить плейлист?");
                b.setMessage(p.name);
                b.setPositiveButton("Удалить", (d, w) -> {
                    PrizmaPlaylist.deletePlaylist(p.id);
                    if (page != null && page.id == p.id) closePlaylist();
                    refresh();
                });
                b.setNegativeButton("Отмена", null);
                b.show();
            });
        }
        popup.showAt(anchor, true);
    }

    private void showPlaylist(long id) {
        PrizmaPlaylist.Playlist p = PrizmaPlaylist.get(id);
        if (p == null) return;
        if (page != null) closePlaylist();
        page = new PlaylistPage(ctx, p);
        pages.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final PlaylistPage shown = page;
        float width = AndroidUtilities.displaySize.x;
        new PrizmaSpring(width, 380f, 0.92f, v -> {
            shown.setTranslationX(v);
            home.setTranslationX(-(width - v) * 0.22f);
            home.setAlpha(Math.max(0.2f, v / width));
        }).to(0f);
    }

    private void closePlaylist() {
        final PlaylistPage gone = page;
        page = null;
        if (gone == null) return;
        home.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        gone.animate().translationX(gone.getWidth()).setDuration(240).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> pages.removeView(gone)).start();
        refresh();
    }

    private void play(long id, String name, int trackIndex, int mode) {
        try {
            ArrayList<PrizmaPlaylist.Track> meta = PrizmaPlaylist.tracks(id);
            if (meta.isEmpty()) return;
            if (trackIndex < 0 || trackIndex >= meta.size()) trackIndex = 0;
            ArrayList<MessageObject> all = PrizmaPlaylist.buildMessages(id);
            if (mode == PLAY_SHUFFLE) {
                trackIndex = new java.util.Random().nextInt(meta.size());
            }
            PrizmaPlaylist.Track tr = meta.get(trackIndex);
            MessageObject target = null;
            for (MessageObject m : all) {
                if (m != null && m.getDialogId() == tr.did && m.getId() == tr.mid) {
                    target = m;
                    break;
                }
            }
            if (target == null || target.getDocument() == null || target.getDuration() <= 0) {
                Toast.makeText(ctx, "Трек сохранён не полностью. Откройте его в чате и добавьте заново", Toast.LENGTH_LONG).show();
                return;
            }
            if (mode == PLAY_SHUFFLE && !SharedConfig.shuffleMusic) {
                MediaController.getInstance().setPlaybackOrderType(2);
            } else if (mode == PLAY_ORDER && SharedConfig.shuffleMusic) {
                MediaController.getInstance().setPlaybackOrderType(0);
            }
            PrizmaPlaylist.setPlaying(id, name);
            if (!MediaController.getInstance().prizmaPlayList(all, target)) {
                Toast.makeText(ctx, "Не удалось воспроизвести", Toast.LENGTH_SHORT).show();
                return;
            }
            dismiss();
            PrizmaFullPlayer.show(ctx);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private final class Mosaic extends FrameLayout {
        private final android.graphics.Path clip = new android.graphics.Path();
        private final android.graphics.RectF box = new android.graphics.RectF();

        @Override
        public void draw(android.graphics.Canvas canvas) {
            box.set(0, 0, getWidth(), getHeight());
            PrizmaShape.leaf(clip, box, AndroidUtilities.dp(34), AndroidUtilities.dp(10), 0f);
            canvas.save();
            canvas.clipPath(clip);
            super.draw(canvas);
            canvas.restore();
        }

        Mosaic(Context c, PrizmaPlaylist.Playlist p, int side) {
            super(c);
            setBackgroundColor(SURFACE);
            Bitmap custom = PrizmaPlaylistSheet.decodeCover(p.cover, side);
            if (custom != null) {
                ImageView iv = new ImageView(c);
                iv.setImageBitmap(custom);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                addView(iv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                return;
            }
            ArrayList<MessageObject> first = PrizmaPlaylist.firstMessages(p.id, 4);
            if (first.isEmpty()) {
                int[] pair = PrizmaPlaylistSheet.PALETTE[Math.abs((p.name == null ? "" : p.name).hashCode() % PrizmaPlaylistSheet.PALETTE.length)];
                setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, pair));
                ImageView glyph = new ImageView(c);
                glyph.setImageResource(R.drawable.prizma_ic_music);
                glyph.setColorFilter(0x88ffffff);
                glyph.setScaleType(ImageView.ScaleType.CENTER);
                addView(glyph, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                return;
            }
            if (first.size() < 4) {
                BackupImageView one = new BackupImageView(c);
                addView(one, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                PrizmaTrackCover.bindSmall(one, first.get(0));
                return;
            }
            int half = side / 2;
            for (int i = 0; i < 4; i++) {
                BackupImageView part = new BackupImageView(c);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(half, half);
                lp.leftMargin = i % 2 == 0 ? 0 : half;
                lp.topMargin = i < 2 ? 0 : half;
                addView(part, lp);
                PrizmaTrackCover.bindSmall(part, first.get(i));
            }
        }
    }

    private final class PlaylistPage extends FrameLayout {
        final long id;
        private PrizmaPlaylist.Playlist playlist;
        private final ArrayList<PrizmaPlaylist.Track> tracks = new ArrayList<>();
        private final HashMap<Long, MessageObject> messages = new HashMap<>();
        private final RecyclerView list;
        private final PageAdapter adapter = new PageAdapter();
        private final ItemTouchHelper touchHelper;
        private final PrizmaPalette tone;

        PlaylistPage(Context c, PrizmaPlaylist.Playlist p) {
            super(c);
            id = p.id;
            playlist = p;
            Bitmap custom = PrizmaPlaylistSheet.decodeCover(p.cover, 96);
            int seed = custom != null ? PrizmaPalette.seedOf(custom) : 0;
            if (seed == 0) {
                seed = PrizmaPlaylistSheet.PALETTE[Math.abs((p.name == null ? "" : p.name).hashCode() % PrizmaPlaylistSheet.PALETTE.length)][0];
            }
            tone = PrizmaPalette.of(seed);
            setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{tone.backgroundTop, tone.backgroundBottom, BG}));

            list = new RecyclerView(c);
            list.setLayoutManager(new LinearLayoutManager(c));
            list.setClipToPadding(false);
            list.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56), 0, AndroidUtilities.dp(24) + AndroidUtilities.navigationBarHeight);
            list.setVerticalScrollBarEnabled(false);
            list.setOverScrollMode(OVER_SCROLL_NEVER);
            DefaultItemAnimator animator = new DefaultItemAnimator();
            animator.setSupportsChangeAnimations(false);
            list.setItemAnimator(animator);
            list.setAdapter(adapter);
            addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            FrameLayout bar = new FrameLayout(c);
            bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
            bar.addView(barButton(R.drawable.ic_ab_back, v -> closePlaylist()), LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
            ImageView add = barButton(R.drawable.msg_add, v -> PrizmaMusicSearchSheet.show(ctx, id, this::reload));
            add.setContentDescription("Найти и добавить треки");
            bar.addView(add, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 52, 0));
            ImageView more = barButton(R.drawable.ic_ab_other, v -> playlistMenu(v, playlist, root));
            bar.addView(more, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
            addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

            touchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
                @Override
                public int getMovementFlags(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
                    return h.getAdapterPosition() <= 0 ? makeMovementFlags(0, 0)
                            : makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.START);
                }

                @Override
                public boolean canDropOver(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder c, @NonNull RecyclerView.ViewHolder t) {
                    return t.getAdapterPosition() > 0;
                }

                @Override
                public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                    int a = from.getAdapterPosition() - 1, b = to.getAdapterPosition() - 1;
                    if (a < 0 || b < 0 || a >= tracks.size() || b >= tracks.size()) return false;
                    tracks.add(b, tracks.remove(a));
                    PrizmaPlaylist.moveTrack(id, a, b);
                    adapter.notifyItemMoved(a + 1, b + 1);
                    return true;
                }

                @Override
                public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {
                    int at = h.getAdapterPosition() - 1;
                    if (at < 0 || at >= tracks.size()) return;
                    tracks.remove(at);
                    PrizmaPlaylist.removeTrackAt(id, at);
                    adapter.notifyItemRemoved(at + 1);
                    adapter.notifyItemChanged(0);
                }

                @Override
                public boolean isLongPressDragEnabled() {
                    return false;
                }

                @Override
                public void onSelectedChanged(RecyclerView.ViewHolder h, int state) {
                    super.onSelectedChanged(h, state);
                    if (h != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                        h.itemView.setScaleX(1.03f);
                        h.itemView.setScaleY(1.03f);
                        h.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                }

                @Override
                public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
                    super.clearView(r, h);
                    h.itemView.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                }
            });
            touchHelper.attachToRecyclerView(list);
            reload();
        }

        void reload() {
            PrizmaPlaylist.Playlist fresh = PrizmaPlaylist.get(id);
            if (fresh != null) playlist = fresh;
            tracks.clear();
            tracks.addAll(PrizmaPlaylist.tracks(id));
            messages.clear();
            for (MessageObject m : PrizmaPlaylist.buildMessages(id)) {
                if (m != null) messages.put(keyOf(m.getDialogId(), m.getId()), m);
            }
            adapter.notifyDataSetChanged();
        }

        private long keyOf(long did, int mid) {
            return did * 1_000_003L + mid;
        }

        private String summary() {
            if (tracks.isEmpty()) return "Пока пусто";
            int seconds = 0;
            for (MessageObject m : messages.values()) seconds += (int) m.getDuration();
            int minutes = seconds / 60;
            String time = minutes >= 60 ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин";
            return tracks.size() + " " + PrizmaPlaylistSheet.plural(tracks.size()) + " · " + time;
        }

        private View header() {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
            int side = AndroidUtilities.dp(196);
            box.addView(new Mosaic(ctx, playlist, side), LayoutHelper.createLinear(196, 196, Gravity.CENTER_HORIZONTAL));
            TextView name = new TextView(ctx);
            name.setText(playlist.name);
            name.setTextColor(TEXT);
            name.setTextSize(25);
            name.setTypeface(AndroidUtilities.bold());
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            box.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
            TextView sub = new TextView(ctx);
            sub.setText(summary());
            sub.setTextColor(TEXT_2);
            sub.setTextSize(14);
            box.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 3, 0, 0));
            if (tracks.isEmpty()) {
                TextView find = pill("Найти треки", tone.accent, tone.onAccent);
                find.setOnClickListener(v -> PrizmaMusicSearchSheet.show(ctx, id, this::reload));
                box.addView(find, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));
                return box;
            }
            LinearLayout actions = new LinearLayout(ctx);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView listen = pill("Слушать", tone.accent, tone.onAccent);
            listen.setOnClickListener(v -> play(id, playlist.name, 0, PLAY_ORDER));
            actions.addView(listen, LayoutHelper.createLinear(0, 52, 1f, 0, 0, 5, 0));
            TextView mixed = pill("Вперемешку", tone.container, tone.onContainer);
            mixed.setOnClickListener(v -> play(id, playlist.name, 0, PLAY_SHUFFLE));
            actions.addView(mixed, LayoutHelper.createLinear(0, 52, 1f, 5, 0, 0, 0));
            box.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));
            return box;
        }

        private TextView pill(String text, int bg, int fg) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextColor(fg);
            t.setTextSize(15.5f);
            t.setTypeface(AndroidUtilities.bold());
            t.setGravity(Gravity.CENTER);
            t.setPadding(AndroidUtilities.dp(28), 0, AndroidUtilities.dp(28), 0);
            t.setBackground(PrizmaShape.fill(bg, AndroidUtilities.dp(26)));
            PrizmaGlass.pressable(t);
            return t;
        }

        private void rowMenu(View anchor, int at, PrizmaPlaylist.Track tr) {
            final MessageObject mo = messages.get(keyOf(tr.did, tr.mid));
            PrizmaPopup popup = new PrizmaPopup(root, 0xff24252b, TEXT);
            if (mo != null && MediaController.getInstance().getPlayingMessageObject() != null) {
                popup.item(R.drawable.msg_go_up, "Играть следующим", () -> MediaController.getInstance().prizmaPlayNext(mo));
            }
            if (mo != null) {
                popup.item(R.drawable.msg_fave, "В другой плейлист", () -> PrizmaPlaylistSheet.showAddToPlaylist(ctx, root, tone.accent, mo, null));
            }
            popup.danger(R.drawable.msg_delete, "Убрать из плейлиста", () -> {
                PrizmaPlaylist.removeTrackAt(id, at);
                reload();
            });
            popup.showAt(anchor, true);
        }

        private final class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            @Override
            public int getItemViewType(int position) {
                return position == 0 ? 0 : 1;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                View v;
                if (type == 0) {
                    v = new FrameLayout(ctx);
                    v.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                } else {
                    v = new TrackRow(ctx);
                    v.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(64)));
                }
                return new RecyclerView.ViewHolder(v) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (position == 0) {
                    FrameLayout f = (FrameLayout) holder.itemView;
                    f.removeAllViews();
                    f.addView(header(), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                } else {
                    ((TrackRow) holder.itemView).bind(tracks.get(position - 1), holder);
                }
            }

            @Override
            public int getItemCount() {
                return tracks.size() + 1;
            }
        }

        private final class TrackRow extends FrameLayout {
            final BackupImageView cover;
            final TextView title, author;
            final ImageView handle;
            final PrizmaWaveBars bars;
            final View shade;

            TrackRow(Context c) {
                super(c);
                cover = new BackupImageView(c);
                cover.setRoundRadius(AndroidUtilities.dp(11));
                addView(cover, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
                shade = new View(c);
                GradientDrawable d = new GradientDrawable();
                d.setColor(0x80000000);
                d.setCornerRadius(AndroidUtilities.dp(11));
                shade.setBackground(d);
                addView(shade, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
                bars = new PrizmaWaveBars(c);
                addView(bars, LayoutHelper.createFrame(24, 18, Gravity.LEFT | Gravity.CENTER_VERTICAL, 31, 0, 0, 0));
                LinearLayout texts = new LinearLayout(c);
                texts.setOrientation(LinearLayout.VERTICAL);
                title = new TextView(c);
                title.setTextSize(15.5f);
                title.setTypeface(AndroidUtilities.bold());
                title.setMaxLines(1);
                title.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(title);
                author = new TextView(c);
                author.setTextColor(TEXT_2);
                author.setTextSize(13);
                author.setMaxLines(1);
                author.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
                addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 78, 0, 52, 0));
                handle = new ImageView(c);
                handle.setImageResource(R.drawable.list_reorder);
                handle.setColorFilter(TEXT_3);
                handle.setScaleType(ImageView.ScaleType.CENTER);
                addView(handle, LayoutHelper.createFrame(52, LayoutHelper.MATCH_PARENT, Gravity.RIGHT));
            }

            @SuppressWarnings("ClickableViewAccessibility")
            void bind(PrizmaPlaylist.Track tr, RecyclerView.ViewHolder holder) {
                MessageObject mo = messages.get(keyOf(tr.did, tr.mid));
                MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                boolean current = playing != null && playing.getDialogId() == tr.did && playing.getId() == tr.mid;
                title.setText(TextUtils.isEmpty(tr.title) ? "Трек" : tr.title);
                title.setTextColor(current ? tone.accent : TEXT);
                author.setText(tr.author == null ? "" : tr.author);
                if (mo != null) {
                    PrizmaTrackCover.bindSmall(cover, mo);
                } else {
                    cover.setImageDrawable(PrizmaTrackCover.placeholder(null, AndroidUtilities.dp(10)));
                }
                shade.setVisibility(current ? VISIBLE : GONE);
                bars.setVisibility(current ? VISIBLE : GONE);
                bars.setBarColor(tone.accent);
                handle.setOnTouchListener((v, e) -> {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder);
                    return false;
                });
                setOnClickListener(v -> {
                    int at = holder.getAdapterPosition() - 1;
                    if (at >= 0) {
                        bounce(v);
                        play(id, playlist.name, at, PLAY_KEEP);
                    }
                });
                setOnLongClickListener(v -> {
                    int at = holder.getAdapterPosition() - 1;
                    if (at >= 0) rowMenu(v, at, tr);
                    return true;
                });
            }
        }
    }
}
