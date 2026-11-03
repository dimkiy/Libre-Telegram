package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaSocial;
import org.telegram.messenger.PrizmaWall;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.util.ArrayList;

public class PrizmaWallActivity extends BaseFragment {
    private static final int TYPE_NOTE = 0, TYPE_POST = 1, TYPE_COMMENT = 2, TYPE_EMPTY = 3, TYPE_LOADING = 4;
    private static final int MENU_SETTINGS = 1;
    private static final int MAX_POST = 2000, MAX_COMMENT = 500;

    private final long ownerId;
    private final long postId;

    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private View inputWrap;
    private EditTextBoldCursor input;
    private ImageView sendButton;
    private FrameLayout photoPreview;
    private ImageView photoPreviewImage;
    private String pendingPhoto;

    private final ArrayList<Object> items = new ArrayList<>();

    private PrizmaWall.Post post;
    private final ArrayList<PrizmaWall.Comment> comments = new ArrayList<>();
    private boolean postLoaded;
    private String postError;

    private Object animateIn;
    private PrizmaWall.Post settle;
    private long holdReloadUntil;
    private boolean reloadScheduled;

    private final Runnable cloudChanged = this::reloadSoon;
    private final Runnable delayedReload = () -> {
        reloadScheduled = false;
        reload();
    };

