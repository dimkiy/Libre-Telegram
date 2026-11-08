package org.telegram.ui.Components;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaTranslate;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Arrays;

public class PrizmaPostCell extends LinearLayout {

    public interface Host {
        BaseFragment fragment();

        void openPost(ArrayList<MessageObject> group);

        void reply(ArrayList<MessageObject> group);

        void showMenu(ArrayList<MessageObject> group);

        boolean isExpanded(String key);

        void expand(String key);

        boolean isSuggested(long chatId);

        boolean isTrending(MessageObject mo);

        TLRPC.TL_textWithEntities translation(String key);

        void putTranslation(String key, TLRPC.TL_textWithEntities text);

        void changed(PrizmaPostCell cell);
    }

    private final Host host;
    private final LinearLayout context;
    private final ImageView contextIcon;
    private final TextView contextText;
    private final TextView follow;
    private final BackupImageView avatar;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView name, meta, replyTo, more, translate;
    private final ImageView verified;
    private final ImageView menu;
    private final LinkSpanDrawable.LinksTextView text;
    private final PrizmaPostMedia media;
    private final PrizmaPostActions actions;
    private final View divider;
    private ArrayList<MessageObject> group;
    private boolean reply;
    private boolean showOriginal;

    public PrizmaPostCell(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        setOrientation(VERTICAL);
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int blue = Theme.getColor(Theme.key_featuredStickers_addButton);

        context = new LinearLayout(ctx);
        context.setOrientation(HORIZONTAL);
        context.setGravity(Gravity.CENTER_VERTICAL);
        context.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), 0);
        contextIcon = new ImageView(ctx);
        contextIcon.setScaleType(ImageView.ScaleType.CENTER);
        context.addView(contextIcon, LayoutHelper.createLinear(40, 18, 0, 0, 12, 0));
        contextText = new TextView(ctx);
        contextText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        contextText.setTypeface(AndroidUtilities.bold());
        contextText.setTextColor(gray);
        contextText.setSingleLine(true);
        contextText.setEllipsize(TextUtils.TruncateAt.END);
        context.addView(contextText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        follow = new TextView(ctx);
        follow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        follow.setTypeface(AndroidUtilities.bold());
        follow.setGravity(Gravity.CENTER);
        follow.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        context.addView(follow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, 8, 0, 0, 0));
        addView(context);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
        avatar = new BackupImageView(ctx);
        avatar.setRoundRadius(AndroidUtilities.dp(20));
        row.addView(avatar, LayoutHelper.createLinear(40, 40, Gravity.TOP));

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(VERTICAL);
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        name = new TextView(ctx);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(ink);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0, 0, 0));
        verified = new ImageView(ctx);
        verified.setImageDrawable(PrizmaFeedKit.verified(ctx));
        head.addView(verified, LayoutHelper.createLinear(16, 16, 3, 0, 0, 0));
        meta = new TextView(ctx);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        meta.setTextColor(gray);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(meta, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 0, 0));
        menu = new ImageView(ctx);
        menu.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.MORE, 18).setColor(gray));
        menu.setScaleType(ImageView.ScaleType.CENTER);
        menu.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(blue, 0.16f), 1, AndroidUtilities.dp(15)));
        head.addView(menu, LayoutHelper.createLinear(30, 24));
        column.addView(head);

        replyTo = new TextView(ctx);
        replyTo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        replyTo.setTextColor(gray);
        replyTo.setSingleLine(true);
        replyTo.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(replyTo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        text = new LinkSpanDrawable.LinksTextView(ctx);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setTextColor(ink);
        text.setLinkTextColor(blue);
        text.setLineSpacing(AndroidUtilities.dp(2.5f), 1f);
        text.setOnLinkPressListener(span -> {
            if (group != null) PrizmaFeedKit.openLink(host.fragment(), group.get(0), span, text);
        });
        column.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        more = new TextView(ctx);
        more.setText("Показать ещё");
        more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        more.setTextColor(blue);
        more.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2));
        column.addView(more);

        translate = new TextView(ctx);
        translate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        translate.setTextColor(gray);
        translate.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(2));
        column.addView(translate);

        media = new PrizmaPostMedia(ctx);
        column.addView(media, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        actions = new PrizmaPostActions(ctx, false);
        column.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 34, -8, 6, -4, 0));

        row.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        addView(row);

        divider = new View(ctx);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 4, 0, 0));
        divider.getLayoutParams().height = Math.max(1, AndroidUtilities.dp(0.66f));

        wire();
    }

    private void wire() {
        setBackground(Theme.getSelectorDrawable(false));
        PrizmaFeedKit.press(avatar, 0.88f);
        PrizmaFeedKit.press(menu, 0.8f);
        PrizmaFeedKit.press(follow, 0.92f);
        PrizmaFeedKit.press(more, 0.95f);
        PrizmaFeedKit.press(translate, 0.95f);
        setOnClickListener(v -> {
            if (group != null) host.openPost(group);
        });
        menu.setOnClickListener(v -> {
            if (group != null) host.showMenu(group);
        });
        more.setOnClickListener(v -> {
            if (group == null) return;
            host.expand(PrizmaFeedKit.key(group.get(0)));
            host.changed(this);
        });
        translate.setOnClickListener(v -> runTranslate());
        media.setDelegate(new PrizmaPostMedia.Delegate() {
            @Override
            public void onMedia(int index) {
                ArrayList<MessageObject> visual = new ArrayList<>();
                for (MessageObject mo : group) {
                    TLRPC.MessageMedia m = mo.messageOwner.media;
                    if (m instanceof TLRPC.TL_messageMediaPhoto || m instanceof TLRPC.TL_messageMediaDocument && (mo.isVideo() || mo.isGif() || mo.isRoundVideo())) visual.add(mo);
                }
                PrizmaFeedKit.openMedia(host.fragment(), visual, index);
            }

            @Override
            public void onLink(String url) {
                if (url != null) Browser.openUrl(getContext(), url);
            }

            @Override
            public void onVote(int option) {
                vote(option);
            }

            @Override
            public void onFile() {
                MessageObject mo = group.get(0);
                if (mo.isMusic() || mo.isVoice()) {
                    MediaController.getInstance().playMessage(mo);
                } else {
                    PrizmaFeedKit.openPost(host.fragment(), mo);
                }
            }
        });
        actions.setDelegate(new PrizmaPostActions.Delegate() {
            @Override
            public void onReply() {
                host.reply(group);
            }

            @Override
            public void onRepost() {
                PrizmaFeedKit.share(host.fragment(), group.get(0));
            }

            @Override
            public void onLike() {
                MessageObject mo = group.get(0);
                boolean now = PrizmaFeedKit.toggleLike(host.fragment(), mo);
                actions.bind(mo.messageOwner, PrizmaFeedKit.isSaved(mo), true);
                if (now) actions.burstLike();
            }

            @Override
            public void onViews() {
                host.openPost(group);
            }

            @Override
            public void onSave() {
                boolean now = PrizmaFeedKit.toggleSaved(host.fragment(), group);
                actions.bind(group.get(0).messageOwner, now, true);
                if (now) actions.burstSave();
            }

            @Override
            public void onShare() {
                PrizmaFeedKit.share(host.fragment(), group.get(0));
            }
        });
    }

    public ArrayList<MessageObject> getGroup() {
        return group;
    }

    public void bind(ArrayList<MessageObject> group, boolean reply, String replyToHandle) {
        this.group = group;
        this.reply = reply;
        MessageObject mo = group.get(0);
        TLRPC.Message m = mo.messageOwner;
        BaseFragment f = host.fragment();
        long chatId = -mo.getDialogId();
        TLRPC.Chat channel = f.getMessagesController().getChat(chatId);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int blue = Theme.getColor(Theme.key_featuredStickers_addButton);

        TLRPC.Chat authorChat = channel;
        TLRPC.User authorUser = null;
        String authorName = channel != null ? channel.title : "";
        String fwdName = null;
        if (reply) {
            long from = mo.getFromChatId();
            if (from > 0) {
                authorUser = f.getMessagesController().getUser(from);
                authorChat = null;
                authorName = UserObject.getUserName(authorUser);
            } else if (from < 0) {
                authorChat = f.getMessagesController().getChat(-from);
                authorName = authorChat != null ? authorChat.title : "";
            }
        } else if (m.fwd_from != null) {
            TLRPC.Peer p = m.fwd_from.from_id;
            if (p instanceof TLRPC.TL_peerChannel || p instanceof TLRPC.TL_peerChat) {
                TLRPC.Chat c = f.getMessagesController().getChat(p.channel_id != 0 ? p.channel_id : p.chat_id);
                if (c != null) {
                    authorChat = c;
                    authorName = c.title;
                    fwdName = channel != null ? channel.title : null;
                }
            } else if (p instanceof TLRPC.TL_peerUser) {
                TLRPC.User u = f.getMessagesController().getUser(p.user_id);
                if (u != null) {
                    authorUser = u;
                    authorChat = null;
                    authorName = UserObject.getUserName(u);
                    fwdName = channel != null ? channel.title : null;
                }
            } else if (!TextUtils.isEmpty(m.fwd_from.from_name)) {
                authorName = m.fwd_from.from_name;
                fwdName = channel != null ? channel.title : null;
            }
        }

        boolean suggested = !reply && host.isSuggested(chatId) && (channel == null || ChatObject.isNotInChat(channel));
        if (fwdName != null) {
            context.setVisibility(VISIBLE);
            contextIcon.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.REPOST, 16).setColor(gray));
            contextText.setText(fwdName + " сделал(а) репост");
            follow.setVisibility(GONE);
        } else if (suggested) {
            context.setVisibility(VISIBLE);
            contextIcon.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.PLUS, 16).setColor(gray));
            contextText.setText("Рекомендация для вас");
            follow.setVisibility(VISIBLE);
            styleFollow(false);
            follow.setOnClickListener(v -> {
                f.getMessagesController().addUserToChat(chatId, f.getUserConfig().getCurrentUser(), 0, null, f, null);
                follow.animate().scaleX(0.82f).scaleY(0.82f).alpha(0.4f).setDuration(120).withEndAction(() -> {
                    styleFollow(true);
                    follow.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(360).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                }).start();
            });
        } else {
            context.setVisibility(GONE);
        }

        if (authorUser != null) {
            avatarDrawable.setInfo(f.getCurrentAccount(), authorUser);
            avatar.setForUserOrChat(authorUser, avatarDrawable);
        } else {
            avatarDrawable.setInfo(f.getCurrentAccount(), authorChat);
            avatar.setForUserOrChat(authorChat, avatarDrawable);
        }
        final TLRPC.User au = authorUser;
        final TLRPC.Chat ac = authorChat;
        avatar.setOnClickListener(v -> {
            if (au != null) {
                f.presentFragment(org.telegram.ui.ProfileActivity.of(au.id));
            } else if (ac != null) {
                PrizmaFeedKit.openChannel(f, ac.id);
            }
        });
        name.setText(authorName);
        boolean isVerified = authorUser != null ? authorUser.verified : authorChat != null && authorChat.verified;
        verified.setVisibility(isVerified ? VISIBLE : GONE);
        String handle = authorUser != null ? (UserObject.getPublicUsername(authorUser) != null ? "@" + UserObject.getPublicUsername(authorUser) : null) : PrizmaFeedKit.handle(authorChat);
        SpannableStringBuilder metaText = new SpannableStringBuilder((handle != null ? handle + " · " : "· ") + PrizmaFeedKit.ago(m.date));
        if (!reply && host.isTrending(mo)) {
            metaText.append(" · ");
            int ts = metaText.length();
            metaText.append("в тренде");
            metaText.setSpan(new ForegroundColorSpan(0xfff4900c), ts, metaText.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        meta.setText(metaText);
        menu.setVisibility(reply ? GONE : VISIBLE);

        if (reply && replyToHandle != null) {
            SpannableStringBuilder sb = new SpannableStringBuilder("В ответ ");
            int s = sb.length();
            sb.append(replyToHandle);
            sb.setSpan(new ForegroundColorSpan(blue), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            replyTo.setText(sb);
            replyTo.setVisibility(VISIBLE);
        } else {
            replyTo.setVisibility(GONE);
        }

        bindText(mo);

        int avail = AndroidUtilities.displaySize.x - AndroidUtilities.dp(16 + 40 + 12 + 12);
        boolean hasMedia = media.bind(group, avail);
        LayoutParams lp = (LayoutParams) media.getLayoutParams();
        lp.topMargin = text.getVisibility() == VISIBLE || more.getVisibility() == VISIBLE ? AndroidUtilities.dp(10) : AndroidUtilities.dp(4);
        media.setVisibility(hasMedia ? VISIBLE : GONE);

        actions.bind(m, PrizmaFeedKit.isSaved(mo), false);
    }

    private void styleFollow(boolean done) {
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        follow.setText(done ? "Вы читаете" : "Читать");
        follow.setEnabled(!done);
        follow.setTextColor(done ? ink : bg);
        follow.setBackground(done ? Theme.createRoundRectDrawable(AndroidUtilities.dp(14), Theme.multAlpha(ink, 0.08f)) : Theme.createRoundRectDrawable(AndroidUtilities.dp(14), ink));
    }

    private void bindText(MessageObject mo) {
        TLRPC.Message m = mo.messageOwner;
        String key = PrizmaFeedKit.key(mo);
        TLRPC.TL_textWithEntities tr = host.translation(key);
        CharSequence body = tr != null && !showOriginal ? PrizmaFeedKit.text(tr.text, tr.entities, text.getPaint()) : PrizmaFeedKit.text(m, text.getPaint());
        text.setText(body);
        text.setVisibility(TextUtils.isEmpty(body) ? GONE : VISIBLE);
        boolean open = reply || host.isExpanded(key);
        int lines = body == null ? 0 : body.toString().split("\n").length;
        boolean longText = body != null && (body.length() > 420 || lines > 10);
        text.setMaxLines(open || !longText ? Integer.MAX_VALUE : 9);
        text.setEllipsize(open || !longText ? null : TextUtils.TruncateAt.END);
        more.setVisibility(longText && !open ? VISIBLE : GONE);
        if (tr != null) {
            translate.setVisibility(VISIBLE);
            translate.setText(showOriginal ? "Показать перевод" : "Переведено · Показать оригинал");
        } else if (PrizmaFeedKit.foreign(m.message)) {
            translate.setVisibility(VISIBLE);
            translate.setText("Перевести пост");
        } else {
            translate.setVisibility(GONE);
        }
    }

    private void runTranslate() {
        if (group == null) return;
        MessageObject mo = group.get(0);
        String key = PrizmaFeedKit.key(mo);
        if (host.translation(key) != null) {
            showOriginal = !showOriginal;
            bindText(mo);
            return;
        }
        translate.setText("Перевожу…");
        TLRPC.TL_textWithEntities src = new TLRPC.TL_textWithEntities();
        src.text = mo.messageOwner.message;
        if (mo.messageOwner.entities != null) src.entities.addAll(mo.messageOwner.entities);
        String to = LocaleController.getInstance().getCurrentLocale().getLanguage();
        PrizmaTranslate.translate(src, to, result -> AndroidUtilities.runOnUIThread(() -> {
            if (result == null) {
                translate.setText("Не получилось перевести");
                return;
            }
            host.putTranslation(key, result);
            showOriginal = false;
            if (group != null && PrizmaFeedKit.key(group.get(0)).equals(key)) bindText(group.get(0));
        }));
    }

    private void vote(int option) {
        MessageObject mo = group.get(0);
        if (!(mo.messageOwner.media instanceof TLRPC.TL_messageMediaPoll)) return;
        TLRPC.TL_messageMediaPoll pm = (TLRPC.TL_messageMediaPoll) mo.messageOwner.media;
        TLRPC.Poll p = pm.poll;
        boolean voted = false;
        if (pm.results != null && pm.results.results != null) for (TLRPC.PollAnswerVoters v : pm.results.results) if (v.chosen) voted = true;
        if (p.closed || voted || p.multiple_choice || option < 0 || option >= p.answers.size()) {
            PrizmaFeedKit.openPost(host.fragment(), mo);
            return;
        }
        TLRPC.PollAnswer answer = p.answers.get(option);
        ArrayList<TLRPC.PollAnswer> list = new ArrayList<>();
        list.add(answer);
        host.fragment().getSendMessagesHelper().sendVote(mo, list, null);
        if (pm.results == null) pm.results = new TLRPC.TL_pollResults();
        if (pm.results.results == null) pm.results.results = new ArrayList<>();
        TLRPC.PollAnswerVoters mine = null;
        for (TLRPC.PollAnswerVoters v : pm.results.results) if (Arrays.equals(v.option, answer.option)) mine = v;
        if (mine == null) {
            mine = new TLRPC.TL_pollAnswerVoters();
            mine.option = answer.option;
            pm.results.results.add(mine);
        }
        mine.chosen = true;
        mine.voters++;
        pm.results.total_voters++;
        media.bind(group, AndroidUtilities.displaySize.x - AndroidUtilities.dp(16 + 40 + 12 + 12));
    }

    public void setDividerVisible(boolean visible) {
        divider.setVisibility(visible ? VISIBLE : GONE);
    }
}
