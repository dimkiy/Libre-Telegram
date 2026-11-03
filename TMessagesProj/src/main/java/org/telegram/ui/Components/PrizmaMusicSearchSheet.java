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
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.PrizmaPlaylist;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashSet;

public class PrizmaMusicSearchSheet {
    private PrizmaMusicSearchSheet() {}

    public static void show(Context context, long playlistId, Runnable onClose) {
        new Impl(context, playlistId, onClose).sheet.show();
    }

    private static class Impl {
        final int account = UserConfig.selectedAccount;
        final Context context;
        final long playlistId;
        final BottomSheet sheet;
        final EditTextBoldCursor field;
        final TextView status;
        final RadialProgressView progress;
        final RecyclerView list;
        final ArrayList<MessageObject> results = new ArrayList<>();
        final HashSet<String> seen = new HashSet<>();
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);

        String query = "";
        int requestId;
        int reqToken;
        boolean loading, endReached;
        int nextRate, lastId;
        TLRPC.InputPeer lastPeer;
        int added;
        int emptyPages;

        final Runnable searchLater = this::search;

        Impl(Context context, long playlistId, Runnable onClose) {
            this.context = context;
            this.playlistId = playlistId;
            sheet = new BottomSheet(context, true);
            sheet.setCanDismissWithSwipe(false);
            sheet.setOnDismissListener(d -> {
                AndroidUtilities.cancelRunOnUIThread(searchLater);
                if (requestId != 0) ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
                if (onClose != null) onClose.run();
            });

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

            TextView title = new TextView(context);
            PrizmaPlaylist.Playlist pl = PrizmaPlaylist.get(playlistId);
            title.setText(pl != null ? "Добавить в «" + pl.name + "»" : "Добавить треки");
            title.setTextColor(textColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
            title.setTypeface(AndroidUtilities.bold());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 12));

            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(14));
            bg.setColor(Theme.multAlpha(textColor, 0.06f));
            box.setBackground(bg);
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.msg_search);
            icon.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));
            icon.setScaleType(ImageView.ScaleType.CENTER);
            box.addView(icon, LayoutHelper.createLinear(40, 44, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
            field = new EditTextBoldCursor(context);
            field.setHint("Исполнитель или название трека");
            field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            field.setTextColor(textColor);
            field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
            field.setCursorColor(textColor);
            field.setCursorSize(AndroidUtilities.dp(20));
            field.setCursorWidth(1.5f);
            field.setBackground(null);
            field.setSingleLine(true);
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            field.setPadding(0, AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    AndroidUtilities.cancelRunOnUIThread(searchLater);
                    AndroidUtilities.runOnUIThread(searchLater, 450);
                }
            });
            field.setOnEditorActionListener((v, actionId, e) -> {
                AndroidUtilities.cancelRunOnUIThread(searchLater);
                search();
                AndroidUtilities.hideKeyboard(field);
                return true;
            });
            box.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            root.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            FrameLayout body = new FrameLayout(context);
            list = new RecyclerView(context);
            list.setLayoutManager(new LinearLayoutManager(context));
            list.setVerticalScrollBarEnabled(false);
            list.setAdapter(new Adapter());
            list.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                    if (lm != null && !loading && !endReached && !results.isEmpty() && lm.findLastVisibleItemPosition() >= results.size() - 4) {
                        loadMore();
                    }
                    if (dy > 0) AndroidUtilities.hideKeyboard(field);
                }
            });
            body.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            status = new TextView(context);
            status.setTextColor(grayColor);
            status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            status.setGravity(Gravity.CENTER);
            status.setText("Ищем по всей музыке в открытых каналах Telegram.\nНажмите на трек, чтобы послушать, «+» — чтобы добавить");
            body.addView(status, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 0));
            progress = new RadialProgressView(context);
            progress.setSize(AndroidUtilities.dp(26));
            progress.setProgressColor(grayColor);
            progress.setVisibility(View.GONE);
            body.addView(progress, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
            int h = (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.62f);
            root.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, h, 0, 10, 0, 0));

            sheet.setCustomView(root);
            AndroidUtilities.runOnUIThread(() -> {
                field.requestFocus();
                AndroidUtilities.showKeyboard(field);
            }, 250);
        }

        void search() {
            String q = field.getText().toString().trim();
            if (q.equals(query) && (loading || !results.isEmpty())) return;
            query = q;
            reqToken++;
            if (requestId != 0) {
                ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
                requestId = 0;
            }
            results.clear();
            seen.clear();
            emptyPages = 0;
            nextRate = 0;
            lastId = 0;
            lastPeer = null;
            endReached = false;
            loading = false;
            list.getAdapter().notifyDataSetChanged();
            if (q.length() < 2) {
                progress.setVisibility(View.GONE);
                status.setVisibility(View.VISIBLE);
                status.setText(q.isEmpty() ? "Ищем по всей музыке в открытых каналах Telegram.\nНажмите на трек, чтобы послушать, «+» — чтобы добавить" : "Введите хотя бы две буквы");
                return;
            }
            loadMore();
        }

        void loadMore() {
            if (loading || endReached || query.length() < 2) return;
            loading = true;
            if (results.isEmpty()) {
                status.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
            }
            final int token = reqToken;
            TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
            req.q = query;
            req.filter = new TLRPC.TL_inputMessagesFilterMusic();
            req.limit = 30;
            req.offset_rate = nextRate;
            req.offset_id = lastId;
            req.offset_peer = lastPeer != null ? lastPeer : new TLRPC.TL_inputPeerEmpty();
            requestId = ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (token != reqToken) return;
                requestId = 0;
                loading = false;
                progress.setVisibility(View.GONE);
                if (!(res instanceof TLRPC.messages_Messages)) {
                    if (results.isEmpty()) {
                        status.setVisibility(View.VISIBLE);
                        status.setText("Не получилось найти — проверьте интернет");
                    }
                    return;
                }
                TLRPC.messages_Messages r = (TLRPC.messages_Messages) res;
                MessagesController mc = MessagesController.getInstance(account);
                mc.putUsers(r.users, false);
                mc.putChats(r.chats, false);
                MessagesStorage.getInstance(account).putUsersAndChats(r.users, r.chats, true, true);
                int before = results.size();
                for (TLRPC.Message m : r.messages) {
                    MessageObject mo = new MessageObject(account, m, false, true);
                    if (!mo.isMusic()) continue;
                    String key = mo.getDialogId() + "_" + mo.getId();
                    TLRPC.Document doc = mo.getDocument();
                    String fileKey = doc != null ? "d" + doc.id : key;
                    if (!seen.add(key) || !seen.add(fileKey)) continue;
                    results.add(mo);
                }
                if (!r.messages.isEmpty()) {
                    TLRPC.Message last = r.messages.get(r.messages.size() - 1);
                    lastId = last.id;
                    nextRate = r.next_rate;
                    lastPeer = mc.getInputPeer(MessageObject.getDialogId(last));
                }
                endReached = r.messages.isEmpty() || r instanceof TLRPC.TL_messages_messages;
                if (results.size() > before) {
                    emptyPages = 0;
                    list.getAdapter().notifyItemRangeInserted(before, results.size() - before);
                } else if (!endReached && ++emptyPages < 3) {
                    loadMore();
                    return;
                }
                if (results.isEmpty()) {
                    status.setVisibility(View.VISIBLE);
                    status.setText("Ничего не нашлось. Попробуйте по-другому написать исполнителя или название");
                }
            }));
        }

        void toggle(MessageObject mo, ImageView button) {
            boolean in = PrizmaPlaylist.containsIn(playlistId, mo);
            boolean ok = in ? PrizmaPlaylist.removeFrom(playlistId, mo) : PrizmaPlaylist.addTo(playlistId, mo);
            if (!ok) return;
            added += in ? -1 : 1;
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            button.animate().cancel();
            button.animate().scaleX(0.6f).scaleY(0.6f).setDuration(110).withEndAction(() -> {
                setAddState(button, !in);
                button.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).withEndAction(null).start();
            }).start();
        }

        void setAddState(ImageView button, boolean in) {
            button.setImageResource(in ? R.drawable.ic_ab_done : R.drawable.msg_add);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(in ? accent : Theme.multAlpha(textColor, 0.07f));
            button.setBackground(d);
            button.setColorFilter(new PorterDuffColorFilter(in ? 0xffffffff : textColor, PorterDuff.Mode.SRC_IN));
        }

        void play(MessageObject mo) {
            MediaController mc = MediaController.getInstance();
            if (mc.isPlayingMessage(mo)) {
                if (mc.isMessagePaused()) mc.playMessage(mo);
                else mc.pauseMessage(mo);
            } else {
                mc.setPlaylist(new ArrayList<>(results), mo, 0);
            }
            AndroidUtilities.runOnUIThread(() -> list.getAdapter().notifyDataSetChanged(), 150);
        }

        private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                FrameLayout f = new FrameLayout(context);
                f.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(f) {};
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                FrameLayout f = (FrameLayout) holder.itemView;
                f.removeAllViews();
                MessageObject mo = results.get(position);
                boolean playing = MediaController.getInstance().isPlayingMessage(mo) && !MediaController.getInstance().isMessagePaused();

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8));
                row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 12, 12));

                FrameLayout lead = new FrameLayout(context);
                GradientDrawable lb = new GradientDrawable();
                lb.setCornerRadius(AndroidUtilities.dp(10));
                lb.setColor(Theme.multAlpha(textColor, 0.06f));
                lead.setBackground(lb);
                if (playing) {
                    PrizmaWaveBars bars = new PrizmaWaveBars(context);
                    bars.setBarColor(accent);
                    lead.addView(bars, LayoutHelper.createFrame(22, 18, Gravity.CENTER));
                } else {
                    ImageView note = new ImageView(context);
                    note.setImageResource(R.drawable.msg_played);
                    note.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));
                    note.setScaleType(ImageView.ScaleType.CENTER);
                    lead.addView(note, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                }
                row.addView(lead, LayoutHelper.createLinear(42, 42, Gravity.CENTER_VERTICAL));

                LinearLayout texts = new LinearLayout(context);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView t = new TextView(context);
                String title = mo.getMusicTitle();
                t.setText(TextUtils.isEmpty(title) ? "Без названия" : title);
                t.setTextColor(playing ? accent : textColor);
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                t.setTypeface(AndroidUtilities.bold());
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(t);
                TextView a = new TextView(context);
                String author = mo.getMusicAuthor();
                int dur = (int) mo.getDuration();
                a.setText((TextUtils.isEmpty(author) ? "Неизвестный исполнитель" : author) + (dur > 0 ? "  ·  " + AndroidUtilities.formatShortDuration(dur) : ""));
                a.setTextColor(grayColor);
                a.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(a, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
                row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 12, 0, 8, 0));

                ImageView add = new ImageView(context);
                add.setScaleType(ImageView.ScaleType.CENTER);
                setAddState(add, PrizmaPlaylist.containsIn(playlistId, mo));
                add.setOnClickListener(v -> toggle(mo, add));
                row.addView(add, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

                row.setOnClickListener(v -> play(mo));
                f.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }

            @Override
            public int getItemCount() {
                return results.size();
            }
        }
    }
}
