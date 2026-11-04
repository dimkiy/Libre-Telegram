package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Locale;

public class PrizmaQueuePanel extends LinearLayout {
    public interface Host {
        ViewGroup popupHost();

        PrizmaPalette palette();
    }

    private final Host host;
    private final EditTextBoldCursor field;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private final ItemTouchHelper touchHelper;
    private final GradientDrawable searchBg = new GradientDrawable();
    private final ImageView searchIcon, addButton, sortButton;
    private final TextView empty;
    private final ArrayList<MessageObject> queue = new ArrayList<>();
    private final ArrayList<MessageObject> shown = new ArrayList<>();
    private String query = "";
    private PrizmaPalette palette = PrizmaPalette.fallback();
    private int currentId;
    private long currentDialog;

    public PrizmaQueuePanel(Context context, Host host) {
        super(context);
        this.host = host;
        setOrientation(VERTICAL);

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout search = new LinearLayout(context);
        search.setOrientation(HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        searchBg.setCornerRadius(AndroidUtilities.dp(22));
        search.setBackground(searchBg);
        searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        search.addView(searchIcon, LayoutHelper.createLinear(40, 44, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        field = new EditTextBoldCursor(context);
        field.setHint("Поиск по очереди");
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setPadding(0, 0, AndroidUtilities.dp(12), 0);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setCursorSize(AndroidUtilities.dp(18));
        field.setCursorWidth(1.5f);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }
        });
        search.addView(field, LayoutHelper.createLinear(0, 44, 1f, Gravity.CENTER_VERTICAL));
        top.addView(search, LayoutHelper.createLinear(0, 44, 1f, Gravity.CENTER_VERTICAL));

        addButton = iconButton(R.drawable.msg_add);
        addButton.setContentDescription("Добавить в очередь");
        addButton.setOnClickListener(v -> PrizmaMusicSearchSheet.show(getContext(), 0, this::refresh));
        top.addView(addButton, LayoutHelper.createLinear(40, 44, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        sortButton = iconButton(R.drawable.prizma_ic_sort);
        sortButton.setContentDescription("Порядок очереди");
        sortButton.setOnClickListener(v -> showSort(v));
        top.addView(sortButton, LayoutHelper.createLinear(40, 44, Gravity.CENTER_VERTICAL));

        addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        FrameLayout body = new FrameLayout(context);
        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        list.setOverScrollMode(OVER_SCROLL_NEVER);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setMoveDuration(220);
        list.setItemAnimator(animator);
        list.setAdapter(adapter);
        body.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        empty = new TextView(context);
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(GONE);
        body.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 40, 24, 0));
        addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        touchHelper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
                int pos = h.getAdapterPosition();
                boolean head = pos <= 0 || pos < shown.size() && isCurrent(shown.get(pos));
                if (!query.isEmpty() || head) return makeMovementFlags(0, 0);
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.START);
            }

            @Override
            public boolean canDropOver(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder c, @NonNull RecyclerView.ViewHolder t) {
                return t.getAdapterPosition() > 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                int a = from.getAdapterPosition(), b = to.getAdapterPosition();
                if (a <= 0 || b <= 0 || a >= shown.size() || b >= shown.size()) return false;
                shown.add(b, shown.remove(a));
                queue.clear();
                queue.addAll(shown);
                MediaController.getInstance().prizmaMoveInQueue(a, b);
                adapter.notifyItemMoved(a, b);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {
                int at = h.getAdapterPosition();
                if (at < 0 || at >= shown.size()) return;
                MessageObject mo = shown.remove(at);
                queue.remove(mo);
                MediaController.getInstance().prizmaRemoveFromQueue(mo);
                adapter.notifyItemRemoved(at);
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
                    h.itemView.setBackground(rowLifted());
                    h.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
                super.clearView(r, h);
                h.itemView.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                h.itemView.setBackground(null);
            }
        });
        touchHelper.attachToRecyclerView(list);
    }

    private ImageView iconButton(int icon) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.CENTER);
        PrizmaGlass.pressable(v);
        return v;
    }

    private GradientDrawable rowLifted() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x1affffff);
        d.setCornerRadius(AndroidUtilities.dp(16));
        return d;
    }

    public void setPalette(PrizmaPalette p) {
        palette = p;
        searchBg.setColor(0x1affffff);
        int text = 0xfff2f1ee, sub = 0x99f2f1ee;
        searchIcon.setColorFilter(new PorterDuffColorFilter(sub, PorterDuff.Mode.SRC_IN));
        addButton.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
        sortButton.setColorFilter(new PorterDuffColorFilter(text, PorterDuff.Mode.SRC_IN));
        field.setTextColor(text);
        field.setHintTextColor(sub);
        field.setCursorColor(p.accent);
        empty.setTextColor(sub);
        adapter.notifyDataSetChanged();
    }

    public void refresh() {
        queue.clear();
        queue.addAll(MediaController.getInstance().prizmaQueue());
        MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
        currentId = cur == null ? 0 : cur.getId();
        currentDialog = cur == null ? 0 : cur.getDialogId();
        applyFilter();
    }

    public void scrollToStart() {
        list.scrollToPosition(0);
    }

    public void hideKeyboard() {
        AndroidUtilities.hideKeyboard(field);
        field.clearFocus();
    }

    private boolean isCurrent(MessageObject mo) {
        return mo != null && mo.getId() == currentId && mo.getDialogId() == currentDialog;
    }

    private void applyFilter() {
        shown.clear();
        for (MessageObject mo : queue) {
            if (query.isEmpty() || matches(mo)) shown.add(mo);
        }
        adapter.notifyDataSetChanged();
        boolean none = shown.isEmpty();
        empty.setVisibility(none ? VISIBLE : GONE);
        empty.setText(query.isEmpty() ? "Очередь пуста. Нажмите плюс, чтобы найти треки." : "В очереди нет такого трека");
    }

    private boolean matches(MessageObject mo) {
        String t = mo.getMusicTitle(), a = mo.getMusicAuthor();
        return t != null && t.toLowerCase(Locale.getDefault()).contains(query)
                || a != null && a.toLowerCase(Locale.getDefault()).contains(query);
    }

    private void showSort(View anchor) {
        MediaController mc = MediaController.getInstance();
        new PrizmaPopup(host.popupHost(), PrizmaPalette.mix(palette.surface, 0xffffffff, 0.04f), 0xfff2f1ee)
                .item(R.drawable.msg_list, "По названию", () -> { mc.prizmaSortQueue((a, b) -> text(a.getMusicTitle()).compareToIgnoreCase(text(b.getMusicTitle()))); refresh(); })
                .item(R.drawable.msg_groups, "По исполнителю", () -> { mc.prizmaSortQueue((a, b) -> text(a.getMusicAuthor()).compareToIgnoreCase(text(b.getMusicAuthor()))); refresh(); })
                .item(R.drawable.msg_autodelete, "По длительности", () -> { mc.prizmaSortQueue((a, b) -> Double.compare(a.getDuration(), b.getDuration())); refresh(); })
                .item(R.drawable.player_new_shuffle, "Перемешать", () -> { java.util.Random r = new java.util.Random(); java.util.HashMap<MessageObject, Integer> keys = new java.util.HashMap<>(); mc.prizmaSortQueue((a, b) -> Integer.compare(key(keys, a, r), key(keys, b, r))); refresh(); })
                .item(R.drawable.msg_go_up, "В обратном порядке", () -> { mc.prizmaSortQueue(null); refresh(); })
                .showAt(anchor, true);
    }

    private static int key(java.util.HashMap<MessageObject, Integer> keys, MessageObject mo, java.util.Random r) {
        Integer k = keys.get(mo);
        if (k == null) {
            k = r.nextInt();
            keys.put(mo, k);
        }
        return k;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private void showRowMenu(View anchor, MessageObject mo) {
        PrizmaPopup popup = new PrizmaPopup(host.popupHost(), PrizmaPalette.mix(palette.surface, 0xffffffff, 0.04f), 0xfff2f1ee);
        if (!isCurrent(mo)) {
            popup.item(R.drawable.msg_go_up, "Играть следующим", () -> {
                MediaController.getInstance().prizmaPlayNext(mo);
                refresh();
            });
        }
        popup.item(R.drawable.msg_fave, "В плейлист", () -> PrizmaPlaylistSheet.showAddToPlaylist(getContext(), host.popupHost(), palette.accent, mo, null));
        if (!isCurrent(mo)) {
            popup.danger(R.drawable.msg_delete, "Убрать из очереди", () -> {
                MediaController.getInstance().prizmaRemoveFromQueue(mo);
                refresh();
            });
        }
        popup.showAt(anchor, true);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Row row = new Row(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, AndroidUtilities.dp(62)));
            return new RecyclerView.ViewHolder(row) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((Row) holder.itemView).bind(shown.get(position), holder);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    private final class Row extends FrameLayout {
        final BackupImageView cover;
        final TextView title, author;
        final ImageView handle;
        final PrizmaWaveBars bars;
        final View shade;
        MessageObject item;

        Row(Context c) {
            super(c);
            cover = new BackupImageView(c);
            cover.setRoundRadius(AndroidUtilities.dp(11));
            addView(cover, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
            shade = new View(c);
            GradientDrawable d = new GradientDrawable();
            d.setColor(0x80000000);
            d.setCornerRadius(AndroidUtilities.dp(11));
            shade.setBackground(d);
            addView(shade, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
            bars = new PrizmaWaveBars(c);
            addView(bars, LayoutHelper.createFrame(24, 18, Gravity.LEFT | Gravity.CENTER_VERTICAL, 15, 0, 0, 0));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setTextSize(15.5f);
            title.setTypeface(AndroidUtilities.bold());
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            author = new TextView(c);
            author.setTextColor(0x8cf2f1ee);
            author.setTextSize(13);
            author.setMaxLines(1);
            author.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 62, 0, 44, 0));

            handle = new ImageView(c);
            handle.setImageResource(R.drawable.list_reorder);
            handle.setColorFilter(0x66f2f1ee);
            handle.setScaleType(ImageView.ScaleType.CENTER);
            addView(handle, LayoutHelper.createFrame(44, LayoutHelper.MATCH_PARENT, Gravity.RIGHT));
        }

        @SuppressWarnings("ClickableViewAccessibility")
        void bind(MessageObject mo, RecyclerView.ViewHolder holder) {
            item = mo;
            boolean current = isCurrent(mo);
            String t = mo.getMusicTitle(), a = mo.getMusicAuthor();
            title.setText(TextUtils.isEmpty(t) ? "Неизвестный трек" : t);
            title.setTextColor(current ? palette.accent : 0xfff2f1ee);
            author.setText(a == null ? "" : a);
            PrizmaTrackCover.bindSmall(cover, mo);
            shade.setVisibility(current ? VISIBLE : GONE);
            bars.setVisibility(current ? VISIBLE : GONE);
            bars.setBarColor(palette.accent);
            handle.setVisibility(current || !query.isEmpty() ? INVISIBLE : VISIBLE);
            handle.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder);
                }
                return false;
            });
            setOnClickListener(v -> {
                if (isCurrent(item)) return;
                hideKeyboard();
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).withEndAction(() ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start()).start();
                MediaController.getInstance().prizmaPlayFromQueue(item);
            });
            setOnLongClickListener(v -> {
                showRowMenu(v, item);
                return true;
            });
        }
    }
}
