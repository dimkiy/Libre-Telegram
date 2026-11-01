package org.telegram.ui.Components;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileRefController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Prizma: «Добавить в профиль Telegram» — the music Telegram shows on your profile. Telegram puts
 * this into its own player's menu; Libre has its own player, so the same request lives here.
 */
public final class PrizmaProfileMusic {

    private PrizmaProfileMusic() {}

    /** Is this track already on my Telegram profile? */
    public static boolean isSaved(int account, MessageObject mo) {
        TLRPC.Document doc = mo != null ? mo.getDocument() : null;
        if (doc == null) return false;
        MessagesController.SavedMusicIds ids = MessagesController.getInstance(account).getSavedMusicIds();
        if (!ids.loaded && !ids.loading) ids.load();
        return ids.ids.contains(doc.id);
    }

    /** Adds or removes; {@code done} gets the text to show (success or the error). UI thread. */
    public static void toggle(int account, MessageObject mo, Utilities.Callback<String> done) {
        boolean save = !isSaved(account, mo);
        save(account, mo, save, err -> done.run(err != null ? err
                : save ? "Трек добавлен в ваш профиль Telegram" : "Трек убран из профиля Telegram"), false);
    }

    private static void save(int account, MessageObject mo, boolean save, Utilities.Callback<String> done, boolean triedFileRef) {
        final TLRPC.Document document = mo.getDocument();
        if (document == null || document.id == 0) {
            done.run("Этот трек нельзя добавить в профиль");
            return;
        }
        final TLRPC.TL_account_saveMusic req = new TLRPC.TL_account_saveMusic();
        req.unsave = !save;
        req.id = new TLRPC.TL_inputDocument();
        req.id.id = document.id;
        req.id.access_hash = document.access_hash;
        req.id.file_reference = document.file_reference != null ? document.file_reference : new byte[0];
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (err != null && FileRefController.isFileRefError(err.text) && !triedFileRef && mo.getId() > 0) {
                // the file reference went stale — take a fresh copy of the message and try once more
                refresh(account, mo, fresh -> {
                    if (fresh == null) done.run("Не удалось обновить трек — откройте его в чате и попробуйте снова");
                    else save(account, fresh, save, done, true);
                });
                return;
            }
            if (err != null) {
                done.run("Telegram не дал это сделать: " + err.text);
                return;
            }
            MessagesController.getInstance(account).getSavedMusicIds().update(document.id, save);
            final long self = UserConfig.getInstance(account).getClientUserId();
            final TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(self);
            if (full != null) {
                if (save) {
                    full.flags2 |= TLObject.FLAG_21;
                    full.saved_music = document;
                } else if (full.saved_music != null && full.saved_music.id == document.id) {
                    full.flags2 &= ~TLObject.FLAG_21;
                    full.saved_music = null;
                }
                MessagesStorage.getInstance(account).updateUserInfo(full, true);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.profileMusicUpdated, self);
            }
            done.run(null);
        }));
    }

    private static void refresh(int account, MessageObject mo, Utilities.Callback<MessageObject> done) {
        final int msgId = mo.getId();
        final TLObject req;
        if (mo.getDialogId() >= 0) {
            TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.add(msgId);
            req = r;
        } else {
            TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = MessagesController.getInstance(account).getInputChannel(-mo.getDialogId());
            r.id.add(msgId);
            req = r;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.messages_Messages) {
                for (TLRPC.Message m : ((TLRPC.messages_Messages) res).messages) {
                    if (m.id == msgId) {
                        done.run(new MessageObject(account, m, false, true));
                        return;
                    }
                }
            }
            done.run(null);
        }));
    }
}
