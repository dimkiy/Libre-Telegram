package org.telegram.ui.Components;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Prizma: lightweight bridge that forwards the player's audio waveform
 * (already computed by Telegram's AudioVisualizerRenderersFactory) to UI views.
 * Supports multiple listeners so the seek bar and a background visualizer can react together.
 */
public class PrizmaVisualizerData {

    public interface Listener {
        void onWaveform(boolean playing, boolean animate, float[] values);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Adds a listener (does not replace existing ones). */
    public static void setListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public static void clearListener(Listener l) {
        if (l != null) {
            listeners.remove(l);
        }
    }

    public static boolean hasListener() {
        return !listeners.isEmpty();
    }

    public static void dispatch(boolean playing, boolean animate, float[] values) {
        for (Listener l : listeners) {
            try {
                l.onWaveform(playing, animate, values);
            } catch (Throwable ignore) {
            }
        }
    }
}
