package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public final class PrizmaOffline implements NotificationCenter.NotificationCenterDelegate {
    private static final PrizmaOffline instance = new PrizmaOffline();
    private static final HashMap<String, ArrayList<Utilities.Callback<Boolean>>> waiting = new HashMap<>();
    private static final HashSet<String> copying = new HashSet<>();
    private static final HashMap<String, ArrayList<Utilities.Callback<File>>> locating = new HashMap<>();
    private static HashSet<String> names;
    private static boolean observing;
    private static int version;

    private PrizmaOffline() {}

    public static int version() {
        return version;
    }

    private static File dir() {
        File d = new File(ApplicationLoader.getFilesDirFixed(), "libre_music");
        d.mkdirs();
        return d;
    }

    private static synchronized HashSet<String> names() {
        if (names == null) {
            names = new HashSet<>();
            String[] list = dir().list();
            if (list != null) {
                for (String n : list) if (!n.endsWith(".part")) names.add(n);
            }
        }
        return names;
    }

    private static String nameOf(TLRPC.Document doc) {
        if (doc == null || doc.id == 0) return null;
        String name = FileLoader.getAttachFileName(doc);
        return name == null || name.isEmpty() ? doc.dc_id + "_" + doc.id + ".audio" : name;
    }

    public static boolean has(TLRPC.Document doc) {
        String name = nameOf(doc);
        if (name == null) return false;
        synchronized (PrizmaOffline.class) {
            return names().contains(name);
        }
    }

    public static boolean has(MessageObject mo) {
        return mo != null && has(mo.getDocument());
    }

    public static boolean busy(MessageObject mo) {
        String name = mo == null ? null : nameOf(mo.getDocument());
        return name != null && (waiting.containsKey(name) || copying.contains(name));
    }

    public static boolean attach(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || !has(mo.getDocument())) return false;
        File f = new File(dir(), nameOf(mo.getDocument()));
        if (!f.exists() || f.length() == 0) {
            synchronized (PrizmaOffline.class) {
                names().remove(f.getName());
            }
            return false;
        }
        mo.messageOwner.attachPath = f.getAbsolutePath();
        return true;
    }

    public static long usedBytes() {
        long total = 0;
        File[] files = dir().listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    public static void save(int account, MessageObject mo, Utilities.Callback<Boolean> done) {
        final TLRPC.Document doc = mo == null ? null : mo.getDocument();
        final String name = nameOf(doc);
        if (name == null) {
            if (done != null) done.run(false);
            return;
        }
        if (has(doc)) {
            if (done != null) done.run(true);
            return;
        }
        ArrayList<Utilities.Callback<Boolean>> list = waiting.get(name);
        if (list != null) {
            if (done != null) list.add(done);
            return;
        }
        list = new ArrayList<>();
        if (done != null) list.add(done);
        waiting.put(name, list);
        File ready = cached(account, mo, doc);
        if (ready != null) {
            copy(name, ready);
            return;
        }
        observe();
        FileLoader.getInstance(account).loadFile(doc, mo, FileLoader.PRIORITY_NORMAL, 0);
    }

    public static void locate(int account, MessageObject mo, Utilities.Callback<File> done) {
        final TLRPC.Document doc = mo == null ? null : mo.getDocument();
        final String name = nameOf(doc);
        if (name == null) {
            done.run(null);
            return;
        }
        if (has(doc)) {
            File own = new File(dir(), name);
            if (own.exists() && own.length() > 0) {
                done.run(own);
                return;
            }
        }
        File ready = cached(account, mo, doc);
        if (ready != null) {
            done.run(ready);
            return;
        }
        ArrayList<Utilities.Callback<File>> list = locating.get(name);
        if (list != null) {
            list.add(done);
            return;
        }
        list = new ArrayList<>();
        list.add(done);
        locating.put(name, list);
        observe();
        FileLoader.getInstance(account).loadFile(doc, mo, FileLoader.PRIORITY_LOW, 0);
    }

    private static void located(String name, File file) {
        ArrayList<Utilities.Callback<File>> list = locating.remove(name);
        if (list == null) return;
        for (Utilities.Callback<File> c : list) {
            try {
                c.run(file);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private static File cached(int account, MessageObject mo, TLRPC.Document doc) {
        try {
            if (mo.messageOwner != null && mo.messageOwner.attachPath != null && mo.messageOwner.attachPath.length() > 0) {
                File own = new File(mo.messageOwner.attachPath);
                if (own.exists() && own.length() > 0) return own;
            }
            File f = FileLoader.getInstance(account).getPathToMessage(mo.messageOwner);
            if (f != null && f.exists() && f.length() > 0 && (doc.size <= 0 || f.length() >= doc.size)) return f;
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private static void observe() {
        if (observing) return;
        observing = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(instance, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(a).addObserver(instance, NotificationCenter.fileLoadFailed);
        }
    }

    private static void finish(String name, boolean ok) {
        copying.remove(name);
        ArrayList<Utilities.Callback<Boolean>> list = waiting.remove(name);
        version++;
        if (list == null) return;
        for (Utilities.Callback<Boolean> c : list) {
            try {
                c.run(ok);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private static void copy(String name, File src) {
        if (!copying.add(name)) return;
        PrizmaJobs.disk(() -> {
            boolean ok = false;
            File dst = new File(dir(), name);
            File part = new File(dir(), name + ".part");
            try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
                ok = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (ok) {
                if (dst.exists()) dst.delete();
                ok = part.renameTo(dst);
            }
            if (!ok) part.delete();
            final boolean result = ok;
            if (result) {
                synchronized (PrizmaOffline.class) {
                    names().add(name);
                }
            }
            AndroidUtilities.runOnUIThread(() -> finish(name, result));
        });
    }

    public static void remove(MessageObject mo) {
        String name = mo == null ? null : nameOf(mo.getDocument());
        if (name == null) return;
        synchronized (PrizmaOffline.class) {
            names().remove(name);
        }
        new File(dir(), name).delete();
        if (mo.messageOwner != null && mo.messageOwner.attachPath != null && mo.messageOwner.attachPath.endsWith("/libre_music/" + name)) {
            mo.messageOwner.attachPath = "";
        }
        version++;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (args.length == 0 || !(args[0] instanceof String)) return;
        final String name = (String) args[0];
        if (locating.containsKey(name)) {
            located(name, id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File ? (File) args[1] : null);
        }
        if (!waiting.containsKey(name) || copying.contains(name)) return;
        if (id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File) {
            copy(name, (File) args[1]);
        } else if (id == NotificationCenter.fileLoadFailed) {
            finish(name, false);
        }
    }
}
