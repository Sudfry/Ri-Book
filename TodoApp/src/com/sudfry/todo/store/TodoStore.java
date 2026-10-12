package com.sudfry.todo.store;

import android.content.Context;

import com.sudfry.todo.model.Todo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 本地 JSON 存储（files/todos.json），单例。同时负责导入导出的合并逻辑。 */
public final class TodoStore {

    private static final String FILE_NAME = "todos.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String APP_TAG = "sudfry-todo";
    public static final int FORMAT_VERSION = 2;

    private static TodoStore instance;

    private final File file;
    private final ArrayList<Todo> items = new ArrayList<>();
    private long nextId = 1;

    public static synchronized TodoStore get(Context c) {
        if (instance == null) {
            instance = new TodoStore(c.getApplicationContext());
        }
        return instance;
    }

    private TodoStore(Context c) {
        file = new File(c.getFilesDir(), FILE_NAME);
        load();
    }

    public File dataFile() {
        return file;
    }

    // ───────────────────────── 基本读写 ─────────────────────────

    public synchronized List<Todo> all() {
        return new ArrayList<>(items);
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized Todo byId(long id) {
        for (Todo t : items) {
            if (t.id == id) {
                return t;
            }
        }
        return null;
    }

    public synchronized Todo add(Todo t) {
        t.id = nextId++;
        normalize(t, true);
        items.add(t);
        save();
        return t;
    }

    /** 撤销删除用：把原来的那条原样放回去（uid 不变）。 */
    public synchronized Todo restore(Todo t) {
        if (t == null) {
            return null;
        }
        boolean idTaken = false;
        for (Todo x : items) {
            if (x.id == t.id) {
                idTaken = true;
                break;
            }
        }
        if (t.id <= 0 || idTaken) {
            t.id = nextId++;
        } else if (t.id >= nextId) {
            nextId = t.id + 1;
        }
        if (t.uid == null || t.uid.isEmpty()) {
            t.uid = UUID.randomUUID().toString();
        }
        items.add(t);
        save();
        return t;
    }

    public synchronized void update(Todo t) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == t.id) {
                normalize(t, false);
                items.set(i, t);
                save();
                return;
            }
        }
        add(t);
    }

    public synchronized Todo delete(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                Todo removed = items.remove(i);
                save();
                return removed;
            }
        }
        return null;
    }

    public synchronized int clearCompleted() {
        int n = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).done) {
                items.remove(i);
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    /** 清空全部任务。 */
    public synchronized int clearAll() {
        int n = items.size();
        items.clear();
        save();
        return n;
    }

    /**
     * 勾选 / 取消勾选。重复任务勾选后会自动生成下一次实例，并作为返回值返回。
     */
    public synchronized Todo setDone(long id, boolean done) {
        Todo t = byId(id);
        if (t == null) {
            return null;
        }
        t.done = done;
        t.doneAt = done ? System.currentTimeMillis() : 0;
        t.updatedAt = System.currentTimeMillis();
        Todo next = null;
        if (done && t.isRepeating() && t.nextDue() > 0) {
            next = t.copy();
            next.id = 0;
            next.uid = UUID.randomUUID().toString();
            next.due = t.nextDue();
            next.done = false;
            next.doneAt = 0;
            next.createdAt = System.currentTimeMillis();
            next.updatedAt = next.createdAt;
            next.id = nextId++;
            items.add(next);
        }
        save();
        return next;
    }

    public synchronized List<Todo> onDay(LocalDate day) {
        ArrayList<Todo> out = new ArrayList<>();
        for (Todo t : items) {
            if (t.due > 0 && Todo.toLocal(t.due).toLocalDate().equals(day)) {
                out.add(t);
            }
        }
        return out;
    }

    public synchronized List<Todo> sorted(long now) {
        ArrayList<Todo> out = new ArrayList<>(items);
        Collections.sort(out, (a, b) -> com.sudfry.todo.util.Urgency.compare(a, b, now));
        return out;
    }

    /** 全部标签，按出现次数从多到少。 */
    public synchronized List<String> allTags() {
        HashMap<String, Integer> count = new HashMap<>();
        for (Todo t : items) {
            String tag = t.tag == null ? "" : t.tag.trim();
            if (!tag.isEmpty()) {
                Integer c = count.get(tag);
                count.put(tag, c == null ? 1 : c + 1);
            }
        }
        ArrayList<String> out = new ArrayList<>(count.keySet());
        Collections.sort(out, (a, b) -> {
            int c = count.get(b) - count.get(a);
            return c != 0 ? c : a.compareTo(b);
        });
        return out;
    }

    /** 本周（周一起算）完成的数量与逾期数量。 */
    public synchronized int[] weeklyStats(long now) {
        LocalDate today = Todo.toLocal(now).toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long start = Todo.toMillis(monday.atStartOfDay());
        int done = 0;
        int overdue = 0;
        for (Todo t : items) {
            if (t.done) {
                if (t.doneAt >= start) {
                    done++;
                }
            } else if (t.due > 0 && t.due < now) {
                overdue++;
            }
        }
        return new int[]{done, overdue};
    }

    // ───────────────────────── 导入 / 导出 ─────────────────────────

    /** 只要任务数组，供整包备份使用。 */
    public synchronized JSONArray exportArray() {
        JSONArray arr = new JSONArray();
        for (Todo t : items) {
            arr.put(t.toJson());
        }
        return arr;
    }

    /** 按 uid/updatedAt 合并一个任务数组（整包备份用）。 */
    public synchronized MergeResult mergeArray(JSONArray arr, boolean replace) {
        MergeResult r = new MergeResult();
        if (arr == null) {
            return r;
        }
        JSONObject root = new JSONObject();
        try {
            root.put("items", arr);
        } catch (Exception ignored) {
        }
        try {
            return mergeJson(root.toString(), replace);
        } catch (Exception e) {
            return r;
        }
    }

    /** 导出全部数据（含设备无关的 uid），返回格式化好的 JSON 文本。 */
    public synchronized String exportJson(String deviceName) {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            root.put("app", APP_TAG);
            root.put("version", FORMAT_VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("device", deviceName == null ? "" : deviceName);
            for (Todo t : items) {
                arr.put(t.toJson());
            }
            root.put("items", arr);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    public synchronized int count() {
        return items.size();
    }

    public static class MergeResult {
        public int added;
        public int updated;
        public int skipped;
        public int total;
        public boolean replace;

        public String describe() {
            if (replace) {
                return "已用对方数据覆盖本机，共 " + total + " 项";
            }
            return "新增 " + added + " 项，更新 " + updated + " 项，无变化 " + skipped + " 项";
        }
    }

    /**
     * 合并外部数据。
     * 同一 uid 视为同一条：谁 updatedAt 新就用谁；本机没有的就新增。
     * 没有 uid 的旧数据用「标题+截止时间」指纹兜底。
     */
    public synchronized MergeResult mergeJson(String raw, boolean replace) throws Exception {
        JSONObject root;
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("[")) {
            root = new JSONObject();
            root.put("items", new JSONArray(text));
        } else {
            root = new JSONObject(text);
        }
        JSONArray arr = root.optJSONArray("items");
        if (arr == null) {
            throw new Exception("数据里没有 items 字段");
        }
        ArrayList<Todo> incoming = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                incoming.add(Todo.fromJson(o));
            }
        }

        MergeResult r = new MergeResult();
        r.total = incoming.size();
        r.replace = replace;

        if (replace) {
            items.clear();
            for (Todo t : incoming) {
                t.id = nextId++;
                normalize(t, true);
                items.add(t);
            }
            r.added = incoming.size();
            save();
            return r;
        }

        HashMap<String, Todo> byUid = new HashMap<>();
        HashMap<String, Todo> bySig = new HashMap<>();
        for (Todo t : items) {
            if (t.uid != null && !t.uid.isEmpty()) {
                byUid.put(t.uid, t);
            }
            bySig.put(t.signature(), t);
        }

        for (Todo in : incoming) {
            Todo local = null;
            if (in.uid != null && !in.uid.isEmpty()) {
                local = byUid.get(in.uid);
            }
            if (local == null) {
                local = bySig.get(in.signature());
            }
            if (local == null) {
                in.id = nextId++;
                normalize(in, true);
                items.add(in);
                if (in.uid != null && !in.uid.isEmpty()) {
                    byUid.put(in.uid, in);
                }
                bySig.put(in.signature(), in);
                r.added++;
            } else if (in.updatedAt > local.updatedAt) {
                long keepId = local.id;
                String keepUid = (local.uid == null || local.uid.isEmpty())
                        ? (in.uid == null || in.uid.isEmpty() ? UUID.randomUUID().toString() : in.uid)
                        : local.uid;
                Todo copy = in.copy();
                copy.id = keepId;
                copy.uid = keepUid;
                copy.updatedAt = in.updatedAt > 0 ? in.updatedAt : System.currentTimeMillis();
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).id == keepId) {
                        items.set(i, copy);
                        break;
                    }
                }
                byUid.put(keepUid, copy);
                bySig.put(copy.signature(), copy);
                r.updated++;
            } else {
                r.skipped++;
            }
        }
        save();
        return r;
    }

    // ───────────────────────── 内部 ─────────────────────────

    private void normalize(Todo t, boolean isNew) {
        long now = System.currentTimeMillis();
        if (t.uid == null || t.uid.isEmpty()) {
            t.uid = UUID.randomUUID().toString();
        }
        if (t.createdAt == 0) {
            t.createdAt = now;
        }
        if (isNew) {
            t.updatedAt = now;
        } else if (t.updatedAt == 0) {
            t.updatedAt = now;
        }
    }

    private void load() {
        items.clear();
        nextId = 1;
        File f = file;
        if (!f.exists()) {
            File bak = new File(f.getParentFile(), FILE_NAME + ".bak");
            if (bak.exists()) {
                f = bak;
            } else {
                seed();
                return;
            }
        }
        try {
            if (!parseInto(f)) {
                throw new Exception("empty");
            }
        } catch (Exception broken) {
            // 主文件坏了就退回备份
            File bak = new File(file.getParentFile(), FILE_NAME + ".bak");
            if (bak.exists() && !bak.equals(f)) {
                items.clear();
                nextId = 1;
                try {
                    parseInto(bak);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 读一个文件进内存；返回是否读到了内容。 */
    private boolean parseInto(File f) throws Exception {
        String raw = readAll(f);
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        JSONObject root = new JSONObject(raw);
        long nid = root.optLong("nextId", 1);
        JSONArray arr = root.optJSONArray("items");
        HashSet<String> seenUid = new HashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Todo t = Todo.fromJson(o);
                if (t.uid == null || t.uid.isEmpty() || seenUid.contains(t.uid)) {
                    t.uid = UUID.randomUUID().toString();
                }
                seenUid.add(t.uid);
                if (t.updatedAt == 0) {
                    t.updatedAt = t.createdAt != 0 ? t.createdAt : System.currentTimeMillis();
                }
                if (t.id <= 0) {
                    t.id = nid++;
                }
                if (t.id >= nid) {
                    nid = t.id + 1;
                }
                items.add(t);
            }
        }
        nextId = nid;
        return true;
    }

    private static void copyFile(File from, File to) {
        try (RandomAccessFile in = new RandomAccessFile(from, "r");
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }

    /** 第一次打开时放几条示例，让人立刻看懂界面。 */
    private void seed() {
        long now = System.currentTimeMillis();
        java.time.LocalDateTime base = Todo.toLocal(now)
                .withHour(9).withMinute(0).withSecond(0).withNano(0);

        Todo a = new Todo("给客户回复报价邮件", Todo.toMillis(base.withHour(11)), true);
        a.priority = Todo.PRI_URGENT;
        a.tag = "工作";
        a.durationMin = 45;
        a.remindBefore = 15;
        items.add(a);

        Todo b = new Todo("下午 3 点项目周会", Todo.toMillis(base.withHour(15)), true);
        b.priority = Todo.PRI_HIGH;
        b.tag = "工作";
        b.durationMin = 60;
        b.repeat = Todo.REPEAT_WEEKLY;
        items.add(b);

        Todo c = new Todo("交水费", Todo.toMillis(base.minusDays(2).withHour(18)), true);
        c.priority = Todo.PRI_HIGH;
        c.tag = "生活";
        items.add(c);

        Todo d = new Todo("买下周出差的火车票", Todo.toMillis(base.plusDays(1).withHour(20)), true);
        d.priority = Todo.PRI_MID;
        d.durationMin = 30;
        d.tag = "生活";
        items.add(d);

        Todo e = new Todo("读完《深度工作》第 4 章", Todo.toMillis(base.plusDays(3)), false);
        e.priority = Todo.PRI_LOW;
        e.durationMin = 90;
        e.tag = "学习";
        items.add(e);

        Todo f = new Todo("整理相册", 0, false);
        f.priority = Todo.PRI_LOW;
        f.tag = "生活";
        items.add(f);

        Todo g = new Todo("晨跑 3 公里", Todo.toMillis(base.withHour(7)), true);
        g.durationMin = 40;
        g.tag = "健康";
        g.repeat = Todo.REPEAT_DAILY;
        g.done = true;
        g.doneAt = now;
        items.add(g);

        for (Todo t : items) {
            normalize(t, true);
            t.createdAt = now;
            t.updatedAt = now;
            t.id = nextId++;
        }
        save();
    }

    public synchronized void save() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            root.put("nextId", nextId);
            for (Todo t : items) {
                arr.put(t.toJson());
            }
            root.put("items", arr);
            byte[] data = root.toString().getBytes(UTF8);
            // 写之前把上一版留成 .bak，坏文件时还能退回去
            File bak = new File(file.getParentFile(), FILE_NAME + ".bak");
            if (file.exists() && file.length() > 2) {
                copyFile(file, bak);
            }
            File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
                fos.flush();
                fos.getFD().sync();
            }
            if (file.exists() && !file.delete()) {
                // 忽略：下面直接覆盖写
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String readAll(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            return new String(buf, UTF8);
        }
    }
}
