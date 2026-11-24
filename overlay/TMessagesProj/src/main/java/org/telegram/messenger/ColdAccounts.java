package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Mr qik yan "Telegraph mode": unlimited cold accounts.
 * Only the account in live slot 0 is instantiated by Telegram's machinery.
 * Every other account is stored as a plain file copy under files/coldaccounts/<userId>/.
 * Switching = write a pending marker, restart the process, and swap the file
 * sets at the very top of ApplicationLoader.onCreate, before any account
 * state is read. Copies are staged to a temp dir and renamed, and an archive
 * is only accepted when it contains tgnet.dat, so an interrupted swap can
 * never destroy a stored session.
 */
public class ColdAccounts {

    public static class Entry {
        public long id;
        public String name;
        public String phone;
        public String username;
    }

    private static final String[] SLOT_FILES = {
            "tgnet.dat", "dc1conf.dat", "dc2conf.dat", "dc3conf.dat", "dc4conf.dat", "dc5conf.dat",
            "cache4.db", "cache4.db-wal", "cache4.db-shm",
            "file_to_path.db", "file_to_path.db-wal", "file_to_path.db-shm"
    };
    private static final String[] SLOT_PREFS = {"userconfing", "mainconfig", "Notifications", "emoji"};

    public static File rootDir(Context ctx) {
        return new File(ctx.getFilesDir(), "coldaccounts");
    }

    private static File metaFile(Context ctx) {
        return new File(rootDir(ctx), "meta.json");
    }

    private static File pendingFile(Context ctx) {
        return new File(rootDir(ctx), "pending.json");
    }

    private static File accountDir(Context ctx, long id) {
        return new File(rootDir(ctx), String.valueOf(id));
    }

    private static File prefsDir(Context ctx) {
        return new File(ctx.getApplicationInfo().dataDir, "shared_prefs");
    }

    // ---------------- meta ----------------

