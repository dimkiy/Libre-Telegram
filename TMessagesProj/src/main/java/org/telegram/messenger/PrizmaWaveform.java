package org.telegram.messenger;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.LruCache;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;

public final class PrizmaWaveform {
    public interface Callback {
        void onReady(float[] peaks);
    }

    public static final int POINTS = 96;

    private static final LruCache<String, float[]> cache = new LruCache<>(48);
    private static final HashSet<String> working = new HashSet<>();

    private PrizmaWaveform() {
    }

    public static void load(MessageObject mo, Callback callback) {
        if (mo == null || callback == null) return;
        final File file = localFile(mo);
        if (file == null) return;
        final String key = file.getName() + ":" + file.length();
        float[] known = cache.get(key);
        if (known != null) {
            callback.onReady(known);
            return;
        }
        synchronized (working) {
            if (!working.add(key)) return;
        }
        final long durationUs = (long) (mo.getDuration() * 1_000_000L);
        Utilities.globalQueue.postRunnable(() -> {
            float[] result = null;
            try {
                result = read(file, durationUs);
            } catch (Throwable ignore) {
            }
            synchronized (working) {
                working.remove(key);
            }
            final float[] peaks = result;
            if (peaks != null) {
                cache.put(key, peaks);
                AndroidUtilities.runOnUIThread(() -> callback.onReady(peaks));
            }
        });
    }

    private static File localFile(MessageObject mo) {
        try {
            if (mo.messageOwner != null && mo.messageOwner.attachPath != null && mo.messageOwner.attachPath.length() > 0) {
                File f = new File(mo.messageOwner.attachPath);
                if (f.exists()) return f;
            }
            File f = FileLoader.getInstance(mo.currentAccount).getPathToMessage(mo.messageOwner);
            if (f != null && f.exists() && f.length() > 0) return f;
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static float[] read(File file, long durationUs) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0) return null;
            extractor.selectTrack(track);
            if (durationUs <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION);
            }
            if (durationUs <= 0) return null;
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            float[] peaks = new float[POINTS];
            float top = 0f;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int p = 0; p < POINTS; p++) {
                long at = (long) (durationUs * (p + 0.5f) / POINTS);
                extractor.seekTo(at, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                codec.flush();
                float peak = sample(extractor, codec, info);
                peaks[p] = peak;
                if (peak > top) top = peak;
            }
            if (top < 0.01f) return null;
            for (int p = 0; p < POINTS; p++) {
                peaks[p] = (float) Math.pow(peaks[p] / top, 0.75f);
            }
            return peaks;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignore) {
                }
                try {
                    codec.release();
                } catch (Throwable ignore) {
                }
            }
            extractor.release();
        }
    }

    private static float sample(MediaExtractor extractor, MediaCodec codec, MediaCodec.BufferInfo info) {
        int collected = 0;
        double sum = 0;
        int tries = 0;
        boolean inputDone = false;
        while (collected < 4096 && tries++ < 24) {
            if (!inputDone) {
                int in = codec.dequeueInputBuffer(4000);
                if (in >= 0) {
                    ByteBuffer buffer = codec.getInputBuffer(in);
                    int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int out = codec.dequeueOutputBuffer(info, 4000);
            if (out >= 0) {
                ByteBuffer buffer = codec.getOutputBuffer(out);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    buffer.order(ByteOrder.LITTLE_ENDIAN);
                    while (buffer.remaining() >= 2 && collected < 4096) {
                        float v = buffer.getShort() / 32768f;
                        sum += v * v;
                        collected++;
                    }
                }
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        return collected == 0 ? 0f : (float) Math.sqrt(sum / collected);
    }
}
