package org.telegram.ui.Components;

import java.util.concurrent.CopyOnWriteArrayList;

public class PrizmaVisualizerData {
    public interface Listener {
        void onWaveform(boolean playing, boolean animate, float[] values);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

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