    public static ArrayList<Entry> loadEntries(Context ctx) {
        ArrayList<Entry> out = new ArrayList<>();
        try {
            File f = metaFile(ctx);
            if (!f.exists()) {
                return out;
            }
            JSONObject meta = new JSONObject(readFile(f));
            JSONArray arr = meta.optJSONArray("accounts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Entry e = new Entry();
                    e.id = o.optLong("id");
                    e.name = o.optString("name");
                    e.phone = o.optString("phone");
                    e.username = o.optString("username");
                    if (e.id != 0) {
                        out.add(e);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    public static long loadLiveId(Context ctx) {
        try {
            File f = metaFile(ctx);
            if (!f.exists()) {
                return 0;
            }
            return new JSONObject(readFile(f)).optLong("liveId", 0);
        } catch (Throwable ignore) {
            return 0;
        }
    }

    public static void saveMeta(Context ctx, ArrayList<Entry> entries, long liveId) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("name", e.name == null ? "" : e.name);
                o.put("phone", e.phone == null ? "" : e.phone);
                o.put("username", e.username == null ? "" : e.username);
                arr.put(o);
            }
            JSONObject meta = new JSONObject();
            meta.put("accounts", arr);
            meta.put("liveId", liveId);
            writeFileSync(metaFile(ctx), meta.toString());
        } catch (Throwable ignore) {
        }
    }

    public static void upsertLive(Context ctx, Entry e) {
        ArrayList<Entry> entries = loadEntries(ctx);
        boolean found = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == e.id) {
                entries.set(i, e);
                found = true;
                break;
            }
        }
        if (!found) {
            entries.add(e);
        }
        saveMeta(ctx, entries, e.id);
    }

    public static boolean hasAnyAccount(Context ctx) {
        return !loadEntries(ctx).isEmpty();
    }

    public static void deleteCold(Context ctx, long id) {
        deleteRecursive(accountDir(ctx, id));
        ArrayList<Entry> entries = loadEntries(ctx);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).id == id) {
                entries.remove(i);
            }
        }
        long liveId = loadLiveId(ctx);
        saveMeta(ctx, entries, liveId == id ? 0 : liveId);
    }

    // ---------------- pending swap + restart ----------------

    public static void requestSwitch(Context ctx, long toId) {
        writePending(ctx, "switch", toId);
        restartApp(ctx);
    }

    public static void requestArchiveOnly(Context ctx) {
        writePending(ctx, "archive", 0);
        restartApp(ctx);
    }

    private static void writePending(Context ctx, String action, long to) {
        try {
            JSONObject p = new JSONObject();
            p.put("action", action);
            p.put("to", to);
            writeFileSync(pendingFile(ctx), p.toString());
        } catch (Throwable ignore) {
        }
    }

    public static void restartApp(Context ctx) {
        try {
            Intent intent = new Intent(ctx, org.telegram.ui.LaunchActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 9911, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi);
            }
        } catch (Throwable ignore) {
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ---------------- boot-time swap ----------------

    public static void applyPendingSwap(Context ctx) {
        try {
            File pf = pendingFile(ctx);
            if (!pf.exists()) {
                return;
            }
            JSONObject p = new JSONObject(readFile(pf));
            String action = p.optString("action");
            long to = p.optLong("to", 0);
            long liveId = loadLiveId(ctx);
            if (liveId != 0) {
                archiveSlot(ctx, liveId);
            }
            clearSlot(ctx);
            long newLive = 0;
            if ("switch".equals(action) && to != 0) {
                restoreSlot(ctx, to);
                newLive = to;
            }
            saveMeta(ctx, loadEntries(ctx), newLive);
            pf.delete();
        } catch (Throwable ignore) {
        }
    }

    private static void archiveSlot(Context ctx, long id) {
        try {
            File tmp = new File(rootDir(ctx), id + ".tmp");
            deleteRecursive(tmp);
            tmp.mkdirs();
            File files = ctx.getFilesDir();
            for (String name : SLOT_FILES) {
                copyIfExists(new File(files, name), new File(tmp, name));
            }
            File prefsDest = new File(tmp, "prefs");
            prefsDest.mkdirs();
            File prefs = prefsDir(ctx);
            for (String name : SLOT_PREFS) {
                copyIfExists(new File(prefs, name + ".xml"), new File(prefsDest, name + ".xml"));
            }
            if (!new File(tmp, "tgnet.dat").exists()) {
                // Incomplete source: never overwrite a good stored copy.
                deleteRecursive(tmp);
                return;
            }
            File dest = accountDir(ctx, id);
            deleteRecursive(dest);
            tmp.renameTo(dest);
        } catch (Throwable ignore) {
        }
    }

    private static void restoreSlot(Context ctx, long id) {
        try {
            File src = accountDir(ctx, id);
            if (!src.exists()) {
                return;
            }
            File files = ctx.getFilesDir();
            for (String name : SLOT_FILES) {
                copyIfExists(new File(src, name), new File(files, name));
            }
            File prefs = prefsDir(ctx);
            prefs.mkdirs();
            File prefsSrc = new File(src, "prefs");
            for (String name : SLOT_PREFS) {
                copyIfExists(new File(prefsSrc, name + ".xml"), new File(prefs, name + ".xml"));
            }
        } catch (Throwable ignore) {
        }
    }

    private static void clearSlot(Context ctx) {
        File files = ctx.getFilesDir();
        for (String name : SLOT_FILES) {
            new File(files, name).delete();
        }
        File prefs = prefsDir(ctx);
        for (String name : SLOT_PREFS) {
            new File(prefs, name + ".xml").delete();
        }
    }

    // ---------------- io helpers ----------------

    private static void copyIfExists(File src, File dst) throws Exception {
        if (!src.exists()) {
            return;
        }
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.getFD().sync();
        out.close();
        in.close();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        int off = 0;
        int n;
        while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
            off += n;
        }
        in.close();
        return new String(data, 0, off, StandardCharsets.UTF_8);
    }

    private static void writeFileSync(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
        out.close();
        if (f.exists()) {
            f.delete();
        }
        tmp.renameTo(f);
    }
}
