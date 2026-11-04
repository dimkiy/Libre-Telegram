package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Map;

public class PrizmaConfig {
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

    public static int version() {
        return version;
    }

    private static Map<String, ?> values() {
        Map<String, ?> s = snapshot;
        if (s == null) {
            s = prefs().getAll();
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

    public static String showcasePins() {
        return getString("showcase_pins", "");
    }

    public static void setShowcasePins(String csv) {
        done(edit().putString("showcase_pins", csv == null ? "" : csv));
    }

    private static void done(SharedPreferences.Editor e) {
        e.apply();
        invalidate();
    }

    private static long fancyCheckedAt;
    private static boolean fancyCached = true;

    public static boolean fancyEffects() {
        long now = SystemClock.elapsedRealtime();
        if (now - fancyCheckedAt > 1000) {
            fancyCheckedAt = now;
            boolean v;
            try {
                v = !liteMode() && SharedConfig.deviceIsHigh() && !LiteMode.isPowerSaverApplied();
            } catch (Throwable e) {
                v = !liteMode();
            }
            fancyCached = v;
        }
        return fancyCached;
    }

    public static boolean beatEffects() {
        try {
            return !liteMode() && !LiteMode.isPowerSaverApplied();
        } catch (Throwable e) {
            return !liteMode();
        }
    }

    public static void nextDecorFrame(android.view.View v) {
        if (AndroidUtilities.screenRefreshRate > 75f) {
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

    public static boolean archiveHideNames() {
        return getBool("archive_hide_names", false);
    }

    public static void setArchiveHideNames(boolean v) {
        done(edit().putBoolean("archive_hide_names", v));
    }

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

    public static boolean alwaysOnline() {
        return getBool("always_online", false);
    }

    public static void setAlwaysOnline(boolean v) {
        done(edit().putBoolean("always_online", v));
    }

    public static boolean maxForward() {
        return getBool("max_forward", false);
    }

    public static void setMaxForward(boolean v) {
        done(edit().putBoolean("max_forward", v));
    }

    public static boolean confirmCalls() {
        return getBool("confirm_calls", true);
    }

    public static void setConfirmCalls(boolean v) {
        done(edit().putBoolean("confirm_calls", v));
    }

    public static boolean showAnonNumber() {
        return getBool("show_anon_number", true);
    }

    public static void setShowAnonNumber(boolean v) {
        done(edit().putBoolean("show_anon_number", v));
    }

    public static boolean dnd() {
        return getBool("dnd", false);
    }

    public static void setDnd(boolean v) {
        done(edit().putBoolean("dnd", v));
    }

    public static String dialogColors() {
        return getString("dialog_colors", "");
    }

    public static void setDialogColors(String csv) {
        done(edit().putString("dialog_colors", csv == null ? "" : csv));
    }

    public static String customFontPath() {
        return getString("custom_font_path", "");
    }

    public static void setCustomFontPath(String v) {
        done(edit().putString("custom_font_path", v == null ? "" : v));
    }

    public static int seekStyle() {
        return getInt("seek_style", 0);
    }

    public static void setSeekStyle(int v) {
        done(edit().putInt("seek_style", v));
    }

    public static int playerBackdrop() {
        return getInt("player_backdrop", 0);
    }

    public static void setPlayerBackdrop(int v) {
        done(edit().putInt("player_backdrop", v));
    }

    public static boolean glassBubbles() {
        return getBool("glass_bubbles", false);
    }

    public static void setGlassBubbles(boolean v) {
        done(edit().putBoolean("glass_bubbles", v));
    }

    public static float stickerScale() {
        float v = getFloat("sticker_scale", 1f);
        if (v < 0.5f) v = 0.5f;
        if (v > 1f) v = 1f;
        return v;
    }

    public static void setStickerScale(float v) {
        done(edit().putFloat("sticker_scale", v));
    }

    public static boolean coverParallax() {
        return getBool("cover_parallax", true);
    }

    public static void setCoverParallax(boolean v) {
        done(edit().putBoolean("cover_parallax", v));
    }

    public static boolean coverColorsBig() {
        return getBool("cover_colors_big", true);
    }

    public static void setCoverColorsBig(boolean v) {
        done(edit().putBoolean("cover_colors_big", v));
    }

    public static boolean coverColorsMini() {
        return getBool("cover_colors_mini", true);
    }

    public static void setCoverColorsMini(boolean v) {
        done(edit().putBoolean("cover_colors_mini", v));
    }

    public static boolean playerBgBeat() {
        return getBool("player_bg_beat", true);
    }

    public static void setPlayerBgBeat(boolean v) {
        done(edit().putBoolean("player_bg_beat", v));
    }

    public static boolean playerSparks() {
        return getBool("player_sparks", false);
    }

    public static void setPlayerSparks(boolean v) {
        done(edit().putBoolean("player_sparks", v));
    }

    public static boolean playerRings() {
        return getBool("player_rings", true);
    }

    public static void setPlayerRings(boolean v) {
        done(edit().putBoolean("player_rings", v));
    }

    public static boolean playerCoverKick() {
        return getBool("player_cover_kick", true);
    }

    public static void setPlayerCoverKick(boolean v) {
        done(edit().putBoolean("player_cover_kick", v));
    }

    public static boolean playerHaptics() {
        return getBool("player_haptics", false);
    }

    public static void setPlayerHaptics(boolean v) {
        done(edit().putBoolean("player_haptics", v));
    }

    public static boolean playerClips() {
        return getBool("player_clips", true);
    }

    public static void setPlayerClips(boolean v) {
        done(edit().putBoolean("player_clips", v));
    }

    public static boolean playerArtistClips() {
        return getBool("player_artist_clips", true);
    }

    public static void setPlayerArtistClips(boolean v) {
        done(edit().putBoolean("player_artist_clips", v));
    }

    public static boolean playerClipsMobile() {
        return getBool("player_clips_mobile", false);
    }

    public static void setPlayerClipsMobile(boolean v) {
        done(edit().putBoolean("player_clips_mobile", v));
    }

    public static boolean lyricsOnline() {
        return getBool("lyrics_online", false);
    }

    public static void setLyricsOnline(boolean v) {
        done(edit().putBoolean("lyrics_online", v));
    }

    public static boolean lyricsAsked() {
        return getBool("lyrics_asked", false);
    }

    public static void setLyricsAsked(boolean v) {
        done(edit().putBoolean("lyrics_asked", v));
    }

    public static int lyricsFill() {
        return getInt("lyrics_fill", 1);
    }

    public static void setLyricsFill(int v) {
        done(edit().putInt("lyrics_fill", v));
    }

    public static boolean dockTabs() {
        return getBool("dock_tabs", true);
    }

    public static void setDockTabs(boolean v) {
        done(edit().putBoolean("dock_tabs", v));
    }

    public static boolean inputCalc() {
        return getBool("input_calc", true);
    }

    public static void setInputCalc(boolean v) {
        done(edit().putBoolean("input_calc", v));
    }

    public static boolean builtInPdf() {
        return getBool("builtin_pdf", true);
    }

    public static void setBuiltInPdf(boolean v) {
        done(edit().putBoolean("builtin_pdf", v));
    }

    public static float playerCoverScale() {
        return getFloat("player_cover_scale", 1f);
    }

    public static void setPlayerCoverScale(float v) {
        done(edit().putFloat("player_cover_scale", v));
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

    public static String randomFakePhoneNumber() {
        java.util.Random r = new java.util.Random();
        return "+888 " + (1000 + r.nextInt(9000)) + " " + (1000 + r.nextInt(9000));
    }

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

    public static boolean anonForward() {
        return getBool("anon_forward", false);
    }

    public static void setAnonForward(boolean v) {
        done(edit().putBoolean("anon_forward", v));
    }

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

    public static String showcaseGifts() {
        return getString("showcase_gifts", "");
    }

    public static void setShowcaseGifts(String csv) {
        done(edit().putString("showcase_gifts", csv == null ? "" : csv));
    }

    public static String pseudoNftIds() {
        return getString("pseudo_nft_ids", "");
    }

    public static void setPseudoNftIds(String csv) {
        done(edit().putString("pseudo_nft_ids", csv == null ? "" : csv));
    }

    public static long nftNextInst() {
        return getLong("nft_next_inst", -1L);
    }

    public static void setNftNextInst(long v) {
        done(edit().putLong("nft_next_inst", v));
    }

    public static String nftStyles() {
        return getString("nft_styles", "");
    }

    public static void setNftStyles(String csv) {
        done(edit().putString("nft_styles", csv == null ? "" : csv));
    }

    public static String nftAttr(long id) {
        return getString("nft_attr_" + id, "");
    }

    public static void setNftAttr(long id, String b64) {
        done(edit().putString("nft_attr_" + id, b64 == null ? "" : b64));
    }

    public static String nftNum(long id) {
        return getString("nft_num_" + id, "");
    }

    public static void setNftNum(long id, String v) {
        done(edit().putString("nft_num_" + id, v == null ? "" : v));
    }

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

    public static boolean musicStatus() {
        return getBool("music_status", false);
    }

    public static void setMusicStatus(boolean v) {
        done(edit().putBoolean("music_status", v));
    }

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

    public static int liveWallpaperPalette() {
        return getInt("live_wallpaper_palette", 0);
    }

    public static void setLiveWallpaperPalette(int v) {
        done(edit().putInt("live_wallpaper_palette", v));
    }
}
