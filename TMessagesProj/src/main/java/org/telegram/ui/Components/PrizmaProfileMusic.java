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

public final class PrizmaProfileMusic {
    private PrizmaProfileMusic() {}

    public static boolean isSaved(int account, MessageObject mo) {
        TLRPC.Document doc = mo != null ? mo.getDocument() : null;
        if (doc == null) return false;
        MessagesController.SavedMusicIds ids = MessagesController.getInstance(account).getSavedMusicIds();
        if (!ids.loaded && !ids.loading) ids.load();
        return ids.ids.contains(doc.id);
    }

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
            if (err != null && FileRefController.isFileRefError(err.text)) {
                if (!triedFileRef && mo.getId() > 0) {
                    refresh(account, mo, fresh -> {
                        if (fresh != null && fresh.getDocument() != null) save(account, fresh, save, done, true);
                        else reupload(account, mo, save, done);
                    });
                } else {
                    reupload(account, mo, save, done);
                }
                return;
            }
            if (err != null) {
                done.run(explain(err.text));
                return;
            }
            applySaved(account, document, save);
            done.run(null);
        }));
    }

    private static String explain(String code) {
        if (code == null) return "Не получилось, попробуйте ещё раз";
        if (code.startsWith("FLOOD_WAIT")) return "Слишком часто. Подождите немного и попробуйте снова";
        if (code.contains("MUSIC") || code.contains("DOCUMENT_INVALID")) return "Этот файл Telegram не принимает как музыку профиля";
        return "Telegram не дал это сделать: " + code;
    }

    private static void reupload(int account, MessageObject mo, boolean save, Utilities.Callback<String> done) {
        final TLRPC.Document document = mo.getDocument();
        if (!save) {
            MessagesController.getInstance(account).getSavedMusicIds().update(document.id, false);
            applySaved(account, document, false);
            done.run(null);
            return;
        }
        java.io.File file = null;
        try {
            if (!android.text.TextUtils.isEmpty(mo.messageOwner.attachPath)) {
                java.io.File f = new java.io.File(mo.messageOwner.attachPath);
                if (f.exists()) file = f;
            }
            if (file == null) {
                java.io.File f = org.telegram.messenger.FileLoader.getInstance(account).getPathToMessage(mo.messageOwner);
                if (f != null && f.exists()) file = f;
            }
            if (file == null) {
                java.io.File f = org.telegram.messenger.FileLoader.getInstance(account).getPathToAttach(document, true);
                if (f != null && f.exists()) file = f;
            }
            if (file == null) {
                java.io.File f = org.telegram.messenger.FileLoader.getInstance(account).getPathToAttach(document, false);
                if (f != null && f.exists()) file = f;
            }
        } catch (Throwable ignore) {
        }
        if (file == null) {
            done.run("Трек ещё не скачан. Дослушайте или скачайте его и попробуйте снова");
            return;
        }
        final String path = file.getAbsolutePath();
        org.telegram.messenger.FileLoader.getInstance(account).uploadFile(path, uploaded -> AndroidUtilities.runOnUIThread(() -> {
            if (uploaded == null) {
                done.run("Не удалось загрузить трек, проверьте интернет");
                return;
            }
            final TLRPC.TL_messages_uploadMedia up = new TLRPC.TL_messages_uploadMedia();
            up.peer = MessagesController.getInstance(account).getInputPeer(UserConfig.getInstance(account).getClientUserId());
            TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
            media.file = uploaded;
            media.mime_type = document.mime_type != null ? document.mime_type : "audio/mpeg";
            media.attributes.addAll(document.attributes);
            up.media = media;
            ConnectionsManager.getInstance(account).sendRequest(up, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (!(res instanceof TLRPC.TL_messageMediaDocument) || ((TLRPC.TL_messageMediaDocument) res).document == null) {
                    done.run(err != null ? explain(err.text) : "Не удалось загрузить трек");
                    return;
                }
                final TLRPC.Document fresh = ((TLRPC.TL_messageMediaDocument) res).document;
                final TLRPC.TL_account_saveMusic again = new TLRPC.TL_account_saveMusic();
                again.id = new TLRPC.TL_inputDocument();
                again.id.id = fresh.id;
                again.id.access_hash = fresh.access_hash;
                again.id.file_reference = fresh.file_reference != null ? fresh.file_reference : new byte[0];
                ConnectionsManager.getInstance(account).sendRequest(again, (r2, e2) -> AndroidUtilities.runOnUIThread(() -> {
                    if (e2 != null) {
                        done.run(explain(e2.text));
                        return;
                    }
                    MessagesController.getInstance(account).getSavedMusicIds().update(document.id, true);
                    applySaved(account, fresh, true);
                    done.run(null);
                }));
            }));
        }));
    }

    private static void applySaved(int account, TLRPC.Document document, boolean save) {
        {
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
        }
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
