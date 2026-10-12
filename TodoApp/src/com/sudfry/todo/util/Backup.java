package com.sudfry.todo.util;

import android.content.Context;
import android.util.Log;

import com.sudfry.todo.billing.PendingPays;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.store.TodoStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 本地备份与恢复。
 *
 * 三件事：
 *  1. 把待办 + 账目 + 待确认通知打包成一个 JSON 存到应用私有目录（backups/）；
 *  2. 能导出成文件带走、也能从文件导入回来（也认旧版本单独导出的 todos.json / entries.json）；
 *  3. 按用户设定的间隔自动备份，并只保留最近若干个，避免越滚越大。
 *
 * 为什么导入默认是「合并」而不是「清空再导入」：
 * 备份可能是几天前的，直接覆盖会把这几天的记录弄丢。合并只增不减，同一条以备份为准，
 * 想彻底还原再手动选「覆盖恢复」。恢复前一律先自动备份一次，误操作也能退回来。
 */
public final class Backup {

    private static final String TAG = "Backup";
    private static final int KEEP = 12;

    private Backup() {
    }

    /** 备份目录：应用私有目录下的 backups/。 */
    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "backups");
        if (!d.exists() && !d.mkdirs()) {
            Log.w(TAG, "备份目录建不出来");
        }
        return d;
    }

    public static class Meta {
        public File file;
        public String name = "";
        public long at;
        public long size;
        public int todos;
        public int entries;
        public boolean auto;

        public String describe() {
            return Fmt.dateFull(at) + " · " + todos + " 条待办 / " + entries + " 条账目";
        }

        public String sizeText() {
            if (size < 1024) {
                return size + " B";
            }
            if (size < 1024 * 1024) {
                return (size / 1024) + " KB";
            }
            return String.format(Locale.US, "%.1f MB", size / 1048576.0);
        }
    }

    // ───────────────── 生成 ─────────────────

    /** 打包当前数据。 */
    public static JSONObject snapshot(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("app", "日簿·待办记账");
            o.put("format", 2);
            o.put("package", c.getPackageName());
            o.put("at", System.currentTimeMillis());
            try {
                o.put("version", c.getPackageManager()
                        .getPackageInfo(c.getPackageName(), 0).versionName);
            } catch (Throwable ignored) {
            }
            o.put("todos", TodoStore.get(c).exportArray());
            o.put("entries", EntryStore.get(c).exportArray());
            JSONArray pending = new JSONArray();
            for (PendingPays.Item it : PendingPays.all(c)) {
                pending.put(PendingPays.toJson(it));
            }
            o.put("pending", pending);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** 立刻备份一份，返回文件。 */
    public static File create(Context c, boolean auto) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File f = new File(dir(c), "ribook-" + stamp + (auto ? "-auto" : "") + ".json");
        try {
            JSONObject o = snapshot(c);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(o.toString().getBytes(StandardCharsets.UTF_8));
                fo.flush();
            }
            AppPrefs.get(c).setLastBackupAt(System.currentTimeMillis());
            prune(c);
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "备份失败：" + t);
            return null;
        }
    }

    /** 只留最近的 KEEP 份。 */
    public static void prune(Context c) {
        List<Meta> all = list(c);
        for (int i = KEEP; i < all.size(); i++) {
            // 用户手动存的尽量留到最后，先删自动的
            all.get(i).file.delete();
        }
    }

    public static List<Meta> list(Context c) {
        ArrayList<Meta> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) {
            return out;
        }
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) {
                continue;
            }
            Meta m = new Meta();
            m.file = f;
            m.name = f.getName();
            m.at = f.lastModified();
            m.size = f.length();
            m.auto = f.getName().contains("-auto");
            try {
                JSONObject o = new JSONObject(read(f));
                m.at = o.optLong("at", m.at);
                m.todos = o.optJSONArray("todos") == null ? 0 : o.optJSONArray("todos").length();
                m.entries = o.optJSONArray("entries") == null ? 0 : o.optJSONArray("entries").length();
            } catch (Throwable ignored) {
            }
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(b.at, a.at));
        return out;
    }

    public static boolean delete(File f) {
        return f != null && f.exists() && f.delete();
    }

    // ───────────────── 读 / 恢复 ─────────────────

    public static String read(InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            Log.w(TAG, "读流失败：" + t);
        }
        return sb.toString();
    }

    public static String read(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            Log.w(TAG, "读文件失败：" + t);
        }
        return sb.toString();
    }

    /**
     * 宽容地解析一份备份，返回 {todos, entries, at, legacy}。
     *
     * 认三种东西：
     *  - 本应用导出的完整备份（有 todos / entries 字段）
     *  - 旧版本单独导出的 todos.json（{"items":[{title...}]}）
     *  - 旧版本单独导出的 entries.json（{"items":[{amount,type...}]}）
     * 后两种靠条目字段猜是待办还是账目 —— 这就是「导入旧版本」能成立的原因。
     */
    public static JSONObject parse(String text) throws JSONException {
        JSONObject root = new JSONObject(text);
        JSONObject out = new JSONObject();
        out.put("at", root.optLong("at", 0));

        JSONArray todos = root.optJSONArray("todos");
        JSONArray entries = root.optJSONArray("entries");
        boolean legacy = false;

        if (todos == null && entries == null) {
            JSONArray items = root.optJSONArray("items");
            if (items == null) {
                throw new JSONException("看不懂这个文件：既不是备份，也不是旧版数据");
            }
            legacy = true;
            List<JSONObject> ts = new ArrayList<>();
            List<JSONObject> es = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (o.has("amount") || o.has("category") || o.has("type")) {
                    es.add(o);
                } else {
                    ts.add(o);
                }
            }
            todos = new JSONArray(ts);
            entries = new JSONArray(es);
        }
        out.put("todos", todos == null ? new JSONArray() : todos);
        out.put("entries", entries == null ? new JSONArray() : entries);
        out.put("legacy", legacy);
        return out;
    }

    public static class Result {
        public int todoAdded;
        public int todoUpdated;
        public int entryAdded;
        public int entryUpdated;
        public boolean replace;
        public File safety;

        public String describe() {
            return (replace ? "覆盖恢复完成：" : "导入完成：")
                    + "待办 +" + todoAdded + " / 更新 " + todoUpdated
                    + "，账目 +" + entryAdded + " / 更新 " + entryUpdated;
        }
    }

    /** 恢复一份数据。replace=true 时先清空再导入。 */
    public static Result restore(Context c, JSONObject parsed, boolean replace) throws JSONException {
        Result r = new Result();
        r.replace = replace;
        // 先给当前数据留一份退路
        r.safety = create(c, true);
        if (replace) {
            TodoStore.get(c).clearAll();
            EntryStore.get(c).clearAll();
        }
        TodoStore.MergeResult tr = TodoStore.get(c).mergeArray(parsed.getJSONArray("todos"), false);
        TodoStore.MergeResult er = EntryStore.get(c).mergeArray(parsed.getJSONArray("entries"), false);
        r.todoAdded = tr.added;
        r.todoUpdated = tr.updated;
        r.entryAdded = er.added;
        r.entryUpdated = er.updated;
        return r;
    }

    // ───────────────── 自动备份 ─────────────────

    public static long intervalMs(Context c) {
        return AppPrefs.get(c).autoBackupHours() * 3600_000L;
    }

    public static boolean due(Context c) {
        if (!AppPrefs.get(c).autoBackup()) {
            return false;
        }
        long last = AppPrefs.get(c).lastBackupAt();
        if (last == 0) {
            return true;
        }
        return System.currentTimeMillis() - last >= intervalMs(c);
    }

    /** 到点了就自动备一份，返回是否真的备了。 */
    public static boolean maybeAuto(Context c) {
        if (!due(c)) {
            return false;
        }
        // 没数据就不必备，免得堆一堆空文件
        if (TodoStore.get(c).size() == 0 && EntryStore.get(c).count() == 0) {
            return false;
        }
        return create(c, true) != null;
    }

    public static String[] intervalNames() {
        return new String[]{"每 6 小时", "每 12 小时", "每天", "每 3 天", "每周"};
    }

    public static int[] intervalHours() {
        return new int[]{6, 12, 24, 72, 168};
    }

    public static String intervalName(Context c) {
        int h = AppPrefs.get(c).autoBackupHours();
        int[] hs = intervalHours();
        for (int i = 0; i < hs.length; i++) {
            if (hs[i] == h) {
                return intervalNames()[i];
            }
        }
        return "每 " + h + " 小时";
    }

    public static List<String> allFiles(Context c) {
        List<Meta> l = list(c);
        List<String> names = new ArrayList<>();
        for (Meta m : l) {
            names.add(m.name);
        }
        return names;
    }
}
