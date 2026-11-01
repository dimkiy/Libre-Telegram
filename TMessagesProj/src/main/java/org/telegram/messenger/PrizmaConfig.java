package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Map;

/**
 * Prizma: feature flags (stealth / privacy).
 */
public class PrizmaConfig {

    // Many of these getters are called from onDraw() of every chat / dialog cell, so they must not
    // touch SharedPreferences each time (getSharedPreferences lookup + lock + map lookup per call was
    // a real source of jank). All values live in an in-memory snapshot that is rebuilt only after a
    // change — through our setters or anyone else writing to "prizma_cfg" (change listener).
    private static volatile SharedPreferences sPrefs;
    private static volatile Map<String, ?> snapshot;
    private static volatile int version;
    private static final SharedPreferences.OnSharedPreferenceChangeListener changeListener = (p, key) -> invalidate();

    private static SharedPreferences prefs() {
        SharedPreferences p = sPrefs;
        if (p == null) {
            synchronized (PrizmaConfig.class) {
                p = sPrefs;
                if (p == null) {
                    p = ApplicationLoader.applicationContext.getSharedPreferences("prizma_cfg", Context.MODE_PRIVATE);
                    p.registerOnSharedPreferenceChangeListener(changeListener);
                    sPrefs = p;
                }
            }
        }
        return p;
    }

    private static void invalidate() {
        snapshot = null;
        version++;
    }

    /** Bumped on every settings change — lets callers cache values derived from the config. */
    public static int version() {
        return version;
    }

    private static Map<String, ?> values() {
        Map<String, ?> s = snapshot;
        if (s == null) {
            s = prefs().getAll(); // a copy, never mutated afterwards → safe to read from any thread
            snapshot = s;
        }
        return s;
    }

