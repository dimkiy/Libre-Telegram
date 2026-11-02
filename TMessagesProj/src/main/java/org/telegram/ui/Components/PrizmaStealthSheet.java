package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PasscodeActivity;

/*
 * Libre — что это за файл:
 * Хаб Libre: все настройки и переключатели в одном окне.
 */

public class PrizmaStealthSheet {

    private interface Toggle {
        void set(boolean value);
    }

    public static void show(Context context) {
        show(context, null);
    }

    public static void show(Context context, BaseFragment fragment) {
        final BottomSheet[] sheetRef = new BottomSheet[1];
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        root.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(20));

        TextView title = new TextView(context);
        title.setText("Prizma");
        title.setTextColor(textColor);
        title.setTextSize(24);
        title.setTypeface(AndroidUtilities.bold());
        title.setPadding(AndroidUtilities.dp(6), 0, 0, 0);
        root.addView(title);

        TextView subtitle = new TextView(context);
        subtitle.setText("Приватность и оформление");
        subtitle.setTextColor(grayColor);
        subtitle.setTextSize(13);
        subtitle.setPadding(AndroidUtilities.dp(6), 0, 0, 0);
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 14));

        // ===== РЕЖИМ ПРИЗРАКА =====
        java.util.ArrayList<View> ghost = new java.util.ArrayList<>();
        ghost.add(toggleRow(context, textColor, grayColor,
                "Скрывать «онлайн»", "никто не увидит, что вы в сети",
                PrizmaConfig.hideOnline(), PrizmaConfig::setHideOnline));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Скрывать «печатает»", "не показывать, что вы набираете текст",
                PrizmaConfig.hideTyping(), PrizmaConfig::setHideTyping));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Всегда в сети", "поддерживать статус «онлайн» (не вместе со «Скрывать онлайн»)",
                PrizmaConfig.alwaysOnline(), v -> {
                    PrizmaConfig.setAlwaysOnline(v);
                    if (v) org.telegram.messenger.PrizmaOnline.start();
                    else org.telegram.messenger.PrizmaOnline.stop();
                }));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Невидимое прочтение", "в личных чатах не отправлять «прочитано»",
                PrizmaConfig.invisibleRead(), PrizmaConfig::setInvisibleRead));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Невидимый просмотр сторис", "смотреть истории, не отмечаясь в просмотрах",
                PrizmaConfig.stealthStories(), PrizmaConfig::setStealthStories));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Подтверждать звонки", "сначала кнопка звонка, потом «Позвонить» — без случайных звонков",
                PrizmaConfig.confirmCalls(), PrizmaConfig::setConfirmCalls));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Скрывать IP в звонках", "звонки через сервер — собеседник не видит ваш IP",
                PrizmaConfig.hideCallIp(), PrizmaConfig::setHideCallIp));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Антиудаление", "удалённые сообщения не исчезают из открытого чата",
                PrizmaConfig.antiDelete(), PrizmaConfig::setAntiDelete));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Стирать GPS из фото", "убирать геолокацию и мета-данные из отправляемых изображений",
                PrizmaConfig.stripExif(), PrizmaConfig::setStripExif));
        ghost.add(toggleRow(context, textColor, grayColor,
                "Анонимная пересылка", "пересылать без подписи «переслано от …»",
                PrizmaConfig.anonForward(), PrizmaConfig::setAnonForward));
        addSection(context, root, grayColor, "РЕЖИМ ПРИЗРАКА", ghost,
                "Скрываете «онлайн» — вы тоже не видите, когда другие в сети: так работает Telegram.");

        // ===== ЗАЩИТА =====
        java.util.ArrayList<View> guard = new java.util.ArrayList<>();
        guard.add(toggleRow(context, textColor, grayColor,
                "Не беспокоить", "глушить все уведомления Telegram (локально)",
                PrizmaConfig.dnd(), PrizmaConfig::setDnd));
        guard.add(toggleRow(context, textColor, grayColor,
                "Сохранять из защищённых", "скачивание, пересылка и копирование везде",
                PrizmaConfig.allowSaveRestricted(), PrizmaConfig::setAllowSaveRestricted));
        guard.add(toggleRow(context, textColor, grayColor,
                "Анонимный номер +888", "показывать ваш номер как анонимный (только у вас)",
                PrizmaConfig.fakePhone(), v -> {
                    PrizmaConfig.setFakePhone(v);
                    try {
                        int acc = org.telegram.messenger.UserConfig.selectedAccount;
                        org.telegram.messenger.NotificationCenter.getInstance(acc).postNotificationName(
                                org.telegram.messenger.NotificationCenter.updateInterfaces,
                                org.telegram.messenger.MessagesController.UPDATE_MASK_NAME);
                    } catch (Throwable ignore) {
                    }
                }));
        guard.add(actionRow(context, textColor, grayColor,
                "Выбрать +888 номер", "крути, пока не понравится, и выбери свой номер",
                () -> showPhoneRoller(context)));
        guard.add(actionRow(context, textColor, grayColor,
                "Пароль на архив",
                PrizmaArchiveLock.isLocked() ? "включён — нажмите, чтобы отключить" : "открывать архив только по PIN",
                () -> {
                    if (PrizmaArchiveLock.isLocked()) {
                        PrizmaArchiveLock.verify(context, () -> {
                            PrizmaArchiveLock.disable();
                            android.widget.Toast.makeText(context, "Пароль на архив снят", android.widget.Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        PrizmaArchiveLock.setupPin(context, () ->
                                android.widget.Toast.makeText(context, "Пароль на архив установлен", android.widget.Toast.LENGTH_SHORT).show());
                    }
                }));
        if (fragment != null) {
            guard.add(actionRow(context, textColor, grayColor,
                    "Блокировка приложения", "пароль и отпечаток на вход, автоблокировка", () -> {
                        if (sheetRef[0] != null) {
                            sheetRef[0].dismiss();
                        }
                        fragment.presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_MANAGE_CODE_SETTINGS));
                    }));
        }
        guard.add(actionRow(context, textColor, grayColor,
                "Обмен контактом", "поднесите телефоны друг к другу — одноразовый код по NFC", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    PrizmaNfcExchange.show(context);
                }));
        guard.add(actionRow(context, textColor, grayColor,
                "Стереть следы", "поиск, недавние, буфер, кэш — одной кнопкой или сами через 10 минут", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    android.app.Activity activity = AndroidUtilities.findActivity(context);
                    PrizmaTraces.show(context, activity != null ? activity.getWindow().getDecorView() : null);
                }));
        addSection(context, root, grayColor, "ЗАЩИТА", guard, null);

        // ===== ОФОРМЛЕНИЕ =====
        java.util.ArrayList<View> look = new java.util.ArrayList<>();
        look.add(actionRow(context, textColor, grayColor,
                "Иконка приложения", "сменить иконку Libre на другую",
                () -> showIconPicker(context)));
        look.add(actionRow(context, textColor, grayColor,
                "Размер эмодзи", "уменьшить большие эмодзи в сообщениях",
                () -> showEmojiSize(context)));
        look.add(actionRow(context, textColor, grayColor,
                "Форма пузырей", "скругление пузырей сообщений в чатах",
                () -> showBubbleShape(context)));
        look.add(toggleRow(context, textColor, grayColor,
                "Стеклянные пузыри", "полупрозрачные пузыри — сквозь них видно обои",
                PrizmaConfig.glassBubbles(), PrizmaConfig::setGlassBubbles));
        look.add(actionRow(context, textColor, grayColor,
                "Размер стикеров", "уменьшить стикеры и анимированные эмодзи",
                () -> showStickerSize(context)));
        look.add(toggleRow(context, textColor, grayColor,
                "Переливающийся профиль", "анимированный градиент в шапке профиля",
                PrizmaConfig.iridescentProfile(), PrizmaConfig::setIridescentProfile));
        look.add(toggleRow(context, textColor, grayColor,
                "Перелив на имени и аватаре", "градиент на имени + свечение вокруг аватара",
                PrizmaConfig.iridescentName(), PrizmaConfig::setIridescentName));
        // live wallpaper toggle + palette grouped as one card row (so palette can hide without a stray divider)
        final LinearLayout paletteRow = paletteRow(context);
        paletteRow.setVisibility(PrizmaConfig.liveWallpaper() ? View.VISIBLE : View.GONE);
        LinearLayout wallGroup = new LinearLayout(context);
        wallGroup.setOrientation(LinearLayout.VERTICAL);
        wallGroup.addView(toggleRow(context, textColor, grayColor,
                "Живые обои чата", "переливающийся градиент на фоне переписки",
                PrizmaConfig.liveWallpaper(), v -> {
                    PrizmaConfig.setLiveWallpaper(v);
                    paletteRow.setVisibility(v ? View.VISIBLE : View.GONE);
                }));
        wallGroup.addView(paletteRow);
        look.add(wallGroup);
        look.add(actionRow(context, textColor, grayColor,
                "Свои обои чата (фото/видео)", "поставить своё изображение или видео фоном чата", () -> {
                    if (context instanceof android.app.Activity) {
                        PrizmaChatWallpaper.pick((android.app.Activity) context);
                    }
                }));
        if (PrizmaChatWallpaper.has()) {
            look.add(actionRow(context, textColor, grayColor,
                    "Убрать свои обои", "вернуть обычный фон чата", PrizmaChatWallpaper::clear));
        }
        look.add(actionRow(context, textColor, grayColor,
                "Баннер профиля", "картинка или видео из галереи", () -> {
                    if (context instanceof android.app.Activity) {
                        PrizmaBanner.pick((android.app.Activity) context);
                    }
                }));
        if (PrizmaBanner.hasBanner()) {
            look.add(actionRow(context, textColor, grayColor,
                    "Убрать баннер", "вернуть обычную шапку профиля", PrizmaBanner::clear));
        }
        look.add(actionRow(context, textColor, grayColor,
                "Витрина подарков", "добавить любые подарки на свой профиль", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    PrizmaGiftsSheet.show(context, null);
                }));
        look.add(actionRow(context, textColor, grayColor,
                "Свой шрифт", "любой шрифт (.ttf/.otf) на весь интерфейс", () -> {
                    if (context instanceof android.app.Activity) {
                        org.telegram.messenger.PrizmaFonts.pick((android.app.Activity) context);
                    }
                }));
        if (org.telegram.messenger.PrizmaFonts.hasCustom()) {
            look.add(actionRow(context, textColor, grayColor,
                    "Сбросить шрифт", "вернуть стандартный шрифт", () -> {
                        org.telegram.messenger.PrizmaFonts.clear();
                        android.widget.Toast.makeText(context, "Шрифт сброшен. Перезапустите приложение", android.widget.Toast.LENGTH_LONG).show();
                    }));
        }
        addSection(context, root, grayColor, "ОФОРМЛЕНИЕ", look, null);

        // ===== ПРОФИЛЬ LIBRE =====
        java.util.ArrayList<View> social = new java.util.ArrayList<>();
        social.add(toggleRow(context, textColor, grayColor,
                "Блок Libre в профилях", "достижения, «в Libre с», клан и стена",
                org.telegram.messenger.PrizmaSocial.cardEnabled(), org.telegram.messenger.PrizmaSocial::setCardEnabled));
        social.add(actionRow(context, textColor, grayColor,
                "Моя стена", "записи, комментарии, кто может писать", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    BaseFragment f = fragment != null ? fragment : org.telegram.ui.LaunchActivity.getSafeLastFragment();
                    if (f != null) {
                        f.presentFragment(new org.telegram.ui.PrizmaWallActivity(
                                org.telegram.messenger.UserConfig.getInstance(org.telegram.messenger.UserConfig.selectedAccount).getClientUserId(), 0));
                    }
                }));
        social.add(actionRow(context, textColor, grayColor,
                "Мой клан", org.telegram.messenger.PrizmaClans.mine(org.telegram.messenger.UserConfig.selectedAccount) != null ? "участники и приглашения" : "создать свой клан с тегом", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    BaseFragment f = fragment != null ? fragment : org.telegram.ui.LaunchActivity.getSafeLastFragment();
                    if (f != null) {
                        int acc = org.telegram.messenger.UserConfig.selectedAccount;
                        PrizmaClanSheet.show(f, acc, org.telegram.messenger.UserConfig.getInstance(acc).getClientUserId(), null);
                    }
                }));
        social.add(toggleRow(context, textColor, grayColor,
                "Не отмечаться в просмотрах", "ваши визиты не попадут в счётчик чужих профилей",
                org.telegram.messenger.PrizmaSocial.noViewMark(), org.telegram.messenger.PrizmaSocial::setNoViewMark));
        social.add(actionRow(context, textColor, grayColor,
                "Облачная копия Libre", "настройки, темы и плейлисты — вернуть на новом телефоне", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    PrizmaBackup.show(context);
                }));
        social.add(actionRow(context, textColor, grayColor,
                "Что сервер знает обо мне", "всё, что хранится о вас, и выгрузка", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    PrizmaBackup.showWhatServerKnows(context);
                }));
        social.add(toggleRow(context, textColor, grayColor,
                "Показывать анонимный номер", "номер +888 из Fragment видят в вашем профиле все в Libre; обычный номер не показывается никогда",
                PrizmaConfig.showAnonNumber(), on -> {
                    PrizmaConfig.setShowAnonNumber(on);
                    org.telegram.messenger.PrizmaPresence.syncAnonNumber(org.telegram.messenger.UserConfig.selectedAccount);
                }));
        social.add(toggleRow(context, textColor, grayColor,
                "Уведомления из MAX", "сообщения из всех чатов MAX приходят вам от бота Libre в Telegram",
                PrizmaConfig.maxForward(), on -> {
                    PrizmaConfig.setMaxForward(on);
                    if (on) PrizmaMaxSetup.start(context);
                }));
        social.add(actionRow(context, textColor, grayColor,
                "Сервер Libre", PrizmaCloudSetup.status(), () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    PrizmaCloudSetup.show(context);
                }));
        addSection(context, root, grayColor, "ПРОФИЛЬ LIBRE", social,
                "С сервером Libre другие пользователи Libre видят ваш баннер, стену, клан, NFT и открытый плейлист. Переписка на сервер не попадает.");

        // ===== МУЗЫКА =====
        java.util.ArrayList<View> music = new java.util.ArrayList<>();
        music.add(actionRow(context, textColor, grayColor,
                "Мой плейлист", "лайкнутая музыка — слушать в одном месте", () -> {
                    PrizmaPlaylistSheet.show(context);
                }));
        music.add(actionRow(context, textColor, grayColor,
                "Звук уведомлений", "свой звук на сообщения из галереи/рингтонов", () -> {
                    if (context instanceof android.app.Activity) {
                        org.telegram.messenger.PrizmaSounds.pick((android.app.Activity) context);
                    }
                }));
        final PrizmaTogether together = PrizmaTogether.get();
        music.add(actionRow(context, textColor, grayColor,
                together.isHosting() ? "Завершить эфир" : together.isFollowing() ? "Выйти из эфира" : "Слушать вместе",
                together.isHosting() ? "вы ведёте эфир — друзья слышат синхронно" : together.isFollowing() ? "вы слушаете чужой эфир синхронно" : "друг в Libre слышит то же, что и вы, секунда в секунду", () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    if (together.isHosting()) {
                        together.stopHosting();
                    } else if (together.isFollowing()) {
                        together.leave();
                    } else {
                        PrizmaTogether.pickChatAndStart(context);
                    }
                }));
        music.add(toggleRow(context, textColor, grayColor,
                "Музыкальный статус", "в «О себе» видно всем: 🎧 исполнитель — трек; после музыки вернётся ваш текст",
                PrizmaConfig.musicStatus(), v -> {
                    PrizmaConfig.setMusicStatus(v);
                    org.telegram.messenger.PrizmaMusicStatus.onToggle();
                }));
        addSection(context, root, grayColor, "МУЗЫКА", music,
                "Музыкальный статус выдаёт, что вы сейчас в телефоне — даже при скрытом «онлайн».");

        // ===== ПРОЧЕЕ =====
        java.util.ArrayList<View> misc = new java.util.ArrayList<>();
        misc.add(toggleRow(context, textColor, grayColor,
                "Скрывать рекламу", "без спонсорских сообщений в каналах и ботах",
                PrizmaConfig.hideAds(), PrizmaConfig::setHideAds));
        misc.add(toggleRow(context, textColor, grayColor,
                "Перевод по тапу", "пункт «Перевести» всегда в меню сообщения",
                PrizmaConfig.tapTranslate(), PrizmaConfig::setTapTranslate));
        misc.add(toggleRow(context, textColor, grayColor,
                "Lite-режим (оптимизация)", "отключить тяжёлые анимации/блюр — меньше лагов",
                PrizmaConfig.liteMode(), v -> {
                    if (v) {
                        PrizmaConfig.setLitePrevFlags(org.telegram.messenger.LiteMode.getValue(true));
                        org.telegram.messenger.LiteMode.setAllFlags(org.telegram.messenger.LiteMode.PRESET_POWER_SAVER);
                        PrizmaConfig.setLiteMode(true);
                    } else {
                        int prev = PrizmaConfig.litePrevFlags();
                        org.telegram.messenger.LiteMode.setAllFlags(prev >= 0 ? prev : org.telegram.messenger.LiteMode.PRESET_HIGH);
                        PrizmaConfig.setLiteMode(false);
                    }
                    org.telegram.ui.Components.PrizmaWallpaper.invalidate();
                }));
        if (fragment != null) {
            misc.add(actionRow(context, textColor, grayColor,
                    "Крипто-кошелёк @walt", "открыть кошелёк — вернули в меню",
                    () -> {
                        if (sheetRef[0] != null) sheetRef[0].dismiss();
                        try {
                            org.telegram.messenger.MessagesController.getInstance(org.telegram.messenger.UserConfig.selectedAccount)
                                    .openByUserName("walt", fragment, 0);
                        } catch (Throwable ignore) {
                        }
                    }));
        }
        misc.add(actionRow(context, textColor, grayColor,
                "Проверить обновления", "Libre " + org.telegram.messenger.BuildVars.BUILD_VERSION_STRING, () -> {
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    android.app.Activity a = AndroidUtilities.findActivity(context);
                    if (a != null) PrizmaUpdater.checkNow(a);
                }));
        addSection(context, root, grayColor, "ПРОЧЕЕ", misc, null);

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = (int) (AndroidUtilities.displaySize.y * 0.82f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setNestedScrollingEnabled(true);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setCustomView(scroll);
        sheetRef[0] = builder.create();
        try {
            sheetRef[0].setCanDismissWithSwipe(false); // dismiss via tap-outside/back; stops accidental drag-close
        } catch (Throwable ignore) {}
        try { sheetRef[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundGray)); } catch (Throwable ignore) {}
        try { sheetRef[0].setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray)); } catch (Throwable ignore) {}
        sheetRef[0].show();
    }

    private static LinearLayout paletteRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(12));

        final Swatch[] swatches = new Swatch[PrizmaWallpaper.PALETTES.length];
        for (int i = 0; i < swatches.length; i++) {
            final int index = i;
            Swatch s = new Swatch(context, index);
            swatches[i] = s;
            s.setOnClickListener(v -> {
                PrizmaConfig.setLiveWallpaperPalette(index);
                PrizmaWallpaper.invalidate();
                for (Swatch other : swatches) {
                    other.invalidate();
                }
            });
            row.addView(s, LayoutHelper.createLinear(44, 44, 0, 0, i == swatches.length - 1 ? 0 : 12, 0));
        }
        return row;
    }

    private static class Swatch extends View {
        private final int index;
        private final MotionBackgroundDrawable preview;
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        Swatch(Context context, int index) {
            super(context);
            this.index = index;
            this.preview = PrizmaWallpaper.preview(index);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(2.5f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int pad = AndroidUtilities.dp(2);
            bounds.set(pad, pad, getWidth() - pad, getHeight() - pad);
            float r = bounds.width() / 2f;
            clip.reset();
            clip.addCircle(bounds.centerX(), bounds.centerY(), r, android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            preview.setBounds((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
            preview.draw(canvas);
            canvas.restore();
            if (index == PrizmaWallpaper.clampPalette(PrizmaConfig.liveWallpaperPalette())) {
                ringPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), r + AndroidUtilities.dp(1f), ringPaint);
            }
        }
    }

    /** Picker for the app launcher icon (uses Telegram's built-in icon aliases, no premium gate). */
    private static void showIconPicker(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(14));

        TextView title = new TextView(context);
        title.setText("Иконка приложения");
        title.setTextColor(textColor);
        title.setTextSize(19);
        title.setTypeface(AndroidUtilities.bold());
        title.setPadding(AndroidUtilities.dp(6), 0, 0, AndroidUtilities.dp(10));
        root.addView(title);

        final BottomSheet[] ref = new BottomSheet[1];
        // Libre: any picture of your own — as a home-screen icon (Android can't swap the app icon itself for it)
        LinearLayout own = new LinearLayout(context);
        own.setOrientation(LinearLayout.HORIZONTAL);
        own.setGravity(Gravity.CENTER_VERTICAL);
        own.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        own.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        android.widget.ImageView plus = new android.widget.ImageView(context);
        plus.setImageResource(org.telegram.messenger.R.drawable.msg_gallery);
        plus.setColorFilter(textColor);
        plus.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        android.graphics.drawable.GradientDrawable plusBg = new android.graphics.drawable.GradientDrawable();
        plusBg.setCornerRadius(AndroidUtilities.dp(11));
        plusBg.setColor(Theme.multAlpha(textColor, 0.07f));
        plus.setBackground(plusBg);
        own.addView(plus, LayoutHelper.createLinear(46, 46, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        LinearLayout ownTexts = new LinearLayout(context);
        ownTexts.setOrientation(LinearLayout.VERTICAL);
        TextView ownName = new TextView(context);
        ownName.setText("Своя картинка");
        ownName.setTextColor(textColor);
        ownName.setTextSize(16);
        ownTexts.addView(ownName);
        TextView ownSub = new TextView(context);
        ownSub.setText("любая картинка из галереи — иконкой Libre на рабочем столе");
        ownSub.setTextColor(grayColor);
        ownSub.setTextSize(13);
        ownTexts.addView(ownSub);
        own.addView(ownTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        own.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            if (context instanceof android.app.Activity) PrizmaAppIcon.pick((android.app.Activity) context);
        });
        root.addView(own, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (org.telegram.ui.LauncherIconController.LauncherIcon icon : org.telegram.ui.LauncherIconController.LauncherIcon.values()) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

            android.widget.FrameLayout preview = new android.widget.FrameLayout(context);
            preview.setClipToOutline(true);
            android.graphics.drawable.GradientDrawable clip = new android.graphics.drawable.GradientDrawable();
            clip.setCornerRadius(AndroidUtilities.dp(11));
            preview.setBackground(clip);
            android.widget.ImageView bgIv = new android.widget.ImageView(context);
            bgIv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            try { bgIv.setImageResource(icon.background); } catch (Throwable ignore) {}
            preview.addView(bgIv, LayoutHelper.createFrame(46, 46));
            android.widget.ImageView fgIv = new android.widget.ImageView(context);
            fgIv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            try { fgIv.setImageResource(icon.foreground); } catch (Throwable ignore) {}
            preview.addView(fgIv, LayoutHelper.createFrame(46, 46));
            android.view.ViewOutlineProvider op = new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(11));
                }
            };
            preview.setOutlineProvider(op);
            row.addView(preview, LayoutHelper.createLinear(46, 46, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            TextView name = new TextView(context);
            try { name.setText(getString(icon.title)); } catch (Throwable e) { name.setText(icon.key); }
            name.setTextColor(textColor);
            name.setTextSize(16);
            row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            if (org.telegram.ui.LauncherIconController.isEnabled(icon)) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextColor(0xff4CC764);
                check.setTextSize(18);
                check.setTypeface(AndroidUtilities.bold());
                row.addView(check, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            }

            final org.telegram.ui.LauncherIconController.LauncherIcon fIcon = icon;
            row.setOnClickListener(v -> {
                try {
                    org.telegram.ui.LauncherIconController.setIcon(fIcon);
                    android.widget.Toast.makeText(context, "Иконка изменена", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {}
                if (ref[0] != null) ref[0].dismiss();
            });
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(scroll);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static String getString(int res) {
        return org.telegram.messenger.LocaleController.getString(res);
    }

    /** Slot-machine roller to pick a liked anonymous +888 number. */
    private static void showPhoneRoller(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(16));

        TextView title = new TextView(context);
        title.setText("Анонимный номер");
        title.setTextColor(textColor);
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

        final TextView numberView = new TextView(context);
        numberView.setText(PrizmaConfig.fakePhoneNumber());
        numberView.setTextColor(textColor);
        numberView.setTextSize(30);
        numberView.setTypeface(AndroidUtilities.bold());
        numberView.setLetterSpacing(0.04f);
        root.addView(numberView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        TextView hint = new TextView(context);
        hint.setText("Крути, пока не понравится, и нажми «Выбрать».");
        hint.setTextColor(grayColor);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 22));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        final TextView rollBtn = rollerButton(context, "Крутить", false);
        final TextView chooseBtn = rollerButton(context, "Выбрать", true);
        buttons.addView(rollBtn, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 5, 0));
        buttons.addView(chooseBtn, LayoutHelper.createLinear(0, 48, 1f, 5, 0, 0, 0));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final BottomSheet[] ref = new BottomSheet[1];
        final String[] current = {PrizmaConfig.fakePhoneNumber()};
        final boolean[] spinning = {false};

        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        rollBtn.setOnClickListener(v -> {
            if (spinning[0]) return;
            spinning[0] = true;
            rollBtn.setAlpha(0.6f);
            final int totalSteps = 26;
            final int[] step = {0};
            final String[] fin = {PrizmaConfig.randomFakePhoneNumber()};
            final Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                boolean done = step[0] >= totalSteps;
                String s = done ? fin[0] : PrizmaConfig.randomFakePhoneNumber();
                // slot-machine feel: new value drops in from above with a quick fade
                numberView.animate().cancel();
                numberView.setText(s);
                numberView.setTranslationY(-AndroidUtilities.dp(14));
                numberView.setAlpha(0.35f);
                numberView.animate().translationY(0f).alpha(1f)
                        .setDuration(done ? 260 : 70)
                        .setInterpolator(done ? new OvershootInterpolator(2.4f) : CubicBezierInterpolator.EASE_OUT)
                        .start();
                if (step[0] % 5 == 0) {
                    try { numberView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignore) {}
                }
                if (done) {
                    current[0] = fin[0];
                    spinning[0] = false;
                    rollBtn.setAlpha(1f);
                    // settle: accent flash + gentle pop
                    numberView.setTextColor(accent);
                    numberView.setScaleX(0.9f); numberView.setScaleY(0.9f);
                    numberView.animate().scaleX(1f).scaleY(1f).setDuration(300)
                            .setInterpolator(new OvershootInterpolator(3f)).start();
                    AndroidUtilities.runOnUIThread(() -> numberView.setTextColor(textColor), 550);
                    try { numberView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignore) {}
                    return;
                }
                long delay = 40 + (long) (step[0] * step[0] * 0.9f); // ease-out: slows down near the end
                step[0]++;
                AndroidUtilities.runOnUIThread(tick[0], delay);
            };
            AndroidUtilities.runOnUIThread(tick[0], 30);
        });

        chooseBtn.setOnClickListener(v -> {
            if (spinning[0]) return;
            PrizmaConfig.setFakePhoneNumber(current[0]);
            PrizmaConfig.setFakePhone(true);
            try {
                int acc = org.telegram.messenger.UserConfig.selectedAccount;
                org.telegram.messenger.NotificationCenter.getInstance(acc).postNotificationName(
                        org.telegram.messenger.NotificationCenter.updateInterfaces,
                        org.telegram.messenger.MessagesController.UPDATE_MASK_NAME);
            } catch (Throwable ignore) {}
            if (ref[0] != null) ref[0].dismiss();
            android.widget.Toast.makeText(context, "Номер выбран: " + current[0], android.widget.Toast.LENGTH_SHORT).show();
        });

        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static TextView rollerButton(Context context, String text, boolean filled) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setTypeface(AndroidUtilities.bold());
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(13));
        if (filled) {
            bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            t.setTextColor(0xffffffff);
        } else {
            bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.08f));
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        t.setBackground(bg);
        return t;
    }

    /** Adds a titled group: small caps label, a rounded card holding the rows, and an optional gray hint below. */
    private static void addSection(Context c, LinearLayout root, int grayColor, String label, java.util.List<View> rows, String hint) {
        root.addView(sectionHeader(c, grayColor, label));
        root.addView(card(c, rows), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, hint == null ? 10 : 6));
        if (hint != null) {
            TextView h = new TextView(c);
            h.setText(hint);
            h.setTextColor(grayColor);
            h.setTextSize(12);
            h.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
            root.addView(h, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        }
    }

    /** Rounded card container with hairline dividers between rows. */
    private static LinearLayout card(Context c, java.util.List<View> rows) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(16));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setBackground(bg);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(2), AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        int divColor = Theme.getColor(Theme.key_divider);
        int divH = Math.max(1, (int) (AndroidUtilities.density + 0.5f));
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                View div = new View(c);
                div.setBackgroundColor(divColor);
                card.addView(div, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, divH));
            }
            card.addView(rows.get(i), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    private static void showStickerSize(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        String[] names = {"Обычный (100%)", "Поменьше (85%)", "Маленький (70%)", "Крошечный (55%)"};
        float[] vals = {1f, 0.85f, 0.7f, 0.55f};
        float cur = PrizmaConfig.stickerScale();
        for (int i = 0; i < names.length; i++) {
            final float v = vals[i];
            boolean sel = Math.abs(cur - v) < 0.02f;
            root.addView(actionRow(context, sel ? accent : textColor, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
                    names[i] + (sel ? "  ✓" : ""), "", () -> {
                        PrizmaConfig.setStickerScale(v);
                        if (ref[0] != null) ref[0].dismiss();
                        android.widget.Toast.makeText(context, "Размер стикеров изменён (применится при открытии чата)", android.widget.Toast.LENGTH_SHORT).show();
                    }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static void showBubbleShape(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        String[] names = {"Острые", "Средние", "Скруглённые", "Круглые"};
        int[] vals = {3, 8, 13, 17};
        int cur = org.telegram.messenger.SharedConfig.bubbleRadius;
        for (int i = 0; i < names.length; i++) {
            final int v = vals[i];
            boolean sel = Math.abs(cur - v) <= 2;
            root.addView(actionRow(context, sel ? accent : textColor, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
                    names[i] + " (" + v + ")" + (sel ? "  ✓" : ""), "", () -> {
                        org.telegram.messenger.SharedConfig.bubbleRadius = v;
                        try {
                            org.telegram.messenger.ApplicationLoader.applicationContext
                                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                                    .edit().putInt("bubbleRadius", v).apply();
                        } catch (Throwable ignore) {}
                        try { Theme.createCommonMessageResources(); } catch (Throwable ignore) {}
                        if (ref[0] != null) ref[0].dismiss();
                        android.widget.Toast.makeText(context, "Форма пузырей изменена (применится при открытии чата)", android.widget.Toast.LENGTH_SHORT).show();
                    }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static void showEmojiSize(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        String[] names = {"Обычный (100%)", "Поменьше (75%)", "Маленький (55%)", "Крошечный (40%)"};
        float[] vals = {1f, 0.75f, 0.55f, 0.4f};
        float cur = PrizmaConfig.emojiScale();
        for (int i = 0; i < names.length; i++) {
            final float v = vals[i];
            boolean sel = Math.abs(cur - v) < 0.02f;
            root.addView(actionRow(context, sel ? accent : textColor, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
                    names[i] + (sel ? "  ✓" : ""), "", () -> {
                        PrizmaConfig.setEmojiScale(v);
                        try { Theme.createCommonMessageResources(); } catch (Throwable ignore) {}
                        try {
                            org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(
                                    org.telegram.messenger.NotificationCenter.emojiLoaded);
                        } catch (Throwable ignore) {}
                        if (ref[0] != null) ref[0].dismiss();
                        android.widget.Toast.makeText(context, "Размер эмодзи сохранён. Перезапусти приложение, чтобы применить ко всем чатам", android.widget.Toast.LENGTH_LONG).show();
                    }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static TextView sectionHeader(Context context, int grayColor, String title) {
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextColor(grayColor);
        t.setTextSize(12);
        t.setLetterSpacing(0.08f);
        t.setTypeface(AndroidUtilities.bold());
        t.setPadding(0, AndroidUtilities.dp(18), 0, AndroidUtilities.dp(6));
        return t;
    }

    private static LinearLayout actionRow(Context context, int textColor, int grayColor,
                                          String name, String desc, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(context);
        n.setText(name);
        n.setTextColor(textColor);
        n.setTextSize(15);
        texts.addView(n);
        TextView d = new TextView(context);
        d.setText(desc);
        d.setTextColor(grayColor);
        d.setTextSize(12);
        texts.addView(d, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static LinearLayout toggleRow(Context context, int textColor, int grayColor,
                                          String name, String desc, boolean checked, Toggle toggle) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(context);
        n.setText(name);
        n.setTextColor(textColor);
        n.setTextSize(15);
        texts.addView(n);
        TextView d = new TextView(context);
        d.setText(desc);
        d.setTextColor(grayColor);
        d.setTextSize(12);
        texts.addView(d, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        final Switch sw = new Switch(context);
        sw.setColors(Theme.key_switchTrackBlue, Theme.key_switchTrackBlueChecked, Theme.key_switchTrackBlueThumb, Theme.key_switchTrackBlueThumbChecked);
        sw.setChecked(checked, false);
        row.addView(sw, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL));

        row.setOnClickListener(v -> {
            boolean nv = !sw.isChecked();
            sw.setChecked(nv, true);
            toggle.set(nv);
        });

        return row;
    }
}
