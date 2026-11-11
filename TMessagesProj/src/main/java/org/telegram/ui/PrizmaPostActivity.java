package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaTranslate;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.PrizmaFeedIcons;
import org.telegram.ui.Components.PrizmaFeedKit;
import org.telegram.ui.Components.PrizmaGlassFrame;
import org.telegram.ui.Components.PrizmaPostActions;
import org.telegram.ui.Components.PrizmaPostCell;
import org.telegram.ui.Components.PrizmaPostMedia;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

public class PrizmaPostActivity extends BaseFragment implements PrizmaPostCell.Host {
    private static final int SORT_TOP = 0;
    private static final int SORT_NEW = 1;
    private static final int SORT_OLD = 2;

    private final ArrayList<MessageObject> group;
    private final MessageObject post;
    private final HashMap<String, TLRPC.TL_textWithEntities> translations;
    private final ArrayList<ArrayList<MessageObject>> replies = new ArrayList<>();
    private final ArrayList<ArrayList<MessageObject>> shown = new ArrayList<>();
    private final HashMap<Integer, MessageObject> byId = new HashMap<>();
    private final HashSet<String> expanded = new HashSet<>();

    private TLRPC.TL_messages_discussionMessage discussion;
    private TLRPC.Message threadTop;
    private boolean loadingReplies;
    private boolean endReached;
    private boolean noComments;
    private int sort = SORT_TOP;
    private int minId = Integer.MAX_VALUE;
    private boolean showOriginal;

    private RecyclerListView list;
    private Adapter adapter;
    private LinearLayout bigPost;
    private PrizmaPostActions bigActions;
    private TextView dateLine;
    private LinearLayout chips;
    private HorizontalScrollView chipsScroll;
    private LinkSpanDrawable.LinksTextView bigText;
    private TextView bigTranslate;
    private TextView follow;
    private LinearLayout composer;
    private EditTextBoldCursor input;
    private ImageView send;
    private TextView replyBanner;
    private MessageObject replyTarget;

    public PrizmaPostActivity(ArrayList<MessageObject> group, HashMap<String, TLRPC.TL_textWithEntities> translations) {
        super();
        this.group = group;
        this.post = group.get(0);
        this.translations = translations != null ? translations : new HashMap<>();
    }

    @Override
    public BaseFragment fragment() {
        return this;
    }

    @Override
    public void openPost(ArrayList<MessageObject> g) {
        openThread(g.get(0).getId());
    }

    @Override
    public void reply(ArrayList<MessageObject> g) {
        startReply(g.get(0));
    }

    @Override
    public void showMenu(ArrayList<MessageObject> g) {
    }

    @Override
    public boolean isExpanded(String key) {
        return true;
    }

    @Override
    public void expand(String key) {
        expanded.add(key);
    }

    @Override
    public boolean isSuggested(long chatId) {
        return false;
    }

    @Override
    public boolean isTrending(MessageObject mo) {
        return false;
    }

    @Override
    public TLRPC.TL_textWithEntities translation(String key) {
        return translations.get(key);
    }

    @Override
    public void putTranslation(String key, TLRPC.TL_textWithEntities text) {
        translations.put(key, text);
    }

    @Override
    public void changed(PrizmaPostCell cell) {
        adapter.notifyDataSetChanged();
    }