    private static boolean getBool(String key, boolean def) {
        Object v = values().get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private static int getInt(String key, int def) {
        Object v = values().get(key);
        return v instanceof Integer ? (Integer) v : def;
    }

    private static long getLong(String key, long def) {
        Object v = values().get(key);
        return v instanceof Long ? (Long) v : def;
    }

    private static float getFloat(String key, float def) {
        Object v = values().get(key);
        return v instanceof Float ? (Float) v : def;
    }

    private static String getString(String key, String def) {
        Object v = values().get(key);
        return v instanceof String ? (String) v : def;
    }

    private static SharedPreferences.Editor edit() {
        return prefs().edit();
    }

    /** Pinned order of the own profile gifts (keys from PrizmaGifts.pinKey, comma-separated). */
    public static String showcasePins() {
        return getString("showcase_pins", "");
    }

    public static void setShowcasePins(String csv) {
        done(edit().putString("showcase_pins", csv == null ? "" : csv));
    }

    private static void done(SharedPreferences.Editor e) {
        e.apply();
        invalidate(); // apply() updates memory synchronously, so the next read sees the new value
    }

    private static long fancyCheckedAt;
    private static boolean fancyCached = true;

    /**
     * Heavy decorative animations (iridescent gradients, glow rings, list cascades, beat pulses) are
     * allowed: only on high-end phones, never in Libre Lite mode or while Telegram's battery saver is on.
     * Cached for a second — cheap enough to call from onDraw().
     */
    public static boolean fancyEffects() {
        long now = SystemClock.elapsedRealtime();
        if (now - fancyCheckedAt > 1000) {
            fancyCheckedAt = now;
            boolean v;
            try {
                // only on HIGH-class phones: mid-range ones (most Samsung A, many Redmi) get the
                // static version — these loops redraw big areas every frame
                v = !liteMode() && SharedConfig.deviceIsHigh() && !LiteMode.isPowerSaverApplied();
            } catch (Throwable e) {
                v = !liteMode();
            }
            fancyCached = v;
        }
        return fancyCached;
    }

    /**
     * Next frame of a purely decorative loop (gradients, glow, rings, beat bars). On 90/120 Hz
     * screens these don't need more than ~60 fps — half the redraws.
     */
    public static void nextDecorFrame(android.view.View v) {
        if (AndroidUtilities.screenRefreshRate > 75f) {
            // aim at the next tick of a 60 Hz grid; the frame lands on the first vsync after it.
            // A fixed delay can't do 60 on 90 Hz (it gives 90 or 45), the grid alternates 1-2 vsyncs.
            long now = SystemClock.uptimeMillis();
            long tick = (now * 60 / 1000 + 1) * 1000 / 60;
            v.postInvalidateDelayed(Math.max(1, tick - now));
        } else {
            v.postInvalidateOnAnimation();
        }
    }

    public static boolean hideOnline() {
        return getBool("hide_online", false);
    }

    public static void setHideOnline(boolean v) {
        done(edit().putBoolean("hide_online", v));
    }

    public static boolean hideTyping() {
        return getBool("hide_typing", false);
    }

    public static void setHideTyping(boolean v) {
        done(edit().putBoolean("hide_typing", v));
    }

    public static boolean allowSaveRestricted() {
        return getBool("allow_save_restricted", false);
    }

    public static void setAllowSaveRestricted(boolean v) {
        done(edit().putBoolean("allow_save_restricted", v));
    }

    public static boolean antiDelete() {
        return getBool("anti_delete", false);
    }

    public static void setAntiDelete(boolean v) {
        done(edit().putBoolean("anti_delete", v));
    }

    public static boolean iridescentProfile() {
        return getBool("iridescent_profile", false);
    }

    public static void setIridescentProfile(boolean v) {
        done(edit().putBoolean("iridescent_profile", v));
    }

    public static String bannerPath() {
        return getString("banner_path", "");
    }

    public static void setBannerPath(String v) {
        done(edit().putString("banner_path", v == null ? "" : v));
    }

    public static boolean bannerIsVideo() {
        return getBool("banner_is_video", false);
    }

    public static void setBannerIsVideo(boolean v) {
        done(edit().putBoolean("banner_is_video", v));
    }

    public static boolean liteMode() {
        return getBool("lite_mode", false);
    }

    public static void setLiteMode(boolean v) {
        done(edit().putBoolean("lite_mode", v));
    }

    public static int litePrevFlags() {
        return getInt("lite_prev_flags", -1);
    }

    public static void setLitePrevFlags(int v) {
        done(edit().putInt("lite_prev_flags", v));
    }

    /** Hide names + avatars of chats inside the archive (toggle shown in the archive window). */
    public static boolean archiveHideNames() {
        return getBool("archive_hide_names", false);
    }

    public static void setArchiveHideNames(boolean v) {
        done(edit().putBoolean("archive_hide_names", v));
    }

    /** Archive lock: require a PIN to open the chat archive. */
    public static boolean archiveLock() {
        return getBool("archive_lock", false);
    }

    public static void setArchiveLock(boolean v) {
        done(edit().putBoolean("archive_lock", v));
    }

    public static String archivePinHash() {
        return getString("archive_pin", "");
    }

    public static void setArchivePinHash(String h) {
        done(edit().putString("archive_pin", h == null ? "" : h));
    }

    public static boolean hasArchivePin() {
        return archivePinHash().length() > 0;
    }

    /** Keep the account always shown as online. */
    public static boolean alwaysOnline() {
        return getBool("always_online", false);
    }

    public static void setAlwaysOnline(boolean v) {
        done(edit().putBoolean("always_online", v));
    }

    /** «Уведомления из MAX»: forward MAX's notifications to the Libre bot (PrizmaMaxListener). */
    public static boolean maxForward() {
        return getBool("max_forward", false);
    }

    public static void setMaxForward(boolean v) {
        done(edit().putBoolean("max_forward", v));
    }

    /** Ask «Позвонить …?» before a Telegram call starts — a stray tap on the call button no longer rings. */
    public static boolean confirmCalls() {
        return getBool("confirm_calls", true);
    }

    public static void setConfirmCalls(boolean v) {
        done(edit().putBoolean("confirm_calls", v));
    }

    /** Do Not Disturb — locally suppress all Telegram notifications. */
    public static boolean dnd() {
        return getBool("dnd", false);
    }

    public static void setDnd(boolean v) {
        done(edit().putBoolean("dnd", v));
    }

    /** CSV of "dialogId:colorIndex" — user color tags for chats. */
    public static String dialogColors() {
        return getString("dialog_colors", "");
    }

    public static void setDialogColors(String csv) {
        done(edit().putString("dialog_colors", csv == null ? "" : csv));
    }

    /** Path to a user-chosen custom font file (.ttf/.otf); empty = default fonts. */
    public static String customFontPath() {
        return getString("custom_font_path", "");
    }

    public static void setCustomFontPath(String v) {
        done(edit().putString("custom_font_path", v == null ? "" : v));
    }

    /** Saved drag offset (px) of the floating now-playing island. */
    public static float islandOffX() {
        return getFloat("island_off_x", 0f);
    }

    public static float islandOffY() {
        return getFloat("island_off_y", 0f);
    }

    public static void setIslandOffset(float x, float y) {
        done(edit().putFloat("island_off_x", x).putFloat("island_off_y", y));
    }

    /** Seek-bar style in the full player: 0 = default, 1 = glowing, 2 = iridescent. */
    public static int seekStyle() {
        return getInt("seek_style", 0);
    }

    public static void setSeekStyle(int v) {
        done(edit().putInt("seek_style", v));
    }

    /** Full-player background: 0 = blurred cover, 1 = sharp cover, 2 = dark theme, 3 = accent gradient. */
    public static int playerBg() {
        return getInt("player_bg", 0);
    }

    public static void setPlayerBg(int v) {
        done(edit().putInt("player_bg", v));
    }

    /** User-customized player layout: per-element drag offset (px) as {x, y}. */
    public static float[] playerOff(String key) {
        String s = getString("player_off_" + key, "");
        if (s != null && s.contains(":")) {
            try {
                String[] p = s.split(":");
                return new float[]{Float.parseFloat(p[0]), Float.parseFloat(p[1])};
            } catch (Throwable ignore) {}
        }
        return new float[]{0f, 0f};
    }

    public static void setPlayerOff(String key, float x, float y) {
        done(edit().putString("player_off_" + key, x + ":" + y));
    }

    /** Translucent "glass" message bubbles (wallpaper shows through). */
    public static boolean glassBubbles() {
        return getBool("glass_bubbles", false);
    }

    public static void setGlassBubbles(boolean v) {
        done(edit().putBoolean("glass_bubbles", v));
    }

    /** Sticker size scale in chats (0.5 … 1.0; 1.0 = default Telegram size). */
    public static float stickerScale() {
        float v = getFloat("sticker_scale", 1f);
        if (v < 0.5f) v = 0.5f;
        if (v > 1f) v = 1f;
        return v;
    }

    public static void setStickerScale(float v) {
        done(edit().putFloat("sticker_scale", v));
    }

    /** 3D parallax tilt of the album cover in the full player (reacts to device tilt). */
    public static boolean coverParallax() {
        return getBool("cover_parallax", true);
    }

    public static void setCoverParallax(boolean v) {
        done(edit().putBoolean("cover_parallax", v));
    }

    /** «Новый плеер (бета)»: the card-style player instead of the full-screen one. */
    public static boolean betaPlayer() {
        return getBool("beta_player", false);
    }

    public static void setBetaPlayer(boolean v) {
        done(edit().putBoolean("beta_player", v));
    }

    /** Cover size multiplier in the full player (0.6 … 1.3). */
    public static float playerCoverScale() {
        return getFloat("player_cover_scale", 1f);
    }

    public static void setPlayerCoverScale(float v) {
        done(edit().putFloat("player_cover_scale", v));
    }

    /** Element dragging in the player was removed — forget offsets saved by older versions. */
    public static void clearPlayerOffsets() {
        if (values().containsKey("player_off_controls") || values().containsKey("player_off_queue")) {
            done(edit().remove("player_off_controls").remove("player_off_queue"));
        }
    }

    public static void resetPlayerLayout() {
        done(edit()
                .remove("player_off_controls")
                .remove("player_off_queue")
                .remove("player_cover_scale"));
    }

    public static boolean fakePhone() {
        return getBool("fake_phone", false);
    }

    public static void setFakePhone(boolean v) {
        done(edit().putBoolean("fake_phone", v));
    }

    /** Stable anonymous +888 number for visual masking (generated once, or chosen by the user). */
    public static String fakePhoneNumber() {
        String s = getString("fake_phone_num", "");
        if (s == null || s.isEmpty()) {
            s = randomFakePhoneNumber();
            done(edit().putString("fake_phone_num", s));
        }
        return s;
    }

    public static void setFakePhoneNumber(String s) {
        done(edit().putString("fake_phone_num", s == null ? "" : s));
    }

    /** Generates a fresh random +888 number (not persisted). */
    public static String randomFakePhoneNumber() {
        java.util.Random r = new java.util.Random();
        return "+888 " + (1000 + r.nextInt(9000)) + " " + (1000 + r.nextInt(9000));
    }

    /** Returns the phone string to DISPLAY: fake +888 when enabled, otherwise the real formatted number. */
    public static String displayPhone(String realDigits) {
        if (fakePhone()) {
            return fakePhoneNumber();
        }
        try {
            return org.telegram.PhoneFormat.PhoneFormat.getInstance().format("+" + realDigits);
        } catch (Throwable e) {
            return realDigits == null ? "" : ("+" + realDigits);
        }
    }

    /** Scale for big "jumbo" emoji in messages (0.4 … 1.0; 1.0 = default Telegram size). */
    public static float emojiScale() {
        float v = getFloat("emoji_scale", 1f);
        if (v < 0.4f) v = 0.4f;
        if (v > 1f) v = 1f;
        return v;
    }

    public static void setEmojiScale(float v) {
        done(edit().putFloat("emoji_scale", v));
    }

    public static boolean hideAds() {
        return getBool("hide_ads", true);
    }

    public static void setHideAds(boolean v) {
        done(edit().putBoolean("hide_ads", v));
    }

    /** Always forward without the "forwarded from" attribution (anonymous forwarding). */
    public static boolean anonForward() {
        return getBool("anon_forward", false);
    }

    public static void setAnonForward(boolean v) {
        done(edit().putBoolean("anon_forward", v));
    }

    /** Strip EXIF/GPS metadata from outgoing photos and videos. */
    public static boolean stripExif() {
        return getBool("strip_exif", false);
    }

    public static void setStripExif(boolean v) {
        done(edit().putBoolean("strip_exif", v));
    }

    public static boolean stealthStories() {
        return getBool("stealth_stories", false);
    }

    public static void setStealthStories(boolean v) {
        done(edit().putBoolean("stealth_stories", v));
    }

    public static boolean hideCallIp() {
        return getBool("hide_call_ip", false);
    }

    public static void setHideCallIp(boolean v) {
        done(edit().putBoolean("hide_call_ip", v));
    }

    /** CSV of star-gift ids chosen for the profile showcase (visual only). */
    public static String showcaseGifts() {
        return getString("showcase_gifts", "");
    }

    public static void setShowcaseGifts(String csv) {
        done(edit().putString("showcase_gifts", csv == null ? "" : csv));
    }

    /** CSV of showcase gift ids the user "upgraded" to a fake NFT look (client-side only). */
    public static String pseudoNftIds() {
        return getString("pseudo_nft_ids", "");
    }

    public static void setPseudoNftIds(String csv) {
        done(edit().putString("pseudo_nft_ids", csv == null ? "" : csv));
    }

    /** Monotonic counter for unique showcase-gift instance ids (negative, so they never collide with real saved_id). */
    public static long nftNextInst() {
        return getLong("nft_next_inst", -1L);
    }

    public static void setNftNextInst(long v) {
        done(edit().putLong("nft_next_inst", v));
    }

    /** CSV of "giftId:styleIndex" — chosen NFT backdrop style per upgraded gift. */
    public static String nftStyles() {
        return getString("nft_styles", "");
    }

    public static void setNftStyles(String csv) {
        done(edit().putString("nft_styles", csv == null ? "" : csv));
    }

    /** Base64 of the real chosen upgrade attributes (model+backdrop+pattern) per gift. */
    public static String nftAttr(long id) {
        return getString("nft_attr_" + id, "");
    }

    public static void setNftAttr(long id, String b64) {
        done(edit().putString("nft_attr_" + id, b64 == null ? "" : b64));
    }

    /** "num/total" collectible number drawn for an upgraded gift. */
    public static String nftNum(long id) {
        return getString("nft_num_" + id, "");
    }

    public static void setNftNum(long id, String v) {
        done(edit().putString("nft_num_" + id, v == null ? "" : v));
    }

    /** A showcase NFT that came out of crafting (like in Telegram, it can't be crafted again). */
    public static boolean nftCrafted(long id) {
        return getBool("nft_crafted_" + id, false);
    }

    public static void setNftCrafted(long id, boolean v) {
        if (v) {
            done(edit().putBoolean("nft_crafted_" + id, true));
        } else if (values().containsKey("nft_crafted_" + id)) {
            done(edit().remove("nft_crafted_" + id));
        }
    }

    /** "Erase traces": which kinds (PrizmaTraces bit mask) and whether to do it automatically. */
    public static int tracesMask() {
        return getInt("traces_mask", 1 | 2 | 8 | 16);
    }

    public static void setTracesMask(int v) {
        done(edit().putInt("traces_mask", v));
    }

    public static boolean tracesAuto() {
        return getBool("traces_auto", false);
    }

    public static void setTracesAuto(boolean v) {
        done(edit().putBoolean("traces_auto", v));
    }

    /** Music status: while a track plays, the bio shows "🎧 Artist — Title". */
    public static boolean musicStatus() {
        return getBool("music_status", false);
    }

    public static void setMusicStatus(boolean v) {
        done(edit().putBoolean("music_status", v));
    }

    /** The user's real bio, saved while the music status replaces it (null when nothing is replaced). */
    public static String musicStatusOriginalBio(int account) {
        return values().containsKey("music_status_bio_" + account) ? getString("music_status_bio_" + account, "") : null;
    }

    public static void setMusicStatusOriginalBio(int account, String bio) {
        if (bio == null) {
            done(edit().remove("music_status_bio_" + account));
        } else {
            done(edit().putString("music_status_bio_" + account, bio));
        }
    }

    public static boolean invisibleRead() {
        return getBool("invisible_read", false);
    }

    public static void setInvisibleRead(boolean v) {
        done(edit().putBoolean("invisible_read", v));
    }

    public static boolean tapTranslate() {
        return getBool("tap_translate", true);
    }

    public static void setTapTranslate(boolean v) {
        done(edit().putBoolean("tap_translate", v));
    }

    public static boolean iridescentName() {
        return getBool("iridescent_name", false);
    }

    public static void setIridescentName(boolean v) {
        done(edit().putBoolean("iridescent_name", v));
    }

    public static boolean liveWallpaper() {
        return getBool("live_wallpaper", false);
    }

    public static void setLiveWallpaper(boolean v) {
        done(edit().putBoolean("live_wallpaper", v));
    }

    /** custom chat wallpaper (own photo/video) */
    public static String chatWallpaperPath() {
        return getString("chat_wp_path", "");
    }

    public static void setChatWallpaperPath(String v) {
        done(edit().putString("chat_wp_path", v == null ? "" : v));
    }

    public static boolean chatWallpaperIsVideo() {
        return getBool("chat_wp_video", false);
    }

    public static void setChatWallpaperIsVideo(boolean v) {
        done(edit().putBoolean("chat_wp_video", v));
    }

    /** index into PrizmaWallpaper palette list */
    public static int liveWallpaperPalette() {
        return getInt("live_wallpaper_palette", 0);
    }

    public static void setLiveWallpaperPalette(int v) {
        done(edit().putInt("live_wallpaper_palette", v));
    }
}