    private static final LruCache<String, Bitmap> photoCache = new LruCache<String, Bitmap>(20 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static class Note {
        final String text;
        Note(String t) {
            text = t;
        }
    }

    private static final Object EMPTY = new Object();
    private static final Object LOADING = new Object();

    public PrizmaWallActivity(long ownerId, long postId) {
        super();
        this.ownerId = ownerId;
        this.postId = postId;
    }

    private long me() {
        return getUserConfig().getClientUserId();
    }

    private boolean myWall() {
        return ownerId == me();
    }

    private boolean clanWall() {
        return ownerId < 0;
    }

    private boolean moderator() {
        if (myWall()) return true;
        PrizmaWall.Wall w = PrizmaWall.peek(ownerId);
        return w != null && w.moderator;
    }

    private String ownerFirstName() {
        TLRPC.User u = getMessagesController().getUser(ownerId);
        return u != null ? UserObject.getFirstName(u) : null;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        TLRPC.User owner = getMessagesController().getUser(ownerId);
        actionBar.setTitle(postId != 0 ? "Комментарии" : clanWall() ? "Стена клана" : "Стена");
        if (clanWall()) {
            org.telegram.messenger.PrizmaSocial.Clan c = org.telegram.messenger.PrizmaClans.mine(currentAccount);
            actionBar.setSubtitle(c != null && c.id == -ownerId ? c.name + " [" + c.tag + "]" : null);
        } else {
            actionBar.setSubtitle(owner != null ? (myWall() ? "Ваша стена" : UserObject.getUserName(owner)) : null);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_SETTINGS) {
                    showWallSettings();
                }
            }
        });
        if (myWall() && postId == 0) {
            ActionBarMenu menu = actionBar.createMenu();
            menu.addItem(MENU_SETTINGS, R.drawable.ic_ab_other);
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setItemAnimator(null);
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(10));
        listView.setAdapter(adapter = new Adapter());
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                maybeLoadMore();
            }
        });
        root.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        photoPreview = new FrameLayout(context);
        photoPreview.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        photoPreview.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), 0);
        photoPreviewImage = new ImageView(context);
        photoPreviewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundCorners(photoPreviewImage, 10);
        photoPreview.addView(photoPreviewImage, LayoutHelper.createFrame(64, 64, Gravity.LEFT | Gravity.TOP));
        TextView removePhoto = new TextView(context);
        removePhoto.setText("Убрать фото");
        removePhoto.setTextColor(getThemedColor(Theme.key_text_RedBold));
        removePhoto.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        removePhoto.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        removePhoto.setOnClickListener(v -> setPendingPhoto(null, true));
        photoPreview.addView(removePhoto, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 72, 0, 0, 0));
        photoPreview.setVisibility(View.GONE);
        root.addView(photoPreview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80));

        root.addView(inputWrap = inputBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = root;
        if (postId == 0) {
            if (PrizmaCloud.enabled()) PrizmaWall.load(currentAccount, ownerId, false, null);
        } else {
            PrizmaWall.Wall w = PrizmaWall.peek(ownerId);
            if (w != null) {
                for (PrizmaWall.Post p : w.posts) {
                    if (p.id == postId) {
                        post = p;
                        comments.addAll(p.last);
                        break;
                    }
                }
            }
            loadPost();
        }
        reload();
        return fragmentView;
    }

    private View inputBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.BOTTOM);
        bar.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        bar.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));

        final int iconColor = getThemedColor(Theme.key_chat_messagePanelIcons);
        if (postId == 0) {
            ImageView attach = new ImageView(context);
            attach.setImageResource(R.drawable.msg_addphoto);
            attach.setScaleType(ImageView.ScaleType.CENTER);
            attach.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
            attach.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, AndroidUtilities.dp(20)));
            attach.setOnClickListener(v -> PrizmaSocial.pickPhoto(getParentActivity(), path -> setPendingPhoto(path, true)));
            bar.addView(attach, LayoutHelper.createLinear(44, 44, Gravity.BOTTOM));
        }

        input = new EditTextBoldCursor(context);
        input.setHint(postId != 0 ? "Ваш комментарий…" : myWall() ? "Написать на своей стене…" : clanWall() ? "Написать клану…" : "Написать на стене…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        input.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorSize(AndroidUtilities.dp(20));
        input.setCursorWidth(1.5f);
        input.setBackground(null);
        input.setMaxLines(5);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(postId != 0 ? MAX_COMMENT : MAX_POST)});
        input.setPadding(AndroidUtilities.dp(postId == 0 ? 4 : 12), AndroidUtilities.dp(11), AndroidUtilities.dp(4), AndroidUtilities.dp(11));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updateSendButton();
            }
        });
        bar.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.BOTTOM));

        sendButton = new ImageView(context);
        sendButton.setImageResource(R.drawable.msg_send);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, AndroidUtilities.dp(20)));
        sendButton.setOnClickListener(v -> send());
        bar.addView(sendButton, LayoutHelper.createLinear(44, 44, Gravity.BOTTOM));
        updateSendButton();

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        View line = new View(context);
        line.setBackgroundColor(getThemedColor(Theme.key_divider));
        wrap.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        wrap.addView(bar);
        return wrap;
    }

    private boolean sendReady() {
        return input.getText().toString().trim().length() > 0 || pendingPhoto != null && postId == 0;
    }

    private void updateSendButton() {
        if (sendButton == null || input == null) return;
        boolean ready = sendReady();
        int c = ready ? getThemedColor(Theme.key_chat_messagePanelSend) : getThemedColor(Theme.key_chat_messagePanelIcons);
        sendButton.setColorFilter(new PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN));
        sendButton.animate().cancel();
        sendButton.setAlpha(ready ? 1f : 0.6f);
    }

    private void setPendingPhoto(String path, boolean deleteOld) {
        if (deleteOld && pendingPhoto != null && !pendingPhoto.equals(path)) {
            new File(pendingPhoto).delete();
        }
        pendingPhoto = path;
        if (photoPreview == null) return;
        if (path != null) {
            photoPreview.setVisibility(View.VISIBLE);
            loadPhoto(photoPreviewImage, path, AndroidUtilities.dp(64));
        } else {
            photoPreview.setVisibility(View.GONE);
            photoPreviewImage.setImageDrawable(null);
        }
        updateSendButton();
    }

    private void loadPost() {
        PrizmaWall.loadPost(currentAccount, postId, (p, list) -> {
            post = p;
            postLoaded = true;
            postError = null;
            comments.clear();
            comments.addAll(list);
            reloadSoon();
        }, code -> {
            postLoaded = true;
            postError = code == 404 ? "Запись удалена" : PrizmaWall.error(code, null);
            if (code == 404) post = null;
            reloadSoon();
        });
    }

    private void reloadSoon() {
        if (fragmentView == null) return;
        long wait = holdReloadUntil - SystemClock.elapsedRealtime();
        if (wait > 0) {
            if (!reloadScheduled) {
                reloadScheduled = true;
                AndroidUtilities.runOnUIThread(delayedReload, wait);
            }
            return;
        }
        reload();
    }

    private void reload() {
        items.clear();
        boolean canWrite = false;
        if (!PrizmaCloud.enabled()) {
            items.add(new Note("Стены работают через сервер Libre. Включите его: хаб Libre → «Сервер Libre»"));
        } else if (postId == 0) {
            PrizmaWall.Wall w = PrizmaWall.peek(ownerId);
            if (w == null || !w.loaded) {
                if (w != null && w.failed) {
                    items.add(new Note("Нет связи с сервером Libre. Потяните позже или зайдите снова"));
                } else {
                    items.add(LOADING);
                }
            } else if (w.notLibre) {
                String name = ownerFirstName();
                items.add(new Note(clanWall() ? "Этого клана больше нет"
                        : (name != null ? name : "Этот человек") + " пока не пользуется Libre. Стена появится, когда он установит Libre"));
            } else if (!myWall() && w.hidden) {
                items.add(new Note("Владелец скрыл стену"));
            } else {
                if (myWall() && w.hidden) items.add(new Note("Стена скрыта от других — её видите только вы"));
                if (!myWall() && !w.canPost) items.add(new Note(clanWall() ? "Писать на стене клана могут только его участники"
                        : w.who == PrizmaSocial.WALL_CONTACTS ? "Писать здесь могут только взаимные контакты владельца" : "Владелец закрыл стену для записей"));
                items.addAll(w.posts);
                if (w.posts.isEmpty()) items.add(EMPTY);
                if (w.hasMore) items.add(LOADING);
                canWrite = myWall() || w.canPost;
            }
        } else {
            if (post != null) {
                items.add(post);
                items.addAll(comments);
                if (postLoaded && comments.isEmpty()) items.add(EMPTY);
                if (!postLoaded) items.add(LOADING);
                canWrite = postLoaded && postError == null && PrizmaWall.canComment(postId);
                if (postError != null) items.add(new Note(postError));
            } else if (postError != null) {
                items.add(new Note(postError));
            } else {
                items.add(LOADING);
            }
        }
        if (inputWrap != null) {
            inputWrap.setVisibility(canWrite ? View.VISIBLE : View.GONE);
            if (!canWrite && photoPreview != null) photoPreview.setVisibility(View.GONE);
            else if (photoPreview != null && pendingPhoto != null) photoPreview.setVisibility(View.VISIBLE);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void maybeLoadMore() {
        if (postId != 0 || layoutManager == null) return;
        PrizmaWall.Wall w = PrizmaWall.peek(ownerId);
        if (w == null || !w.hasMore) return;
        if (layoutManager.findLastVisibleItemPosition() >= items.size() - 3) {
            PrizmaWall.load(currentAccount, ownerId, true, null);
        }
    }

    private void toast(String text) {
        if (getParentActivity() != null && text != null) {
            Toast.makeText(getParentActivity(), text, Toast.LENGTH_SHORT).show();
        }
    }

    private void send() {
        if (!sendReady()) {
            AndroidUtilities.shakeViewSpring(input);
            return;
        }
        final String text = input.getText().toString().trim();
        flySendButton();
        if (postId == 0) {
            final String photo = pendingPhoto;
            setPendingPhoto(null, false);
            input.setText("");
            PrizmaWall.Post draft = PrizmaWall.addPost(currentAccount, ownerId, text, photo, (p, err) -> {
                if (p != null) {
                    settle = p;
                } else {
                    toast(err);
                    if (input != null && input.length() == 0) input.setText(text);
                    if (photo != null && pendingPhoto == null) setPendingPhoto(photo, false);
                }
                reloadSoon();
            });
            animateIn = draft;
            holdReloadUntil = SystemClock.elapsedRealtime() + 480;
            reload();
            listView.scrollToPosition(0);
        } else {
            if (post == null) return;
            input.setText("");
            final PrizmaWall.Comment pending = new PrizmaWall.Comment();
            pending.id = -System.currentTimeMillis();
            pending.authorId = me();
            TLRPC.User self = getUserConfig().getCurrentUser();
            pending.name = self != null ? UserObject.getUserName(self) : "";
            pending.text = text;
            pending.date = System.currentTimeMillis() / 1000;
            pending.sending = true;
            comments.add(pending);
            animateIn = pending;
            holdReloadUntil = SystemClock.elapsedRealtime() + 380;
            reload();
            listView.smoothScrollToPosition(items.size() - 1);
            PrizmaWall.addComment(currentAccount, post, text, (c, err) -> {
                if (c != null) {
                    pending.id = c.id;
                    pending.date = c.date;
                    pending.sending = false;
                } else {
                    comments.remove(pending);
                    toast(err);
                    if (input != null && input.length() == 0) input.setText(text);
                }
                reloadSoon();
            });
        }
    }

    private void flySendButton() {
        if (sendButton == null) return;
        sendButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        sendButton.animate().cancel();
        sendButton.animate()
                .translationX(AndroidUtilities.dp(26)).translationY(-AndroidUtilities.dp(12)).alpha(0f)
                .setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_IN)
                .withEndAction(() -> {
                    sendButton.setTranslationX(-AndroidUtilities.dp(14));
                    sendButton.setTranslationY(AndroidUtilities.dp(6));
                    sendButton.animate()
                            .translationX(0).translationY(0).alpha(sendReady() ? 1f : 0.6f)
                            .setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK)
                            .withEndAction(null).start();
                }).start();
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        PrizmaCloud.addListener(cloudChanged);
        if (postId == 0 && PrizmaCloud.enabled()) PrizmaWall.get(currentAccount, ownerId);
        reload();
    }

    @Override
    public void onPause() {
        super.onPause();
        PrizmaCloud.removeListener(cloudChanged);
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        if (input != null) AndroidUtilities.hideKeyboard(input);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        PrizmaCloud.removeListener(cloudChanged);
        AndroidUtilities.cancelRunOnUIThread(delayedReload);
        if (pendingPhoto != null) {
            new File(pendingPhoto).delete();
        }
    }

    private View rowOf(Object item) {
        int i = items.indexOf(item);
        RecyclerView.ViewHolder h = i >= 0 ? listView.findViewHolderForAdapterPosition(i) : null;
        return h != null ? h.itemView : null;
    }

    private void animateOut(Object item) {
        View row = rowOf(item);
        if (row == null) {
            reload();
            return;
        }
        holdReloadUntil = SystemClock.elapsedRealtime() + 240;
        row.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_IN)
                .withEndAction(() -> {
                    row.setAlpha(1f);
                    row.setScaleX(1f);
                    row.setScaleY(1f);
                    reload();
                }).start();
    }

    private void showPostOptions(PrizmaWall.Post p, View anchor) {
        if (p.sending) return;
        ItemOptions o = ItemOptions.makeOptions(this, anchor);
        if (!TextUtils.isEmpty(p.text)) {
            o.add(R.drawable.msg_copy, "Копировать текст", () -> {
                AndroidUtilities.addToClipboard(p.text);
                toast("Текст скопирован");
            });
        }
        if (p.authorId != me()) {
            o.add(R.drawable.msg_report, "Пожаловаться", () -> {
                PrizmaWall.report(currentAccount, p);
                toast("Жалоба отправлена — модератор посмотрит запись");
            });
        }
        if (myWall() && p.authorId != me()) {
            o.add(R.drawable.msg_block, "Запретить писать на стене", () -> confirm(
                    "Запретить писать?",
                    authorName(p.authorId, p.name) + " больше не сможет писать на вашей стене, а его записи и комментарии пропадут.",
                    "Запретить", () -> {
                        rememberName(p.authorId, p.name);
                        PrizmaWall.setBlocked(currentAccount, p.authorId, true, err -> {
                            if (err != null) toast(err);
                            reloadSoon();
                        });
                    }));
        }
        if (moderator() || p.authorId == me()) {
            o.add(R.drawable.msg_delete, "Удалить запись", true, () -> confirm(
                    "Удалить запись?", "Запись исчезнет вместе с комментариями — у всех.", "Удалить", () ->
                            PrizmaWall.deletePost(currentAccount, p, err -> {
                                if (err != null) {
                                    toast(err);
                                } else if (postId != 0) {
                                    finishFragment();
                                } else {
                                    animateOut(p);
                                }
                            })));
        }
        o.show();
    }

    private void showCommentOptions(PrizmaWall.Comment c, View anchor) {
        if (post == null || c.sending) return;
        ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.msg_copy, "Копировать текст", () -> {
            AndroidUtilities.addToClipboard(c.text);
            toast("Текст скопирован");
        });
        if (moderator() || c.authorId == me()) {
            o.add(R.drawable.msg_delete, "Удалить комментарий", true, () ->
                    PrizmaWall.deleteComment(currentAccount, post, c, err -> {
                        if (err != null) {
                            toast(err);
                            return;
                        }
                        comments.remove(c);
                        animateOut(c);
                    }));
        }
        o.show();
    }

    private void showWallSettings() {
        PrizmaWall.Wall w = PrizmaWall.peek(ownerId);
        if (w == null || !w.loaded) {
            toast("Подождите, стена ещё загружается");
            return;
        }
        CharSequence[] rows = {
                "Кто может писать: " + PrizmaSocial.wallWhoName(w.who).toLowerCase(),
                w.hidden ? "Показать стену другим" : "Скрыть стену от других",
                "Запрещено писать" + (w.blocked.isEmpty() ? "" : " · " + w.blocked.size())};
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("Настройки стены")
                .setItems(rows, (d, which) -> {
                    if (which == 0) {
                        pickWho(w);
                    } else if (which == 1) {
                        saveSettings(w.who, !w.hidden);
                    } else {
                        showBlocked(w);
                    }
                })
                .show();
    }

    private void saveSettings(int who, boolean hidden) {
        PrizmaWall.saveSettings(currentAccount, who, hidden, err -> {
            if (err != null) toast(err);
            reloadSoon();
        });
    }

    private void pickWho(PrizmaWall.Wall w) {
        int[] values = {PrizmaSocial.WALL_ALL, PrizmaSocial.WALL_CONTACTS, PrizmaSocial.WALL_NOBODY};
        CharSequence[] rows = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            String name = values[i] == PrizmaSocial.WALL_CONTACTS ? "Только взаимные контакты" : PrizmaSocial.wallWhoName(values[i]);
            rows[i] = (values[i] == w.who ? "✓  " : "     ") + name;
        }
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("Кто может писать на стене")
                .setItems(rows, (d, which) -> saveSettings(values[which], w.hidden))
                .show();
    }

    private void showBlocked(PrizmaWall.Wall w) {
        final ArrayList<Long> ids = new ArrayList<>(w.blocked);
        if (ids.isEmpty()) {
            toast("Никому не запрещено писать на вашей стене");
            return;
        }
        CharSequence[] rows = new CharSequence[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            rows[i] = authorName(ids.get(i), names().getString("blocked_name_" + ids.get(i), null));
        }
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle("Нажмите, чтобы снова разрешить")
                .setItems(rows, (d, which) -> PrizmaWall.setBlocked(currentAccount, ids.get(which), false, err -> {
                    if (err != null) toast(err);
                    reloadSoon();
                }))
                .show();
    }

    private static SharedPreferences names() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_social", Context.MODE_PRIVATE);
    }

    private static void rememberName(long userId, String name) {
        if (!TextUtils.isEmpty(name)) names().edit().putString("blocked_name_" + userId, name).apply();
    }

    private void confirm(String title, String message, String action, Runnable onYes) {
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(action, (d, w) -> onYes.run())
                .setNegativeButton("Отмена", null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    private void openProfile(long userId) {
        if (getMessagesController().getUser(userId) == null) return;
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        presentFragment(new ProfileActivity(args));
    }

    private String authorName(long authorId, String fallback) {
        TLRPC.User u = authorId > 0 ? getMessagesController().getUser(authorId) : null;
        if (u != null) return UserObject.getUserName(u);
        return !TextUtils.isEmpty(fallback) ? fallback : "Пользователь Libre";
    }

    private void like(PrizmaWall.Post p, LikeButton button) {
        if (p.sending) return;
        PrizmaWall.toggleLike(currentAccount, p, () -> {
            if (button.getTag() == p) button.set(p.liked, p.likes, false);
        });
        button.set(p.liked, p.likes, true);
        button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public int getItemViewType(int position) {
            Object o = items.get(position);
            if (o instanceof Note) return TYPE_NOTE;
            if (o instanceof PrizmaWall.Post) return TYPE_POST;
            if (o instanceof PrizmaWall.Comment) return TYPE_COMMENT;
            if (o == LOADING) return TYPE_LOADING;
            return TYPE_EMPTY;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout f = new FrameLayout(parent.getContext());
            f.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(f);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            FrameLayout f = (FrameLayout) holder.itemView;
            f.animate().cancel();
            f.setAlpha(1f);
            f.setTranslationY(0);
            f.setScaleX(1f);
            f.setScaleY(1f);
            f.removeAllViews();
            Object o = items.get(position);
            Context ctx = f.getContext();
            if (o instanceof Note) {
                TextView t = new TextView(ctx);
                t.setText(((Note) o).text);
                t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
                t.setGravity(Gravity.CENTER);
                f.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 8, 24, 8));
            } else if (o instanceof PrizmaWall.Post) {
                PrizmaWall.Post p = (PrizmaWall.Post) o;
                f.addView(postCard(ctx, p), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 10, 5, 10, 5));
                float rest = p.sending ? 0.6f : 1f;
                if (animateIn == p) {
                    animateIn = null;
                    f.setAlpha(0f);
                    f.setTranslationY(-AndroidUtilities.dp(18));
                    f.setScaleX(0.94f);
                    f.setScaleY(0.94f);
                    f.setPivotY(0);
                    f.animate().alpha(rest).translationY(0).scaleX(1f).scaleY(1f)
                            .setDuration(440).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                } else if (settle == p) {
                    settle = null;
                    f.setAlpha(0.6f);
                    f.animate().alpha(1f).setDuration(240).start();
                } else {
                    f.setAlpha(rest);
                }
            } else if (o instanceof PrizmaWall.Comment) {
                PrizmaWall.Comment c = (PrizmaWall.Comment) o;
                f.addView(commentRow(ctx, c), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                float rest = c.sending ? 0.6f : 1f;
                if (animateIn == c) {
                    animateIn = null;
                    f.setAlpha(0f);
                    f.setTranslationY(AndroidUtilities.dp(28));
                    f.animate().alpha(rest).translationY(0)
                            .setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
                } else {
                    f.setAlpha(rest);
                }
            } else if (o == LOADING) {
                RadialProgressView progress = new RadialProgressView(ctx);
                progress.setSize(AndroidUtilities.dp(26));
                progress.setProgressColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                f.addView(progress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 72, Gravity.CENTER));
            } else {
                TextView t = new TextView(ctx);
                t.setText(postId != 0 ? "Комментариев пока нет. Напишите первым" : myWall()
                        ? "На стене пока пусто. Напишите первую запись — или дождитесь друзей"
                        : "Здесь пока пусто. Напишите первым");
                t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                t.setGravity(Gravity.CENTER);
                f.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 48, 32, 48));
            }
        }
    }

    private View postCard(Context ctx, PrizmaWall.Post p) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(AndroidUtilities.dp(16));
        card.setBackground(bg);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(avatar(ctx, p.authorId, p.name, 40), LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(ctx);
        name.setText(authorName(p.authorId, p.name));
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        name.setTypeface(AndroidUtilities.bold());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        TextView date = new TextView(ctx);
        date.setText(p.sending ? "Отправляется…" : LocaleController.formatDateAudio(p.date, true));
        date.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        names.addView(date);
        head.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        if (!p.sending) {
            ImageView more = new ImageView(ctx);
            more.setImageResource(R.drawable.ic_ab_other);
            more.setScaleType(ImageView.ScaleType.CENTER);
            more.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.SRC_IN));
            more.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, AndroidUtilities.dp(18)));
            more.setOnClickListener(v -> showPostOptions(p, v));
            head.addView(more, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, -8, 0));
        }
        head.setOnClickListener(v -> openProfile(p.authorId));
        card.addView(head);

        if (!TextUtils.isEmpty(p.text)) {
            TextView text = new TextView(ctx);
            text.setText(p.text);
            text.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            text.setLineSpacing(AndroidUtilities.dp(1.5f), 1f);
            text.setTextIsSelectable(postId != 0);
            card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }

        final LikeButton like = new LikeButton(ctx);
        like.setTag(p);
        like.set(p.liked, p.likes, false);
        like.setOnClickListener(v -> like(p, like));

        if (p.photoUrl != null || p.localPhoto != null) {
            card.addView(photoView(ctx, p, like), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(like, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        if (postId == 0) {
            TextView commentsButton = actionButton(ctx, R.drawable.msg_discuss, getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon),
                    p.comments == 0 ? "" : String.valueOf(p.comments));
            commentsButton.setOnClickListener(v -> openComments(p));
            actions.addView(commentsButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, Gravity.CENTER_VERTICAL));
        }
        card.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        if (postId == 0 && !p.last.isEmpty()) {
            View line = new View(ctx);
            line.setBackgroundColor(getThemedColor(Theme.key_divider));
            card.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 12, 0, 6));
            int from = Math.max(0, p.last.size() - 2);
            for (int i = from; i < p.last.size(); i++) {
                PrizmaWall.Comment c = p.last.get(i);
                TextView t = new TextView(ctx);
                SpannableStringBuilder sb = new SpannableStringBuilder();
                String who = authorName(c.authorId, c.name);
                sb.append(who).append("  ").append(c.text.replace('\n', ' '));
                sb.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_windowBackgroundWhiteBlackText)), 0, who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new org.telegram.ui.Components.TypefaceSpan(AndroidUtilities.bold()), 0, who.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                t.setText(sb);
                t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(0, AndroidUtilities.dp(3), 0, AndroidUtilities.dp(3));
                t.setOnClickListener(v -> openComments(p));
                card.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            if (p.comments > 2) {
                TextView all = new TextView(ctx);
                all.setText("Все комментарии · " + p.comments);
                all.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
                all.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                all.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(2));
                all.setOnClickListener(v -> openComments(p));
                card.addView(all, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }
        }
        return card;
    }

    private void openComments(PrizmaWall.Post p) {
        if (p.sending) return;
        presentFragment(new PrizmaWallActivity(ownerId, p.id));
    }

    private View photoView(Context ctx, PrizmaWall.Post p, LikeButton like) {
        FrameLayout frame = new FrameLayout(ctx);
        ImageView photo = new ImageView(ctx);
        photo.setAdjustViewBounds(true);
        photo.setMaxHeight(AndroidUtilities.dp(440));
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        roundCorners(photo, 12);
        String path = PrizmaWall.photoPath(p, () -> {
            int i = items.indexOf(p);
            if (i >= 0 && adapter != null) adapter.notifyItemChanged(i);
        });
        if (path != null) {
            loadPhoto(photo, path, AndroidUtilities.displaySize.x);
            frame.addView(photo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            GradientDrawable ph = new GradientDrawable();
            ph.setCornerRadius(AndroidUtilities.dp(12));
            ph.setColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), 0.06f));
            photo.setBackground(ph);
            frame.addView(photo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 220));
        }

        ImageView heart = new ImageView(ctx);
        heart.setImageResource(R.drawable.media_like_active);
        heart.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heart.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
        heart.setAlpha(0f);
        frame.addView(heart, LayoutHelper.createFrame(84, 84, Gravity.CENTER));

        GestureDetector detector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (p.sending) return true;
                heart.animate().cancel();
                heart.setAlpha(0f);
                heart.setScaleX(0.3f);
                heart.setScaleY(0.3f);
                heart.animate().alpha(0.95f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(260)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK)
                        .withEndAction(() -> heart.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f)
                                .setStartDelay(260).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_IN)
                                .withEndAction(null).start())
                        .start();
                if (!p.liked) like(p, like);
                return true;
            }
        });
        frame.setOnTouchListener((v, e) -> detector.onTouchEvent(e));
        return frame;
    }

    private TextView actionButton(Context ctx, int icon, int color, String count) {
        TextView t = new TextView(ctx);
        t.setText(count);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(count.isEmpty() ? 10 : 14), 0);
        t.setCompoundDrawablePadding(count.isEmpty() ? 0 : AndroidUtilities.dp(6));
        Drawable d = ctx.getResources().getDrawable(icon).mutate();
        d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        d.setBounds(0, 0, AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        t.setCompoundDrawables(d, null, null, null);
        t.setBackground(pill());
        return t;
    }

    private Drawable pill() {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(AndroidUtilities.dp(17));
        pill.setColor(Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), 0.05f));
        return new android.graphics.drawable.LayerDrawable(new Drawable[]{
                pill, Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 17, 17)});
    }

    private View commentRow(Context ctx, PrizmaWall.Comment c) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
        View av = avatar(ctx, c.authorId, c.name, 34);
        av.setOnClickListener(v -> openProfile(c.authorId));
        row.addView(av, LayoutHelper.createLinear(34, 34, Gravity.TOP, 6, 2, 10, 0));
        LinearLayout bubble = new LinearLayout(ctx);
        bubble.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(AndroidUtilities.dp(14));
        bubble.setBackground(bg);
        bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        TextView name = new TextView(ctx);
        name.setText(authorName(c.authorId, c.name));
        name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        name.setTypeface(AndroidUtilities.bold());
        bubble.addView(name);
        TextView text = new TextView(ctx);
        text.setText(c.text);
        text.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bubble.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        TextView date = new TextView(ctx);
        date.setText(c.sending ? "Отправляется…" : LocaleController.formatDateAudio(c.date, true));
        date.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        bubble.addView(date, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        bubble.setOnLongClickListener(v -> {
            showCommentOptions(c, v);
            return true;
        });
        row.addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        return row;
    }

    private View avatar(Context ctx, long authorId, String fallbackName, int sizeDp) {
        BackupImageView iv = new BackupImageView(ctx);
        iv.setRoundRadius(AndroidUtilities.dp(sizeDp / 2f));
        AvatarDrawable ad = new AvatarDrawable();
        TLRPC.User u = authorId > 0 ? getMessagesController().getUser(authorId) : null;
        if (u != null) {
            ad.setInfo(currentAccount, u);
            iv.setForUserOrChat(u, ad);
        } else {
            ad.setInfo(authorId, authorName(authorId, fallbackName), null);
            iv.setImageDrawable(ad);
        }
        return iv;
    }

    private class LikeButton extends View {
        private final Drawable heartOff, heartOn;
        private final AnimatedTextView.AnimatedTextDrawable count = new AnimatedTextView.AnimatedTextDrawable(false, true, true);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable background = pill();
        private final int colorOff, colorOn;
        private boolean liked;
        private float pop = 1f;
        private float burst = -1f;
        private ValueAnimator popAnimator, burstAnimator;

        LikeButton(Context ctx) {
            super(ctx);
            colorOff = getThemedColor(Theme.key_windowBackgroundWhiteGrayIcon);
            colorOn = getThemedColor(Theme.key_text_RedBold);
            heartOff = ctx.getResources().getDrawable(R.drawable.media_like).mutate();
            heartOff.setColorFilter(new PorterDuffColorFilter(colorOff, PorterDuff.Mode.SRC_IN));
            heartOn = ctx.getResources().getDrawable(R.drawable.media_like_active).mutate();
            heartOn.setColorFilter(new PorterDuffColorFilter(colorOn, PorterDuff.Mode.SRC_IN));
            count.setTextSize(AndroidUtilities.dp(14));
            count.setAnimationProperties(0.35f, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            count.setCallback(this);
            background.setCallback(this);
            dot.setColor(colorOn);
        }

        void set(boolean liked, int likes, boolean animated) {
            boolean became = animated && liked && !this.liked;
            boolean dropped = animated && !liked && this.liked;
            this.liked = liked;
            count.setText(likes > 0 ? String.valueOf(likes) : "", animated);
            count.setTextColor(liked ? colorOn : colorOff, animated);
            requestLayout();
            if (became) {
                animatePop(new float[]{1f, 0.72f, 1.28f, 1f}, 420);
                if (burstAnimator != null) burstAnimator.cancel();
                burstAnimator = ValueAnimator.ofFloat(0f, 1f);
                burstAnimator.setDuration(520);
                burstAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                burstAnimator.addUpdateListener(a -> {
                    burst = (float) a.getAnimatedValue();
                    invalidate();
                });
                burstAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        burst = -1f;
                        invalidate();
                    }
                });
                burstAnimator.start();
            } else if (dropped) {
                animatePop(new float[]{1f, 0.82f, 1f}, 240);
            } else {
                invalidate();
            }
        }

        private void animatePop(float[] values, long duration) {
            if (popAnimator != null) popAnimator.cancel();
            popAnimator = ValueAnimator.ofFloat(values);
            popAnimator.setDuration(duration);
            popAnimator.addUpdateListener(a -> {
                pop = (float) a.getAnimatedValue();
                invalidate();
            });
            popAnimator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float textW = count.getAnimateToWidth();
            int w = AndroidUtilities.dp(12) + AndroidUtilities.dp(20) + (textW > 0 ? AndroidUtilities.dp(6) + (int) Math.ceil(textW) + AndroidUtilities.dp(2) : AndroidUtilities.dp(-2)) + AndroidUtilities.dp(12);
            setMeasuredDimension(w, AndroidUtilities.dp(34));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            background.setBounds(0, 0, getWidth(), getHeight());
            background.draw(canvas);
            float cx = AndroidUtilities.dp(12) + AndroidUtilities.dp(10);
            float cy = getHeight() / 2f;
            if (burst >= 0f) {
                float r = AndroidUtilities.dp(8) + AndroidUtilities.dp(10) * burst;
                float size = AndroidUtilities.dp(2.2f) * (1f - burst * 0.7f);
                dot.setAlpha((int) (255 * (1f - burst)));
                for (int i = 0; i < 7; i++) {
                    double a = Math.PI * 2 * i / 7 - Math.PI / 2;
                    canvas.drawCircle(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, size, dot);
                }
            }
            int half = AndroidUtilities.dp(10);
            Drawable heart = liked ? heartOn : heartOff;
            canvas.save();
            canvas.scale(pop, pop, cx, cy);
            heart.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
            heart.draw(canvas);
            canvas.restore();
            int left = AndroidUtilities.dp(12) + AndroidUtilities.dp(20) + AndroidUtilities.dp(6);
            count.setBounds(left, 0, getWidth() - AndroidUtilities.dp(10), getHeight());
            count.draw(canvas);
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == count || who == background || super.verifyDrawable(who);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            background.setState(getDrawableState());
        }

        @Override
        public void invalidateDrawable(Drawable drawable) {
            if (drawable == count) requestLayout();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (popAnimator != null) popAnimator.cancel();
            if (burstAnimator != null) burstAnimator.cancel();
            pop = 1f;
            burst = -1f;
        }
    }

    private static void roundCorners(View v, int radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private static void loadPhoto(ImageView iv, String path, int targetPx) {
        Bitmap cached = photoCache.get(path);
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(path);
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bmp = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= targetPx) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                bmp = BitmapFactory.decodeFile(path, o);
            } catch (Throwable ignore) {
            }
            final Bitmap result = bmp;
            AndroidUtilities.runOnUIThread(() -> {
                if (result == null) return;
                photoCache.put(path, result);
                if (path.equals(iv.getTag())) iv.setImageBitmap(result);
            });
        });
    }
}
