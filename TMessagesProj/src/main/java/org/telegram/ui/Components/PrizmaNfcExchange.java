package org.telegram.ui.Components;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaNfcService;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Random;

/*
 * Libre — что это за файл:
 * Экран обмена контактом касанием телефонов.
 */

/**
 * Prizma: "tap phones" contact exchange (like Apple's NameDrop).
 *
 * Both phones open this screen and touch with their top edges. Each phone alternates between
 * being an NFC card that holds a ONE-TIME code ({@link PrizmaNfcService}) and an NFC reader, so
 * sooner or later each reads the other — the exchange goes both ways. The code is a temporary
 * Telegram contact token: it reveals neither the phone number nor the username, works for one tap
 * and then a fresh one is armed. Phones without NFC get a QR code with the same one-time link.
 *
 * When a tap lands, a wave of light runs down from the top edge (where the phones touched), the
 * ring around the avatar flashes, and the other person's card rises into place.
 */
public class PrizmaNfcExchange extends Dialog {

    private static final int COLOR_IN = 0xff64D2FF;  // the contact we received
    private static final int COLOR_OUT = 0xffBF8CFF; // our code went to the other phone

    private final Activity activity;
    private final int account = UserConfig.selectedAccount;
    private final NfcAdapter adapter;
    private final boolean canShare;
    private final Random rnd = new Random();

    private Scene scene;
    private FrameLayout centerBox;
    private BackupImageView avatar;
    private ImageView qrView;
    private TextView status, hint, qrLink;
    private TextView nfcSettings;
    private LinearLayout card;

    private boolean alive;
    private boolean readerOn;
    private volatile boolean reading;
    private boolean importing, received;
    private boolean pausedArmed;
    private String currentUrl;
    private boolean showingQr;

    public static void show(Context context) {
        Activity a = AndroidUtilities.findActivity(context);
        if (a == null) return;
        new PrizmaNfcExchange(a).show();
    }

    private PrizmaNfcExchange(Activity activity) {
        super(activity);
        this.activity = activity;
        this.adapter = NfcAdapter.getDefaultAdapter(activity);
        this.canShare = adapter != null && activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
    }

    // ===================== layout =====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Context ctx = getContext();

