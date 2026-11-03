package org.telegram.ui.Components;

import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stars.StarsController;

public final class PrizmaGiftTransfer {
    private static final long INBOX_EVERY = 3 * 60_000L;
    private static long inboxCheckedAt;
    private static boolean inboxBusy;

    private PrizmaGiftTransfer() {}

    public static void pickAndSend(int account, long instId) {
        if (!PrizmaCloud.configured()) {
            toast("Передача подарков работает через сервер Libre — он не подключён");
            return;
        }
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null) return;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        args.putBoolean("resetDelegate", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setCurrentAccount(account);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.isEmpty()) return false;
            long to = dids.get(0).dialogId;
            fragment.finishFragment();
            confirm(account, instId, to);
            return true;
        });
        last.presentFragment(picker);
    }

    public static void confirm(int account, long instId, long to) {
        if (!PrizmaCloud.configured()) {
            toast("Передача подарков работает через сервер Libre — он не подключён");
            return;
        }
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || last.getParentActivity() == null) return;
        TLRPC.User user = MessagesController.getInstance(account).getUser(to);
        if (user == null || user.bot || UserObject.isUserSelf(user) || to <= 0) {
            toast("Подарок можно передать только другому человеку");
            return;
        }
        String name = UserObject.getFirstName(user);
        new AlertDialog.Builder(last.getParentActivity(), last.getResourceProvider())
                .setTitle("Передать подарок?")
                .setMessage("«" + giftTitle(instId) + "» перейдёт в витрину " + name + ", а в чат с ним придёт сообщение о подарке. У вас он исчезнет.")
                .setPositiveButton("Передать", (d, w) -> send(account, instId, to, name))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void send(int account, long instId, long to, String name) {
        long giftId = PrizmaGifts.giftIdOf(instId);
        if (giftId == 0) {
            toast("Этого подарка уже нет в витрине");
            return;
        }
        JSONObject body = new JSONObject();
        final JSONObject gift = new JSONObject();
        try {
            gift.put("giftId", giftId);
            gift.put("upgraded", PrizmaGifts.isUpgraded(instId));
            gift.put("attr", PrizmaConfig.nftAttr(instId));
            gift.put("num", PrizmaConfig.nftNum(instId));
            gift.put("style", PrizmaGifts.style(instId));
            gift.put("crafted", PrizmaConfig.nftCrafted(instId));
            gift.put("title", giftTitle(instId));
            body.put("to", to);
            body.put("gift", gift);
        } catch (Throwable e) {
            return;
        }
        final String title = giftTitle(instId);
        PrizmaCloud.call(account, "POST", "/v1/gift/send", body, r -> {
            if (r.code == 200) {
                long card = 0;
                try {
                    card = new JSONObject(r.body).optLong("card", 0);
                } catch (Throwable ignore) {
                }
                PrizmaGiftCard.remember(card, gift);
                PrizmaGifts.removeInstance(instId);
                refreshMyGifts(account);
                announce(account, to, name, title, card);
            } else if (r.code == 404) {
                toast(name + " пока не пользуется Libre — подарок остаётся у вас");
            } else if (r.code == 409) {
                toast("Витрина " + name + " переполнена");
            } else {
                toast(org.telegram.messenger.PrizmaWall.error(r.code, null) + ". Подарок остаётся у вас");
            }
        });
    }

    private static void announce(int account, long to, String name, String title, long card) {
        try {
            PrizmaGiftCard.watchSent(account);
            SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
                    "🎁 Дарю тебе «" + title + "» — подарок уже в твоей витрине Libre" + (card > 0 ? PrizmaGiftCard.hide(card) : ""), to));
        } catch (Throwable e) {
            FileLog.e(e);
        }
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null) {
            toast("Подарок передан — " + name + " увидит его в своей витрине");
            return;
        }
        ChatActivity chat = ChatActivity.of(to);
        chat.whenFullyVisible(() -> BulletinFactory.of(chat)
                .createSimpleBulletin(R.raw.forward, "Подарок передан", "«" + title + "» теперь в витрине " + name)
                .ignoreDetach()
                .show());
        last.presentFragment(chat);
    }

    private static String giftTitle(long instId) {
        TL_stars.StarGift g = PrizmaGifts.find(PrizmaGifts.giftIdOf(instId));
        String t = g != null ? g.title : null;
        String num = PrizmaConfig.nftNum(instId);
        if (t == null || t.isEmpty()) t = "Подарок";
        if (num != null && num.contains("/")) num = num.substring(0, num.indexOf('/')).trim();
        return num != null && !num.isEmpty() ? t + " #" + num : t;
    }

    public static void checkInbox(int account) {
        if (!PrizmaCloud.configured() || inboxBusy) return;
        if (SystemClock.elapsedRealtime() - inboxCheckedAt < INBOX_EVERY && inboxCheckedAt != 0) return;
        inboxCheckedAt = SystemClock.elapsedRealtime();
        inboxBusy = true;
        PrizmaCloud.call(account, "GET", "/v1/gift/inbox", null, r -> {
            inboxBusy = false;
            if (r.code != 200 || r.body == null) return;
            try {
                JSONArray gifts = new JSONObject(r.body).optJSONArray("gifts");
                if (gifts == null || gifts.length() == 0) return;
                int got = 0;
                String lastFrom = null, lastTitle = null;
                for (int i = 0; i < gifts.length(); i++) {
                    JSONObject item = gifts.getJSONObject(i);
                    JSONObject g = item.getJSONObject("gift");
                    final long rxId = item.getLong("id");
                    PrizmaGiftCard.remember(g.optLong("card", 0), g);
                    if (alreadyReceived(rxId)) {
                        PrizmaCloud.call(account, "POST", "/v1/gift/ack/" + rxId, null, ack -> {});
                        continue;
                    }
                    if (PrizmaGifts.isFull()) {
                        toast("Витрина заполнена — освободите место, чтобы получить подарки друзей");
                        break;
                    }
                    long inst = PrizmaGifts.addInstance(g.getLong("giftId"));
                    if (inst == 0) break;
                    if (g.optBoolean("upgraded", false)) {
                        PrizmaConfig.setNftAttr(inst, g.optString("attr", ""));
                        PrizmaConfig.setNftNum(inst, g.optString("num", ""));
                        PrizmaGifts.setStyle(inst, g.optInt("style", 0));
                        PrizmaConfig.setNftCrafted(inst, g.optBoolean("crafted", false));
                        PrizmaGifts.setUpgraded(inst, true);
                    }
                    markReceived(rxId);
                    PrizmaCloud.call(account, "POST", "/v1/gift/ack/" + rxId, null, ack -> {});
                    got++;
                    TLRPC.User from = MessagesController.getInstance(account).getUser(item.optLong("from"));
                    lastFrom = from != null ? UserObject.getFirstName(from) : null;
                    lastTitle = g.optString("title", "подарок");
                }
                if (got > 0) {
                    refreshMyGifts(account);
                    toast(got == 1
                            ? (lastFrom != null ? "Подарок от " + lastFrom + ": «" + lastTitle + "»" : "Вам передали «" + lastTitle + "»") + " — уже в вашей витрине"
                            : "Вам передали подарки: " + got + " — они в вашей витрине");
                }
            } catch (Throwable ignore) {
            }
        });
    }

    private static android.content.SharedPreferences rx() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_gifts_rx", android.content.Context.MODE_PRIVATE);
    }

    private static boolean alreadyReceived(long id) {
        return rx().getBoolean("g" + id, false);
    }

    private static void markReceived(long id) {
        rx().edit().putBoolean("g" + id, true).apply();
    }

    private static void refreshMyGifts(int account) {
        try {
            long self = UserConfig.getInstance(account).getClientUserId();
            StarsController sc = StarsController.getInstance(account);
            StarsController.GiftsList a = sc.getProfileGiftsList(self, false);
            if (a != null) a.invalidate(true);
            StarsController.GiftsList b = sc.getProfileGiftsList(0, false);
            if (b != null) b.invalidate(true);
        } catch (Throwable ignore) {
        }
        PrizmaCloud.syncShowcaseSoon(account);
    }

    private static void toast(String text) {
        AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show());
    }
}