    private TLRPC.Chat channel() {
        return getMessagesController().getChat(-post.getDialogId());
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

        PrizmaGlassFrame root = new PrizmaGlassFrame(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        int barHeight = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(52);

        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setPadding(0, barHeight, 0, AndroidUtilities.dp(12));
        buildBigPost(context);
        list.setAdapter(adapter = new Adapter(context));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3) loadReplies();
                root.invalidate();
            }
        });
        root.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.setSource(list);

        FrameLayout bar = new FrameLayout(context);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        bar.setClickable(true);
        ImageView back = new ImageView(context);
        back.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.BACK, 24).setColor(ink));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, AndroidUtilities.dp(20)));
        back.setOnClickListener(v -> finishFragment());
        PrizmaFeedKit.press(back, 0.84f);
        bar.addView(back, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        TextView title = new TextView(context);
        title.setText("Пост");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(ink);
        bar.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 68, 0, 0, 0));
        ImageView open = new ImageView(context);
        open.setImageResource(org.telegram.messenger.R.drawable.msg_openin);
        open.setColorFilter(ink);
        open.setScaleType(ImageView.ScaleType.CENTER);
        open.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, AndroidUtilities.dp(20)));
        open.setOnClickListener(v -> PrizmaFeedKit.openPost(this, post));
        PrizmaFeedKit.press(open, 0.84f);
        bar.addView(open, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        root.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        bar.getLayoutParams().height = barHeight;
        root.addEdgePane(bar);

        composer = buildComposer(context);
        root.addView(composer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        root.addBottomEdgePane(composer);
        composer.setVisibility(PrizmaFeedKit.hasComments(post.messageOwner) ? View.VISIBLE : View.GONE);
        composer.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int bottom = (composer.getVisibility() == View.VISIBLE ? b - t : 0) + AndroidUtilities.dp(12);
            if (list.getPaddingBottom() != bottom) list.setPadding(0, barHeight, 0, bottom);
        });

        fragmentView = root;
        refreshCounters();
        if (PrizmaFeedKit.hasComments(post.messageOwner)) {
            loadDiscussion();
        } else {
            noComments = true;
        }
        return root;
    }

    @Override
    public boolean isLightStatusBar() {
        return androidx.core.graphics.ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundWhite)) > 0.7f;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            bindBigState(false);
            adapter.notifyDataSetChanged();
        }
    }

    private void buildBigPost(Context context) {
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int blue = Theme.getColor(Theme.key_featuredStickers_addButton);
        TLRPC.Chat chat = channel();
        TLRPC.Message m = post.messageOwner;

        bigPost = new LinearLayout(context);
        bigPost.setOrientation(LinearLayout.VERTICAL);
        bigPost.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), 0);

        LinearLayout author = new LinearLayout(context);
        author.setOrientation(LinearLayout.HORIZONTAL);
        author.setGravity(Gravity.CENTER_VERTICAL);
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(22));
        AvatarDrawable ad = new AvatarDrawable();
        ad.setInfo(currentAccount, chat);
        avatar.setForUserOrChat(chat, ad);
        author.addView(avatar, LayoutHelper.createLinear(44, 44));
        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(context);
        name.setText(chat != null ? chat.title : "");
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(ink);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        nameRow.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (chat != null && chat.verified) {
            ImageView v = new ImageView(context);
            v.setImageDrawable(PrizmaFeedKit.verified(context));
            nameRow.addView(v, LayoutHelper.createLinear(16, 16, 3, 0, 0, 0));
            ((LinearLayout.LayoutParams) name.getLayoutParams()).width = LayoutHelper.WRAP_CONTENT;
            ((LinearLayout.LayoutParams) name.getLayoutParams()).weight = 0;
        }
        names.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView handle = new TextView(context);
        String h = PrizmaFeedKit.handle(chat);
        int subs = chat != null ? chat.participants_count : 0;
        handle.setText(h != null ? h : subs > 0 ? LocaleController.formatShortNumber(subs, null) + " подписчиков" : "Канал");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        handle.setTextColor(gray);
        handle.setSingleLine(true);
        names.addView(handle);
        author.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 8, 0));
        follow = new TextView(context);
        follow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        follow.setTypeface(AndroidUtilities.bold());
        follow.setGravity(Gravity.CENTER);
        follow.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        author.addView(follow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));
        styleFollow();
        PrizmaFeedKit.press(follow, 0.92f);
        follow.setOnClickListener(v -> {
            TLRPC.Chat c = channel();
            if (c == null) return;
            if (ChatObject.isNotInChat(c)) {
                getMessagesController().addUserToChat(c.id, getUserConfig().getCurrentUser(), 0, null, this, null);
                c.left = false;
                styleFollow();
                return;
            }
            new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                    .setTitle("Перестать читать?")
                    .setMessage("Вы выйдете из канала. В закрытый канал без приглашения вернуться не получится.")
                    .setPositiveButton("Выйти", (d, w) -> {
                        getMessagesController().deleteParticipantFromChat(c.id, getUserConfig().getCurrentUser());
                        c.left = true;
                        styleFollow();
                    })
                    .setNegativeButton(LocaleController.getString(org.telegram.messenger.R.string.Cancel), null)
                    .show();
        });
        View.OnClickListener toChannel = v -> {
            if (chat != null) PrizmaFeedKit.openChannel(this, chat.id);
        };
        avatar.setOnClickListener(toChannel);
        names.setOnClickListener(toChannel);
        bigPost.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (m.fwd_from != null) {
            TextView fwd = new TextView(context);
            String from = null;
            if (m.fwd_from.from_id instanceof TLRPC.TL_peerChannel) {
                TLRPC.Chat c = getMessagesController().getChat(m.fwd_from.from_id.channel_id);
                if (c != null) from = c.title;
            } else if (m.fwd_from.from_id instanceof TLRPC.TL_peerUser) {
                from = UserObject.getUserName(getMessagesController().getUser(m.fwd_from.from_id.user_id));
            }
            if (from == null) from = m.fwd_from.from_name;
            if (!TextUtils.isEmpty(from)) {
                fwd.setText("Репост из " + from);
                fwd.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                fwd.setTextColor(gray);
                PrizmaFeedIcons r = new PrizmaFeedIcons(PrizmaFeedIcons.REPOST, 16).setColor(gray);
                r.setBounds(0, 0, r.getIntrinsicWidth(), r.getIntrinsicHeight());
                fwd.setCompoundDrawables(r, null, null, null);
                fwd.setCompoundDrawablePadding(AndroidUtilities.dp(6));
                bigPost.addView(fwd, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            }
        }

        bigText = new LinkSpanDrawable.LinksTextView(context);
        bigText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        bigText.setTextColor(ink);
        bigText.setLinkTextColor(blue);
        bigText.setLineSpacing(AndroidUtilities.dp(3), 1f);
        bigText.setTextIsSelectable(false);
        bigText.setOnLinkPressListener(span -> PrizmaFeedKit.openLink(this, post, span, bigText));
        bigText.setOnLongClickListener(v -> {
            if (!TextUtils.isEmpty(post.messageOwner.message)) {
                AndroidUtilities.addToClipboard(post.messageOwner.message);
                org.telegram.ui.Components.BulletinFactory.of(this).createCopyBulletin("Текст поста скопирован").show();
            }
            return true;
        });
        bigPost.addView(bigText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        bigTranslate = new TextView(context);
        bigTranslate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bigTranslate.setTextColor(blue);
        bigTranslate.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(2));
        bigTranslate.setOnClickListener(v -> translateBig());
        bigPost.addView(bigTranslate);
        bindBigText();

        PrizmaPostMedia media = new PrizmaPostMedia(context);
        media.setDelegate(new PrizmaPostMedia.Delegate() {
            @Override
            public void onMedia(int index) {
                ArrayList<MessageObject> visual = new ArrayList<>();
                for (MessageObject mo : group) {
                    TLRPC.MessageMedia mm = mo.messageOwner.media;
                    if (mm instanceof TLRPC.TL_messageMediaPhoto || mm instanceof TLRPC.TL_messageMediaDocument && (mo.isVideo() || mo.isGif() || mo.isRoundVideo())) visual.add(mo);
                }
                PrizmaFeedKit.openMedia(PrizmaPostActivity.this, visual, index);
            }

            @Override
            public void onLink(String url) {
                if (url != null) Browser.openUrl(getParentActivity(), url);
            }

            @Override
            public void onVote(int option) {
                PrizmaFeedKit.openPost(PrizmaPostActivity.this, post);
            }

            @Override
            public void onFile() {
                if (post.isMusic() || post.isVoice()) MediaController.getInstance().playMessage(post);
                else PrizmaFeedKit.openPost(PrizmaPostActivity.this, post);
            }
        });
        if (media.bind(group, AndroidUtilities.displaySize.x - AndroidUtilities.dp(32))) {
            bigPost.addView(media, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }

        dateLine = new TextView(context);
        dateLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        dateLine.setTextColor(gray);
        bigPost.addView(dateLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 12));

        bigPost.addView(line(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        chipsScroll.addView(chips);
        bigPost.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        View chipsLine = line(context);
        chipsLine.setTag("chipsLine");
        bigPost.addView(chipsLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        bigActions = new PrizmaPostActions(context, true);
        bigActions.setDelegate(new PrizmaPostActions.Delegate() {
            @Override
            public void onReply() {
                startReply(null);
            }

            @Override
            public void onRepost() {
                PrizmaFeedKit.share(PrizmaPostActivity.this, post);
            }

            @Override
            public void onLike() {
                boolean now = PrizmaFeedKit.toggleLike(PrizmaPostActivity.this, post);
                bindBigState(true);
                if (now) bigActions.burstLike();
            }

            @Override
            public void onViews() {
            }

            @Override
            public void onSave() {
                boolean now = PrizmaFeedKit.toggleSaved(PrizmaPostActivity.this, group);
                bindBigState(true);
                if (now) bigActions.burstSave();
            }

            @Override
            public void onShare() {
                PrizmaFeedKit.share(PrizmaPostActivity.this, post);
            }
        });
        bigPost.addView(bigActions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, -6, 2, -6, 2));
        bigPost.addView(line(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, -16, 0, -16, 0));
        bigPost.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bindBigState(false);
    }

    private View line(Context context) {
        View v = new View(context);
        v.setBackgroundColor(Theme.getColor(Theme.key_divider));
        return v;
    }

    private void styleFollow() {
        TLRPC.Chat c = channel();
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        boolean joined = c != null && !ChatObject.isNotInChat(c);
        follow.setVisibility(c == null ? View.GONE : View.VISIBLE);
        follow.setText(joined ? "Вы читаете" : "Читать");
        follow.setTextColor(joined ? ink : bg);
        if (joined) {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setCornerRadius(AndroidUtilities.dp(17));
            d.setStroke(Math.max(1, AndroidUtilities.dp(1)), Theme.getColor(Theme.key_divider));
            follow.setBackground(d);
        } else {
            follow.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(17), ink));
        }
    }

    private void bindBigText() {
        String key = PrizmaFeedKit.key(post);
        TLRPC.TL_textWithEntities tr = translations.get(key);
        CharSequence body = tr != null && !showOriginal ? PrizmaFeedKit.text(tr.text, tr.entities, bigText.getPaint()) : PrizmaFeedKit.text(post.messageOwner, bigText.getPaint());
        bigText.setText(body);
        bigText.setVisibility(TextUtils.isEmpty(body) ? View.GONE : View.VISIBLE);
        if (tr != null) {
            bigTranslate.setVisibility(View.VISIBLE);
            bigTranslate.setText(showOriginal ? "Показать перевод" : "Показать оригинал");
        } else if (PrizmaFeedKit.foreign(post.messageOwner.message)) {
            bigTranslate.setVisibility(View.VISIBLE);
            bigTranslate.setText("Перевести пост");
        } else {
            bigTranslate.setVisibility(View.GONE);
        }
    }

    private void translateBig() {
        String key = PrizmaFeedKit.key(post);
        if (translations.get(key) != null) {
            showOriginal = !showOriginal;
            bindBigText();
            return;
        }
        bigTranslate.setText("Перевожу…");
        TLRPC.TL_textWithEntities src = new TLRPC.TL_textWithEntities();
        src.text = post.messageOwner.message;
        if (post.messageOwner.entities != null) src.entities.addAll(post.messageOwner.entities);
        PrizmaTranslate.translate(src, LocaleController.getInstance().getCurrentLocale().getLanguage(), result -> AndroidUtilities.runOnUIThread(() -> {
            if (result == null) {
                bigTranslate.setText("Не получилось перевести");
                return;
            }
            translations.put(key, result);
            showOriginal = false;
            bindBigText();
        }));
    }

    private void bindBigState(boolean animated) {
        TLRPC.Message m = post.messageOwner;
        SpannableStringBuilder sb = new SpannableStringBuilder(PrizmaFeedKit.fullDate(m.date));
        if (m.views > 0) {
            sb.append(" · ");
            int s = sb.length();
            sb.append(PrizmaFeedKit.count(m.views));
            sb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(" Просмотры");
        }
        if (m.edit_date > 0 && !m.edit_hide) sb.append(" · изменено");
        dateLine.setText(sb);
        bigActions.bind(m, PrizmaFeedKit.isSaved(post), animated);
        bindChips();
    }

    private void bindChips() {
        chips.removeAllViews();
        TLRPC.Message m = post.messageOwner;
        View chipsLine = bigPost.findViewWithTag("chipsLine");
        boolean any = m.reactions != null && !m.reactions.results.isEmpty();
        chipsScroll.setVisibility(any ? View.VISIBLE : View.GONE);
        if (chipsLine != null) chipsLine.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;
        ArrayList<TLRPC.ReactionCount> sorted = new ArrayList<>(m.reactions.results);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.count, a.count));
        for (TLRPC.ReactionCount rc : sorted) {
            chips.addView(new Chip(chips.getContext(), rc), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
        }
    }

    private final class Chip extends LinearLayout {
        private AnimatedEmojiDrawable emoji;
        private final ImageView icon;

        Chip(Context context, TLRPC.ReactionCount rc) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(12), 0);
            int blue = Theme.getColor(Theme.key_featuredStickers_addButton);
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(16));
            if (rc.chosen) {
                bg.setColor(Theme.multAlpha(blue, 0.14f));
                bg.setStroke(Math.max(1, AndroidUtilities.dp(1)), blue);
            } else {
                bg.setStroke(Math.max(1, AndroidUtilities.dp(1)), Theme.getColor(Theme.key_divider));
            }
            setBackground(bg);
            icon = new ImageView(context);
            TextView face = new TextView(context);
            face.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            if (rc.reaction instanceof TLRPC.TL_reactionEmoji) {
                face.setText(org.telegram.messenger.Emoji.replaceEmoji(((TLRPC.TL_reactionEmoji) rc.reaction).emoticon, face.getPaint().getFontMetricsInt(), false));
                addView(face);
            } else if (rc.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                emoji = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_KEYBOARD, ((TLRPC.TL_reactionCustomEmoji) rc.reaction).document_id);
                icon.setImageDrawable(emoji);
                addView(icon, LayoutHelper.createLinear(20, 20));
            } else {
                face.setText(org.telegram.messenger.Emoji.replaceEmoji("⭐", face.getPaint().getFontMetricsInt(), false));
                addView(face);
            }
            TextView n = new TextView(context);
            n.setText(LocaleController.formatShortNumber(rc.count, null));
            n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            n.setTypeface(AndroidUtilities.bold());
            n.setTextColor(rc.chosen ? blue : ink);
            addView(n, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
            if (!(rc.reaction instanceof TLRPC.TL_reactionPaid)) {
                PrizmaFeedKit.press(this, 0.9f);
                setOnClickListener(v -> {
                    PrizmaFeedKit.react(PrizmaPostActivity.this, post, ReactionsLayoutInBubble.VisibleReaction.fromTL(rc.reaction), !rc.chosen);
                    bindBigState(true);
                });
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (emoji != null) emoji.addView(icon);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (emoji != null) emoji.removeView(icon);
        }
    }

    private LinearLayout buildComposer(Context context) {
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int blue = Theme.getColor(Theme.key_featuredStickers_addButton);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
                box.addView(line(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        replyBanner = new TextView(context);
        replyBanner.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        replyBanner.setTextColor(gray);
        replyBanner.setPadding(AndroidUtilities.dp(60), AndroidUtilities.dp(8), AndroidUtilities.dp(16), 0);
        replyBanner.setVisibility(View.GONE);
        replyBanner.setOnClickListener(v -> startReply(null));
        box.addView(replyBanner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        BackupImageView me = new BackupImageView(context);
        me.setRoundRadius(AndroidUtilities.dp(16));
        TLRPC.User user = getUserConfig().getCurrentUser();
        AvatarDrawable d = new AvatarDrawable();
        d.setInfo(currentAccount, user);
        me.setForUserOrChat(user, d);
        row.addView(me, LayoutHelper.createLinear(32, 32, Gravity.BOTTOM, 0, 0, 0, 4));

        input = new EditTextBoldCursor(context);
        input.setHint("Опубликовать ответ");
        input.setHintTextColor(gray);
        input.setTextColor(ink);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setMaxLines(5);
        input.setBackground(null);
        input.setCursorColor(blue);
        input.setCursorSize(AndroidUtilities.dp(20));
        input.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                send.setEnabled(has);
                send.animate().alpha(has ? 1f : 0.4f).setDuration(150).start();
            }
        });
        row.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        ImageView thread = new ImageView(context);
        thread.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.REPLY, 22).setColor(blue));
        thread.setScaleType(ImageView.ScaleType.CENTER);
        thread.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(blue, 0.16f), 1, AndroidUtilities.dp(18)));
        thread.setOnClickListener(v -> openThread(0));
        PrizmaFeedKit.press(thread, 0.82f);
        row.addView(thread, LayoutHelper.createLinear(38, 38, Gravity.BOTTOM, 0, 0, 4, 0));

        send = new ImageView(context);
        send.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.SEND, 20).setColor(0xffffffff));
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(36), blue));
        send.setEnabled(false);
        send.setAlpha(0.4f);
        send.setOnClickListener(v -> sendReply());
        PrizmaFeedKit.press(send, 0.82f);
        row.addView(send, LayoutHelper.createLinear(36, 36, Gravity.BOTTOM, 0, 0, 0, 1));
        box.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return box;
    }

    private String nameOf(MessageObject mo) {
        long from = mo.getFromChatId();
        if (from > 0) {
            TLRPC.User u = getMessagesController().getUser(from);
            String un = u != null ? UserObject.getPublicUsername(u) : null;
            return un != null ? "@" + un : UserObject.getUserName(u);
        }
        TLRPC.Chat c = getMessagesController().getChat(-from);
        String h = PrizmaFeedKit.handle(c);
        return h != null ? h : c != null ? c.title : "";
    }

    private void startReply(MessageObject target) {
        replyTarget = target;
        if (composer.getVisibility() != View.VISIBLE) {
            PrizmaFeedKit.openPost(this, post);
            return;
        }
        if (target != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder("В ответ ");
            int s = sb.length();
            sb.append(nameOf(target));
            sb.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_featuredStickers_addButton)), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ✕");
            replyBanner.setText(sb);
            replyBanner.setVisibility(View.VISIBLE);
        } else {
            replyBanner.setVisibility(View.GONE);
        }
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    private void loadDiscussion() {
        loadingReplies = true;
        TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = getMessagesController().getInputPeer(post.getDialogId());
        req.msg_id = post.getId();
        int id = getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            loadingReplies = false;
            if (res instanceof TLRPC.TL_messages_discussionMessage) {
                discussion = (TLRPC.TL_messages_discussionMessage) res;
                getMessagesController().putUsers(discussion.users, false);
                getMessagesController().putChats(discussion.chats, false);
                for (TLRPC.Message msg : discussion.messages) {
                    if (!(msg instanceof TLRPC.TL_messageEmpty)) {
                        threadTop = msg;
                        break;
                    }
                }
            }
            if (threadTop == null) {
                noComments = true;
                composer.setVisibility(View.GONE);
                adapter.notifyDataSetChanged();
                return;
            }
            loadReplies();
        }));
        getConnectionsManager().bindRequestToGuid(id, classGuid);
    }

    private void loadReplies() {
        if (loadingReplies || endReached || threadTop == null) return;
        loadingReplies = true;
        adapter.notifyDataSetChanged();
        TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
        req.peer = getMessagesController().getInputPeer(threadTop.peer_id);
        req.msg_id = threadTop.id;
        req.offset_id = minId == Integer.MAX_VALUE ? 0 : minId;
        req.limit = 40;
        int id = getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            loadingReplies = false;
            if (res instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages m = (TLRPC.messages_Messages) res;
                getMessagesController().putUsers(m.users, false);
                getMessagesController().putChats(m.chats, false);
                HashMap<Long, ArrayList<MessageObject>> albums = new HashMap<>();
                for (ArrayList<MessageObject> g : replies) {
                    long gid = g.get(0).messageOwner.grouped_id;
                    if (gid != 0) albums.put(gid, g);
                }
                for (TLRPC.Message msg : m.messages) {
                    minId = Math.min(minId, msg.id);
                    if (msg instanceof TLRPC.TL_messageService || byId.containsKey(msg.id)) continue;
                    MessageObject mo = new MessageObject(currentAccount, msg, false, false);
                    byId.put(msg.id, mo);
                    long gid = msg.grouped_id;
                    if (gid != 0 && albums.containsKey(gid)) {
                        albums.get(gid).add(mo);
                        continue;
                    }
                    ArrayList<MessageObject> g = new ArrayList<>();
                    g.add(mo);
                    if (gid != 0) albums.put(gid, g);
                    replies.add(g);
                }
                if (m.messages.size() < req.limit) endReached = true;
            } else {
                endReached = true;
            }
            resort();
        }));
        getConnectionsManager().bindRequestToGuid(id, classGuid);
    }

    private void refreshCounters() {
        TLRPC.TL_messages_getMessagesViews req = new TLRPC.TL_messages_getMessagesViews();
        req.peer = getMessagesController().getInputPeer(post.getDialogId());
        req.id.add(post.getId());
        req.increment = true;
        int id = getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(res instanceof TLRPC.TL_messages_messageViews)) return;
            TLRPC.TL_messages_messageViews v = (TLRPC.TL_messages_messageViews) res;
            if (v.views.isEmpty()) return;
            TLRPC.TL_messageViews mv = v.views.get(0);
            TLRPC.Message m = post.messageOwner;
            if (mv.views > m.views) m.views = mv.views;
            if (mv.forwards > m.forwards) m.forwards = mv.forwards;
            if (mv.replies != null) m.replies = mv.replies;
            bindBigState(true);
        }));
        getConnectionsManager().bindRequestToGuid(id, classGuid);
    }

    private static int weight(ArrayList<MessageObject> g) {
        TLRPC.Message m = g.get(0).messageOwner;
        return PrizmaFeedKit.reactions(m) * 2 + PrizmaFeedKit.replies(m) * 3;
    }

    private void resort() {
        shown.clear();
        shown.addAll(replies);
        if (sort == SORT_TOP) {
            Collections.sort(shown, (a, b) -> {
                int c = Integer.compare(weight(b), weight(a));
                return c != 0 ? c : Integer.compare(b.get(0).messageOwner.date, a.get(0).messageOwner.date);
            });
        } else if (sort == SORT_NEW) {
            Collections.sort(shown, (a, b) -> Integer.compare(b.get(0).getId(), a.get(0).getId()));
        } else {
            Collections.sort(shown, (a, b) -> Integer.compare(a.get(0).getId(), b.get(0).getId()));
        }
        adapter.notifyDataSetChanged();
    }

    private String sortTitle() {
        return sort == SORT_TOP ? "Актуальные" : sort == SORT_NEW ? "Сначала новые" : "Сначала старые";
    }

    private void chooseSort() {
        if (getParentActivity() == null) return;
        BottomSheet.Builder b = new BottomSheet.Builder(getParentActivity());
        b.setTitle("Порядок ответов");
        CharSequence[] items = {
                (sort == SORT_TOP ? "✓ " : "") + "Актуальные",
                (sort == SORT_NEW ? "✓ " : "") + "Сначала новые",
                (sort == SORT_OLD ? "✓ " : "") + "Сначала старые"
        };
        b.setItems(items, (d, which) -> {
            sort = which;
            resort();
        });
        showDialog(b.create());
    }

    private String replyHandle(MessageObject mo) {
        TLRPC.Message m = mo.messageOwner;
        int to = m.reply_to != null ? m.reply_to.reply_to_msg_id : 0;
        MessageObject parent = to != 0 && threadTop != null && to != threadTop.id ? byId.get(to) : null;
        if (parent != null) return nameOf(parent);
        TLRPC.Chat c = channel();
        String h = PrizmaFeedKit.handle(c);
        return h != null ? h : c != null ? c.title : null;
    }

    private void sendReply() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || threadTop == null) return;
        long dialog = MessageObject.getDialogId(threadTop);
        MessageObject top = new MessageObject(currentAccount, threadTop, false, false);
        top.messageOwner.isThreadMessage = true;
        MessageObject target = replyTarget != null ? replyTarget : top;
        getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialog, target, top, null, false, null, null, null, true, 0, 0, null, false));

        TLRPC.TL_message local = new TLRPC.TL_message();
        local.id = Integer.MAX_VALUE - replies.size();
        local.message = text;
        local.date = getConnectionsManager().getCurrentTime();
        local.out = true;
        local.from_id = new TLRPC.TL_peerUser();
        local.from_id.user_id = getUserConfig().getClientUserId();
        local.peer_id = threadTop.peer_id;
        local.reply_to = new TLRPC.TL_messageReplyHeader();
        local.reply_to.reply_to_msg_id = target.getId();
        local.reply_to.reply_to_top_id = threadTop.id;
        MessageObject mine = new MessageObject(currentAccount, local, false, false);
        ArrayList<MessageObject> g = new ArrayList<>();
        g.add(mine);
        replies.add(g);
        shown.add(0, g);
        if (post.messageOwner.replies != null) post.messageOwner.replies.replies++;
        input.setText("");
        startReply(null);
        AndroidUtilities.hideKeyboard(input);
        bindBigState(true);
        adapter.notifyDataSetChanged();
        list.smoothScrollToPosition(2);
    }

    private void openThread(int highlight) {
        if (discussion == null || threadTop == null) {
            PrizmaFeedKit.openPost(this, post);
            return;
        }
        ArrayList<MessageObject> arr = new ArrayList<>();
        for (TLRPC.Message msg : discussion.messages) {
            if (msg instanceof TLRPC.TL_messageEmpty) continue;
            msg.isThreadMessage = true;
            arr.add(new MessageObject(currentAccount, msg, true, true));
        }
        if (arr.isEmpty()) {
            PrizmaFeedKit.openPost(this, post);
            return;
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", -arr.get(0).getDialogId());
        args.putInt("message_id", Math.max(1, highlight != 0 && highlight < Integer.MAX_VALUE - 10000 ? highlight : discussion.read_inbox_max_id));
        args.putInt("unread_count", discussion.unread_count);
        args.putBoolean("historyPreloaded", false);
        ChatActivity chat = new ChatActivity(args);
        chat.setThreadMessages(arr, channel(), post.getId(), discussion.read_inbox_max_id, discussion.read_outbox_max_id, null);
        if (highlight != 0 && highlight < Integer.MAX_VALUE - 10000) chat.highlightMessageId = highlight;
        presentFragment(chat);
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 2;
        }

        @Override
        public int getItemCount() {
            return 2 + shown.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return 0;
            if (position == 1) return 1;
            if (position - 2 < shown.size()) return 2;
            return 3;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v;
            if (viewType == 0) {
                if (bigPost.getParent() != null) ((ViewGroup) bigPost.getParent()).removeView(bigPost);
                v = bigPost;
            } else if (viewType == 1) {
                LinearLayout h = new LinearLayout(context);
                h.setOrientation(LinearLayout.HORIZONTAL);
                h.setGravity(Gravity.CENTER_VERTICAL);
                h.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
                TextView t = new TextView(context);
                t.setTag("sort");
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                t.setTypeface(AndroidUtilities.bold());
                t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                PrizmaFeedIcons ch = new PrizmaFeedIcons(PrizmaFeedIcons.CHEVRON, 16).setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                ch.setBounds(0, 0, ch.getIntrinsicWidth(), ch.getIntrinsicHeight());
                t.setCompoundDrawables(null, null, ch, null);
                t.setCompoundDrawablePadding(AndroidUtilities.dp(4));
                t.setOnClickListener(x -> chooseSort());
                h.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
                View space = new View(context);
                h.addView(space, LayoutHelper.createLinear(0, 1, 1f));
                TextView all = new TextView(context);
                all.setText("Всё обсуждение");
                all.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                all.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                all.setTag("all");
                all.setGravity(Gravity.CENTER_VERTICAL);
                all.setOnClickListener(x -> openThread(0));
                h.addView(all, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT));
                v = h;
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(46)));
            } else if (viewType == 2) {
                v = new PrizmaPostCell(context, PrizmaPostActivity.this);
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                FrameLayout f = new FrameLayout(context);
                RadialProgressView p = new RadialProgressView(context);
                p.setSize(AndroidUtilities.dp(22));
                p.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                p.setTag("spin");
                f.addView(p, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
                TextView t = new TextView(context);
                t.setTag("note");
                t.setGravity(Gravity.CENTER);
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                t.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
                f.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
                v = f;
                v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(120)));
            }
            RecyclerListView.Holder holder = new RecyclerListView.Holder(v);
            if (viewType == 0) holder.setIsRecyclable(false);
            return holder;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == 1) {
                TextView t = holder.itemView.findViewWithTag("sort");
                t.setText(noComments ? "Ответы" : sortTitle());
                t.setEnabled(!noComments);
                holder.itemView.findViewWithTag("all").setVisibility(noComments || threadTop == null ? View.GONE : View.VISIBLE);
            } else if (type == 2) {
                ArrayList<MessageObject> g = shown.get(position - 2);
                PrizmaPostCell cell = (PrizmaPostCell) holder.itemView;
                cell.bind(g, true, replyHandle(g.get(0)));
            } else if (type == 3) {
                View spin = holder.itemView.findViewWithTag("spin");
                TextView note = holder.itemView.findViewWithTag("note");
                boolean busy = loadingReplies && !noComments;
                spin.setVisibility(busy ? View.VISIBLE : View.GONE);
                String text = null;
                if (!busy) {
                    if (noComments) text = "Комментарии к этому посту закрыты";
                    else if (shown.isEmpty()) text = "Пока нет ответов. Напишите первым";
                }
                note.setText(text == null ? "" : text);
                note.setVisibility(text == null ? View.GONE : View.VISIBLE);
            }
        }
    }
}
