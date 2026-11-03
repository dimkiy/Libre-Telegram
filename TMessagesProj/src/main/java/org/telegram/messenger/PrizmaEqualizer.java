package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;

public class PrizmaEqualizer {
    private static volatile PrizmaEqualizer instance;

    public static PrizmaEqualizer getInstance() {
        if (instance == null) {
            synchronized (PrizmaEqualizer.class) {
                if (instance == null) {
                    instance = new PrizmaEqualizer();
                }
            }
        }
        return instance;
    }

    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;
    private int sessionId = -1;

    private boolean enabled;
    private short numBands;
    private short minLevel;
    private short maxLevel;
    private short[] bandLevels;
    private int bassStrength;
    private int virtStrength;
    private boolean prefsLoaded;

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_eq", Context.MODE_PRIVATE);
    }

    private void loadPrefs() {
        if (prefsLoaded) {
            return;
        }
        SharedPreferences p = prefs();
        enabled = p.getBoolean("enabled", false);
        bassStrength = p.getInt("bass", 0);
        virtStrength = p.getInt("virt", 0);
        prefsLoaded = true;
    }

    public void attach(int newSessionId) {
        loadPrefs();
        if (newSessionId == 0 || newSessionId == -1) {
            return;
        }
        try {
            if (equalizer != null && sessionId == newSessionId) {
                return;
            }
            release();
            sessionId = newSessionId;
            equalizer = new Equalizer(1000, sessionId);
            numBands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            minLevel = range[0];
            maxLevel = range[1];
            bandLevels = new short[numBands];
            SharedPreferences p = prefs();
            for (short i = 0; i < numBands; i++) {
                bandLevels[i] = (short) p.getInt("band_" + i, 0);
            }
            try {
                bassBoost = new BassBoost(1000, sessionId);
            } catch (Exception e) {
                bassBoost = null;
            }
            try {
                virtualizer = new Virtualizer(1000, sessionId);
            } catch (Exception e) {
                virtualizer = null;
            }
            apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void apply() {
        try {
            if (equalizer != null) {
                if (enabled && bandLevels != null) {
                    for (short i = 0; i < numBands && i < bandLevels.length; i++) {
                        try {
                            equalizer.setBandLevel(i, bandLevels[i]);
                        } catch (Throwable ignore) {
                        }
                    }
                }
                if (equalizer.getEnabled() != enabled) {
                    equalizer.setEnabled(enabled);
                }
            }
            if (bassBoost != null) {
                boolean on = enabled && bassStrength > 0;
                if (on) {
                    bassBoost.setStrength((short) Math.min(1000, Math.max(0, bassStrength)));
                }
                if (bassBoost.getEnabled() != on) {
                    bassBoost.setEnabled(on);
                }
            }
            if (virtualizer != null) {
                boolean on = enabled && virtStrength > 0;
                if (on) {
                    virtualizer.setStrength((short) Math.min(1000, Math.max(0, virtStrength)));
                }
                if (virtualizer.getEnabled() != on) {
                    virtualizer.setEnabled(on);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public boolean isReady() {
        return equalizer != null && numBands > 0;
    }

    public boolean isEnabled() {
        loadPrefs();
        return enabled;
    }

    public void setEnabled(boolean value) {
        loadPrefs();
        enabled = value;
        prefs().edit().putBoolean("enabled", value).apply();
        apply();
    }

    public int getNumberOfBands() {
        return numBands;
    }

    public short getMinLevel() {
        return minLevel;
    }

    public short getMaxLevel() {
        return maxLevel;
    }

    public short getBandLevel(int band) {
        if (bandLevels != null && band >= 0 && band < bandLevels.length) {
            return bandLevels[band];
        }
        return 0;
    }

    public int getCenterFreq(int band) {
        try {
            if (equalizer != null) {
                return equalizer.getCenterFreq((short) band);
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    public void setBandLevel(int band, short level) {
        if (bandLevels == null || band < 0 || band >= bandLevels.length) {
            return;
        }
        bandLevels[band] = level;
        prefs().edit().putInt("band_" + band, level).apply();
        try {
            if (equalizer != null && enabled) {
                equalizer.setBandLevel((short) band, level);
            }
        } catch (Throwable ignore) {
        }
    }

    public int getBassStrength() {
        loadPrefs();
        return bassStrength;
    }

    public void setBassStrength(int value) {
        loadPrefs();
        bassStrength = value;
        prefs().edit().putInt("bass", value).apply();
        apply();
    }

    public int getVirtStrength() {
        loadPrefs();
        return virtStrength;
    }

    public void setVirtStrength(int value) {
        loadPrefs();
        virtStrength = value;
        prefs().edit().putInt("virt", value).apply();
        apply();
    }

    public void resetAll() {
        SharedPreferences.Editor editor = prefs().edit();
        if (bandLevels != null) {
            for (int i = 0; i < bandLevels.length; i++) {
                bandLevels[i] = 0;
                editor.putInt("band_" + i, 0);
            }
        }
        bassStrength = 0;
        virtStrength = 0;
        editor.putInt("bass", 0);
        editor.putInt("virt", 0);
        editor.apply();
        apply();
    }

    public short getNumberOfPresets() {
        try {
            if (equalizer != null) {
                return equalizer.getNumberOfPresets();
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    public String getPresetName(short preset) {
        try {
            if (equalizer != null) {
                return equalizer.getPresetName(preset);
            }
        } catch (Throwable ignore) {
        }
        return "";
    }

    public void usePreset(short preset) {
        try {
            if (equalizer != null) {
                equalizer.usePreset(preset);
                SharedPreferences.Editor editor = prefs().edit();
                for (short i = 0; i < numBands; i++) {
                    bandLevels[i] = equalizer.getBandLevel(i);
                    editor.putInt("band_" + i, bandLevels[i]);
                }
                editor.apply();
            }
        } catch (Throwable ignore) {
        }
    }

    private org.json.JSONArray customArray() {
        try {
            String s = prefs().getString("custom_presets", "");
            if (s != null && s.length() > 0) {
                return new org.json.JSONArray(s);
            }
        } catch (Throwable ignore) {
        }
        return new org.json.JSONArray();
    }

    private void saveCustomArray(org.json.JSONArray arr) {
        prefs().edit().putString("custom_presets", arr.toString()).apply();
    }

    public int getCustomCount() {
        return customArray().length();
    }

    public String getCustomName(int i) {
        try {
            return customArray().getJSONObject(i).optString("name", "Пресет");
        } catch (Throwable ignore) {
            return "Пресет";
        }
    }

    public void saveCustomPreset(String name) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("name", name == null || name.trim().isEmpty() ? "Мой пресет" : name.trim());
            org.json.JSONArray bands = new org.json.JSONArray();
            if (bandLevels != null) {
                for (short b : bandLevels) {
                    bands.put((int) b);
                }
            }
            obj.put("bands", bands);
            obj.put("bass", bassStrength);
            obj.put("virt", virtStrength);
            org.json.JSONArray arr = customArray();
            arr.put(obj);
            saveCustomArray(arr);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void applyCustomPreset(int i) {
        try {
            org.json.JSONObject obj = customArray().getJSONObject(i);
            org.json.JSONArray bands = obj.optJSONArray("bands");
            SharedPreferences.Editor editor = prefs().edit();
            if (bands != null && bandLevels != null) {
                for (int b = 0; b < bandLevels.length && b < bands.length(); b++) {
                    bandLevels[b] = (short) bands.optInt(b, 0);
                    editor.putInt("band_" + b, bandLevels[b]);
                }
            }
            bassStrength = obj.optInt("bass", 0);
            virtStrength = obj.optInt("virt", 0);
            editor.putInt("bass", bassStrength);
            editor.putInt("virt", virtStrength);
            editor.apply();
            apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void deleteCustomPreset(int i) {
        try {
            org.json.JSONArray arr = customArray();
            org.json.JSONArray out = new org.json.JSONArray();
            for (int k = 0; k < arr.length(); k++) {
                if (k != i) {
                    out.put(arr.get(k));
                }
            }
            saveCustomArray(out);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void release() {
        try {
            if (equalizer != null) {
                equalizer.release();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (bassBoost != null) {
                bassBoost.release();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (virtualizer != null) {
                virtualizer.release();
            }
        } catch (Throwable ignore) {
        }
        equalizer = null;
        bassBoost = null;
        virtualizer = null;
    }
}
