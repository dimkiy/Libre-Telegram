package org.telegram.ui.Components;

import org.telegram.messenger.AndroidUtilities;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PrizmaBeat implements PrizmaVisualizerData.Listener {
    public interface Listener {
        void onBeat(float strength);
    }

    private static final int WINDOW = 44;
    private static final int HOP = 1024;
    public static final int BANDS = 24;
    private static final float CUTOFF_HZ = 170f;
    private static final float THRESHOLD = 1.4f;
    private static final float QUIET = 1e-5f;
    private static final long MIN_GAP_MS = 220;
    private static final long OUTPUT_DELAY_MS = 130;

    private static final PrizmaBeat instance = new PrizmaBeat();

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final float[] history = new float[WINDOW];
    private int head, filled;
    private float lowPass, sum, previous, peak;
    private int samples;
    private long lastBeatAt;
    private boolean subscribed;
    private volatile float level;
    private final float[] window = new float[HOP];
    private final float[] bandPeak = new float[BANDS];
    private final float[] bandValue = new float[BANDS];
    private org.telegram.messenger.FourierTransform.FFT fft;
    private int fftRate;

    private PrizmaBeat() {
    }

    public static void add(Listener l) {
        if (l == null) return;
        instance.listeners.addIfAbsent(l);
        if (!instance.subscribed) {
            instance.subscribed = true;
            PrizmaVisualizerData.setListener(instance);
        }
    }

    public static void remove(Listener l) {
        if (l == null) return;
        instance.listeners.remove(l);
        if (instance.listeners.isEmpty() && instance.subscribed) {
            instance.subscribed = false;
            PrizmaVisualizerData.clearListener(instance);
        }
    }

    public static float level() {
        return instance.level;
    }

    public static float band(int index) {
        return instance.bandValue[index];
    }

    public static void feed(ByteBuffer pcm, int channels, int sampleRate) {
        if (instance.listeners.isEmpty() || channels <= 0 || sampleRate <= 0) return;
        instance.analyze(pcm, channels, sampleRate);
    }

    @Override
    public void onWaveform(boolean playing, boolean animate, float[] values) {
        if (!playing) {
            level = 0f;
            java.util.Arrays.fill(bandValue, 0f);
        }
    }

    private void analyze(ByteBuffer pcm, int channels, int sampleRate) {
        ByteBuffer data = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        float k = (float) (2 * Math.PI * CUTOFF_HZ);
        float alpha = k / (sampleRate + k);
        int frame = channels * 2;
        for (int at = data.position(), end = data.limit() - frame + 1; at < end; at += frame) {
            float mono = 0f;
            for (int c = 0; c < channels; c++) {
                mono += data.getShort(at + c * 2) / 32768f;
            }
            lowPass += (mono / channels - lowPass) * alpha;
            sum += lowPass * lowPass;
            window[samples] = mono / channels;
            if (++samples >= HOP) {
                spectrum(sampleRate);
                hop(sum / samples);
                sum = 0f;
                samples = 0;
            }
        }
    }

    private void spectrum(int sampleRate) {
        if (fft == null || fftRate != sampleRate) {
            fft = new org.telegram.messenger.FourierTransform.FFT(HOP, sampleRate);
            fftRate = sampleRate;
        }
        fft.forward(window);
        float[] re = fft.getSpectrumReal(), im = fft.getSpectrumImaginary();
        float hz = sampleRate / (float) HOP;
        for (int b = 0; b < BANDS; b++) {
            float low = (float) (45 * Math.pow(220f, b / (float) BANDS));
            float high = (float) (45 * Math.pow(220f, (b + 1) / (float) BANDS));
            int from = Math.max(1, (int) (low / hz));
            int to = Math.min(HOP / 2 - 1, Math.max(from + 1, (int) (high / hz)));
            float top = 0f;
            for (int i = from; i < to; i++) {
                float m = re[i] * re[i] + im[i] * im[i];
                if (m > top) top = m;
            }
            top = (float) Math.sqrt(top);
            bandPeak[b] = Math.max(top, bandPeak[b] * 0.996f);
            float v = bandPeak[b] > 0.001f ? top / bandPeak[b] : 0f;
            bandValue[b] += (v - bandValue[b]) * (v > bandValue[b] ? 0.7f : 0.22f);
        }
    }

    private void hop(float energy) {
        peak = Math.max(energy, peak * 0.997f);
        level = peak > QUIET ? (float) Math.sqrt(energy / peak) : 0f;
        if (filled >= WINDOW / 2) {
            float mean = 0f;
            for (int i = 0; i < filled; i++) mean += history[i];
            mean /= filled;
            long now = System.currentTimeMillis();
            if (energy > mean * THRESHOLD && energy > QUIET && energy >= previous && now - lastBeatAt > MIN_GAP_MS) {
                lastBeatAt = now;
                fire(Math.max(0.2f, Math.min(1f, (energy / Math.max(mean, 1e-9f) - 1f) / 2f)));
            }
        }
        previous = energy;
        history[head] = energy;
        head = (head + 1) % WINDOW;
        if (filled < WINDOW) filled++;
    }

    private void fire(float strength) {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) {
                try {
                    l.onBeat(strength);
                } catch (Throwable ignore) {
                }
            }
        }, OUTPUT_DELAY_MS);
    }
}