        FrameLayout root = new FrameLayout(ctx);
        scene = new Scene(ctx);
        root.addView(scene, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(AndroidUtilities.dp(28), AndroidUtilities.statusBarHeight + AndroidUtilities.dp(12), AndroidUtilities.dp(28), AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(22));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // top bar: close
        FrameLayout top = new FrameLayout(ctx);
        ImageView close = new ImageView(ctx);
        close.setImageResource(R.drawable.ic_close_white);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setColorFilter(0xccffffff);
        close.setOnClickListener(v -> dismiss());
        top.addView(close, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, -10, 0, 0, 0));
        column.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        TextView title = new TextView(ctx);
        title.setText("Обмен контактом");
        title.setTextColor(0xffffffff);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));

        hint = new TextView(ctx);
        hint.setText("Поднесите телефон верхним краем к другому телефону, где открыт этот экран Libre");
        hint.setTextColor(0x99ffffff);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(AndroidUtilities.dp(2), 1f);
        column.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        // centre: our avatar (or the QR), the rings live on the Scene behind it
        centerBox = new FrameLayout(ctx);
        avatar = new BackupImageView(ctx);
        avatar.setRoundRadius(AndroidUtilities.dp(60));
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self != null) {
            avatar.setForUserOrChat(self, new AvatarDrawable(self));
        }
        centerBox.addView(avatar, LayoutHelper.createFrame(120, 120, Gravity.CENTER));
        qrView = new ImageView(ctx);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrView.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        GradientDrawable qrBg = new GradientDrawable();
        qrBg.setColor(0xffffffff);
        qrBg.setCornerRadius(AndroidUtilities.dp(24));
        qrView.setBackground(qrBg);
        qrView.setVisibility(View.GONE);
        centerBox.addView(qrView, LayoutHelper.createFrame(230, 230, Gravity.CENTER));
        column.addView(centerBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // the received contact (hidden until a tap lands)
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setVisibility(View.GONE);
        column.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        status = new TextView(ctx);
        status.setTextColor(0xffffffff);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        status.setTypeface(AndroidUtilities.bold());
        status.setGravity(Gravity.CENTER);
        column.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        nfcSettings = new TextView(ctx);
        nfcSettings.setText("Открыть настройки NFC");
        nfcSettings.setTextColor(0xff111114);
        nfcSettings.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nfcSettings.setTypeface(AndroidUtilities.bold());
        nfcSettings.setGravity(Gravity.CENTER);
        nfcSettings.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(23)).setGlassTint(0xf2ffffff));
        nfcSettings.setVisibility(View.GONE);
        nfcSettings.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
            } catch (Throwable e) {
                try { activity.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Throwable ignore) {}
            }
        });
        column.addView(nfcSettings, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 30, 8, 30, 6));

        TextView privacy = new TextView(ctx);
        privacy.setText("Передаётся одноразовый код, а не номер и не юзернейм. После касания он сгорает.");
        privacy.setTextColor(0x5cffffff);
        privacy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        privacy.setGravity(Gravity.CENTER);
        column.addView(privacy, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10, 6, 10, 0));

        qrLink = new TextView(ctx);
        qrLink.setText("Показать QR-код");
        qrLink.setTextColor(0xccffffff);
        qrLink.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        qrLink.setGravity(Gravity.CENTER);
        qrLink.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(4));
        qrLink.setOnClickListener(v -> setQrVisible(!showingQr));
        column.addView(qrLink, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setContentView(root, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setDimAmount(0f);
            try {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(0x00000000);
                window.setNavigationBarColor(0x00000000);
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            } catch (Throwable ignore) {
            }
        }
    }

    // ===================== lifecycle =====================

    @Override
    public void show() {
        super.show();
        alive = true;
        if (adapter == null) {
            status.setText("На этом телефоне нет NFC");
            hint.setText("Покажите QR-код — его можно отсканировать камерой любого телефона");
            requestCode();
            AndroidUtilities.runOnUIThread(() -> setQrVisible(true), 400);
            return;
        }
        requestCode();
        AndroidUtilities.runOnUIThread(duplex, 300);
    }

    @Override
    public void dismiss() {
        alive = false;
        AndroidUtilities.cancelRunOnUIThread(duplex);
        AndroidUtilities.cancelRunOnUIThread(rearm);
        PrizmaNfcService.disarm();
        setReader(false);
        try {
            super.dismiss();
        } catch (Throwable ignore) {
        }
    }

    // ===================== one-time code =====================

    private final Runnable rearm = this::requestCode;

    /** A fresh temporary contact token from Telegram: reveals neither number nor username, expires by itself. */
    private void requestCode() {
        if (!alive) return;
        AndroidUtilities.cancelRunOnUIThread(rearm);
        if (!received && !importing) status.setText("Готовим одноразовый код…");
        ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_contacts_exportContactToken(), (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (!alive) return;
            if (res instanceof TLRPC.TL_exportedContactToken) {
                final TLRPC.TL_exportedContactToken t = (TLRPC.TL_exportedContactToken) res;
                currentUrl = t.url;
                final long ttl = Math.max(15_000L, (t.expires - ConnectionsManager.getInstance(account).getCurrentTime()) * 1000L);
                if (canShare) {
                    PrizmaNfcService.arm(tokenOf(t.url), SystemClock.elapsedRealtime() + ttl, this::onCodeSent);
                }
                AndroidUtilities.runOnUIThread(rearm, Math.max(10_000L, ttl - 8_000L)); // renew before it expires
                if (showingQr) renderQr();
                if (!received && !importing) updateIdleStatus();
            } else {
                status.setText("Нет связи — код не получен");
                AndroidUtilities.runOnUIThread(rearm, 3000);
            }
        }));
    }

    private static String tokenOf(String url) {
        if (url == null) return "";
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private void updateIdleStatus() {
        if (adapter == null) {
            status.setText("На этом телефоне нет NFC");
        } else if (!adapter.isEnabled()) {
            status.setText("NFC выключен");
        } else if (!canShare) {
            status.setText("Готов принять контакт");
        } else {
            status.setText("Готов к обмену");
        }
        nfcSettings.setVisibility(adapter != null && !adapter.isEnabled() ? View.VISIBLE : View.GONE);
    }

    /** Our code went to the other phone: the light runs, then a new one-time code is armed. */
    private void onCodeSent() {
        if (!alive) return;
        haptic();
        scene.wave(COLOR_OUT);
        if (!received) status.setText("Ваш контакт отправлен");
        AndroidUtilities.runOnUIThread(rearm, 2200);
    }

    // ===================== NFC: card + reader in turns =====================

    /**
     * A phone can't be a reader and a card at the same moment, so each one flips between the two
     * at a random rhythm — when they touch, one is reading while the other is answering.
     */
    private final Runnable duplex = new Runnable() {
        @Override
        public void run() {
            if (!alive || adapter == null) return;
            if (!adapter.isEnabled()) {
                setReader(false);
                updateIdleStatus();
                AndroidUtilities.runOnUIThread(this, 1000); // notice when the user turns NFC on
                return;
            }
            if (ApplicationLoader.mainInterfacePaused) {
                // in the background: stop answering taps until the screen is back
                setReader(false);
                if (!pausedArmed) {
                    pausedArmed = true;
                    PrizmaNfcService.disarm();
                }
                AndroidUtilities.runOnUIThread(this, 1000);
                return;
            }
            if (pausedArmed) {
                pausedArmed = false;
                requestCode();
            }
            if (nfcSettings.getVisibility() == View.VISIBLE) updateIdleStatus();
            if (received) {
                setReader(false); // got theirs — keep answering so they can get ours
                AndroidUtilities.runOnUIThread(this, 1000);
                return;
            }
            if (reading) {
                AndroidUtilities.runOnUIThread(this, 200); // don't switch off in the middle of a read
                return;
            }
            boolean next = !canShare || !readerOn;
            setReader(next);
            AndroidUtilities.runOnUIThread(this, next ? 520 + rnd.nextInt(380) : 380 + rnd.nextInt(320));
        }
    };

    private void setReader(boolean on) {
        if (adapter == null || readerOn == on) return;
        readerOn = on;
        try {
            if (on) {
                Bundle extras = new Bundle();
                extras.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
                adapter.enableReaderMode(activity, this::onTag,
                        NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                                | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                        extras);
            } else {
                adapter.disableReaderMode(activity);
            }
        } catch (Throwable ignore) {
            readerOn = false;
        }
    }

    /** Binder thread: ask the other phone for its one-time code. */
    private void onTag(Tag tag) {
        IsoDep iso = IsoDep.get(tag);
        if (iso == null) return;
        reading = true;
        try {
            iso.connect();
            iso.setTimeout(2000);
            byte[] r = iso.transceive(PrizmaNfcService.selectApdu());
            if (r != null && r.length > 2 && r[r.length - 2] == (byte) 0x90 && r[r.length - 1] == 0x00) {
                String text = new String(r, 0, r.length - 2, StandardCharsets.UTF_8);
                if (text.startsWith(PrizmaNfcService.PREFIX)) {
                    final String token = text.substring(PrizmaNfcService.PREFIX.length());
                    AndroidUtilities.runOnUIThread(() -> onCodeRead(token));
                }
            }
        } catch (Throwable ignore) {
        } finally {
            try {
                iso.close();
            } catch (Throwable ignore) {
            }
            reading = false;
        }
    }

    private void onCodeRead(String token) {
        if (!alive || received || importing || token.isEmpty()) return;
        importing = true;
        haptic();
        scene.wave(COLOR_IN);
        status.setText("Получаем контакт…");
        TLRPC.TL_contacts_importContactToken req = new TLRPC.TL_contacts_importContactToken();
        req.token = token;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            importing = false;
            if (!alive) return;
            if (res instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) res;
                MessagesController.getInstance(account).putUser(user, false);
                received = true;
                org.telegram.messenger.PrizmaSocial.inc(org.telegram.messenger.PrizmaSocial.C_NFC);
                showCard(user);
            } else {
                status.setText("Код уже использован — коснитесь ещё раз");
            }
        }));
    }

    private void haptic() {
        try {
            scene.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignore) {
        }
    }

    // ===================== result card =====================

    private void showCard(TLRPC.User user) {
        final Context ctx = getContext();
        setQrVisible(false);
        card.removeAllViews();

        BackupImageView their = new BackupImageView(ctx);
        their.setRoundRadius(AndroidUtilities.dp(44));
        their.setForUserOrChat(user, new AvatarDrawable(user));
        card.addView(their, LayoutHelper.createLinear(88, 88, Gravity.CENTER_HORIZONTAL));

        TextView name = new TextView(ctx);
        name.setText(UserObject.getUserName(user));
        name.setTextColor(0xffffffff);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        name.setTypeface(AndroidUtilities.bold());
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        card.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        String uname = UserObject.getPublicUsername(user);
        TextView sub = new TextView(ctx);
        sub.setText(uname != null ? "@" + uname : "Пользователь Telegram");
        sub.setTextColor(0x99ffffff);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sub.setGravity(Gravity.CENTER);
        card.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 16));

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView write = cardButton(ctx, "Написать", true);
        write.setOnClickListener(v -> open(user.id, true));
        TextView profile = cardButton(ctx, "Профиль", false);
        profile.setOnClickListener(v -> open(user.id, false));
        buttons.addView(write, LayoutHelper.createLinear(0, 46, 1f, 0, 0, 5, 0));
        buttons.addView(profile, LayoutHelper.createLinear(0, 46, 1f, 5, 0, 0, 0));
        card.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // our own avatar steps back, their card rises in
        centerBox.animate().alpha(0f).scaleX(0.86f).scaleY(0.86f).setDuration(260).start();
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setTranslationY(AndroidUtilities.dp(40));
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(520)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        hint.setText("Ваш контакт тоже уходит на другой телефон, пока открыт этот экран");
        status.setText("Контакт получен");
        qrLink.setVisibility(View.GONE);
    }

    private TextView cardButton(Context ctx, String text, boolean primary) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        t.setTextColor(primary ? 0xff111114 : 0xffffffff);
        PrizmaGlass.GlassDrawable bg = new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(23));
        if (primary) bg.setGlassTint(0xf2ffffff);
        t.setBackground(bg);
        PrizmaGlass.pressable(t);
        return t;
    }

    private void open(long userId, boolean chat) {
        dismiss();
        BaseFragment last = LaunchActivity.getLastFragment();
        if (last == null) return;
        Bundle args = new Bundle();
        args.putLong("user_id", userId);
        last.presentFragment(chat ? new ChatActivity(args) : new ProfileActivity(args));
    }

    // ===================== QR fallback =====================

    private void setQrVisible(boolean visible) {
        showingQr = visible;
        qrLink.setText(visible ? "Скрыть QR-код" : "Показать QR-код");
        if (visible) renderQr();
        View in = visible ? qrView : avatar, out = visible ? avatar : qrView;
        out.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(160).withEndAction(() -> out.setVisibility(View.GONE)).start();
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setScaleX(0.9f);
        in.setScaleY(0.9f);
        in.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        scene.setRingsVisible(!visible);
    }

    private void renderQr() {
        if (currentUrl == null) return;
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            Bitmap bmp = new TelegramQRCodeWriter().encode(currentUrl, 600, 600, hints, null);
            qrView.setImageBitmap(bmp);
        } catch (Throwable ignore) {
        }
    }

    // ===================== the light =====================

    /** Black stage: a soft glow, slow rings around the avatar, and the wave that runs on a tap. */
    private class Scene extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix waveMatrix = new Matrix();
        private final boolean fancy = PrizmaConfig.fancyEffects();
        private int glowForH = -1;
        private long waveStart;
        private int waveColor;
        private boolean ringsVisible = true;
        private float flash;
        private long lastTime;

        Scene(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(AndroidUtilities.dpf2(1.3f));
        }

        void setRingsVisible(boolean v) {
            ringsVisible = v;
            invalidate();
        }

        void wave(int color) {
            waveColor = color;
            waveStart = SystemClock.elapsedRealtime();
            flash = 1f;
            int h = Math.max(1, getHeight());
            // a soft band of light: transparent → colour → white core → colour → transparent
            wavePaint.setShader(new LinearGradient(0, 0, 0, h * 0.42f,
                    new int[]{0x00ffffff, (color & 0x00ffffff) | 0x70000000, 0xb3ffffff, (color & 0x00ffffff) | 0x70000000, 0x00ffffff},
                    new float[]{0f, 0.3f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, 0, 0, AndroidUtilities.dp(90),
                    new int[]{0xccffffff, (color & 0x00ffffff) | 0x66000000, color & 0x00ffffff},
                    new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            invalidate();
        }

        private float centerY() {
            if (centerBox == null) return getHeight() / 2f;
            return centerBox.getTop() + centerBox.getHeight() / 2f + ((View) centerBox.getParent()).getTop();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            canvas.drawColor(0xff060609);
            long now = SystemClock.elapsedRealtime();
            float dt = lastTime == 0 ? 16 : Math.min(40, now - lastTime);
            lastTime = now;
            final float cx = w / 2f, cy = centerY();

            if (glowForH != h) {
                glowForH = h;
                glow.setShader(new RadialGradient(cx, cy, Math.max(1, w * 0.75f), new int[]{0x26405cff, 0x0a7a3cff, 0x00000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, glow);

            boolean animating = false;

            // rings: two slow ripples leaving the avatar (one still ring on weak devices / Lite)
            if (ringsVisible) {
                float base = AndroidUtilities.dp(66);
                if (fancy) {
                    float maxR = Math.min(w, h) * 0.5f;
                    for (int i = 0; i < 2; i++) {
                        float t = ((now % 2800L) / 2800f + i * 0.5f) % 1f;
                        float r = base + (maxR - base) * t;
                        float a = (1f - t) * (1f - t);
                        ring.setColor(0xffffff);
                        ring.setAlpha((int) (90 * a));
                        canvas.drawCircle(cx, cy, r, ring);
                    }
                    animating = true;
                }
                ring.setColor(0xffffff);
                ring.setAlpha((int) (70 + 150 * flash));
                if (flash > 0.01f) {
                    ring.setColor((waveColor & 0x00ffffff) | 0xff000000);
                    ring.setAlpha((int) (255 * flash));
                }
                canvas.drawCircle(cx, cy, base, ring);
            }
            if (flash > 0.01f) {
                flash = Math.max(0f, flash - dt / 900f);
                animating = true;
            }

            // the wave: enters at the top edge (where the phones touch) and flows down the screen
            if (waveStart != 0) {
                float p = (now - waveStart) / 1100f;
                if (p >= 1f) {
                    waveStart = 0;
                } else {
                    float e = CubicBezierInterpolator.EASE_OUT.getInterpolation(p);
                    float bandH = h * 0.42f;
                    float y = -bandH + e * (h + bandH);
                    int alpha = (int) (255 * (p < 0.75f ? 1f : (1f - p) / 0.25f));
                    waveMatrix.setTranslate(0, y);
                    wavePaint.getShader().setLocalMatrix(waveMatrix);
                    wavePaint.setAlpha(alpha);
                    canvas.drawRect(0, Math.max(0, y), w, Math.min(h, y + bandH), wavePaint);
                    // the contact point keeps glowing for a moment at the top edge
                    float edgeA = p < 0.15f ? p / 0.15f : Math.max(0f, 1f - (p - 0.15f) / 0.5f);
                    edge.setAlpha((int) (255 * edgeA));
                    canvas.drawRect(0, 0, w, AndroidUtilities.dp(90), edge);
                    animating = true;
                }
            }
            if (animating) org.telegram.messenger.PrizmaConfig.nextDecorFrame(this);
        }
    }
}
