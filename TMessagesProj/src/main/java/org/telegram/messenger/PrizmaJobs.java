package org.telegram.messenger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PrizmaJobs {
    private PrizmaJobs() {}

    private static ExecutorService pool(String name, int threads) {
        final AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor e = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name + counter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    private static final ExecutorService net = pool("libreNet", 4);
    private static final ExecutorService heavy = pool("libreHeavy", 1);
    private static final ExecutorService disk = pool("libreDisk", 1);

    private static Runnable guarded(Runnable job) {
        return () -> {
            try {
                job.run();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        };
    }

    public static void net(Runnable job) {
        net.execute(guarded(job));
    }

    public static void heavy(Runnable job) {
        heavy.execute(guarded(job));
    }

    public static void disk(Runnable job) {
        disk.execute(guarded(job));
    }
}
