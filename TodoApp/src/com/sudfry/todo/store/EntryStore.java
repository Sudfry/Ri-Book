package com.sudfry.todo.store;

import android.content.Context;

import com.sudfry.todo.model.Categories;
import com.sudfry.todo.model.Entry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 收支记录的本地存储（files/entries.json）。 */
public final class EntryStore {

    private static final String FILE_NAME = "entries.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static EntryStore instance;

    private final File file;
    private final ArrayList<Entry> items = new ArrayList<>();
    private long nextId = 1;

    public static synchronized EntryStore get(Context c) {
        if (instance == null) {
            instance = new EntryStore(c.getApplicationContext());
        }
        return instance;
    }

    private EntryStore(Context c) {
        file = new File(c.getFilesDir(), FILE_NAME);
        load();
    }

    // ───────────────────── 读写 ─────────────────────

    public synchronized List<Entry> all() {
        return new ArrayList<>(items);
    }

    public synchronized int count() {
        return items.size();
    }

    public synchronized Entry byId(long id) {
        for (Entry e : items) {
            if (e.id == id) {
                return e;
            }
        }
        return null;
    }

    public synchronized Entry add(Entry e) {
        e.id = nextId++;
        normalize(e, true);
        items.add(e);
        save();
        return e;
    }

    public synchronized void update(Entry e) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == e.id) {
                normalize(e, false);
                items.set(i, e);
                save();
                return;
            }
        }
        add(e);
    }

    public synchronized Entry delete(long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == id) {
                Entry removed = items.remove(i);
                save();
                return removed;
            }
        }
        return null;
    }

    public synchronized Entry restore(Entry e) {
        if (e == null) {
            return null;
        }
        boolean taken = false;
        for (Entry x : items) {
            if (x.id == e.id) {
                taken = true;
                break;
            }
        }
        if (e.id <= 0 || taken) {
            e.id = nextId++;
        } else if (e.id >= nextId) {
            nextId = e.id + 1;
        }
        items.add(e);
        save();
        return e;
    }

    /** 撤销一整批导入。 */
    public synchronized int deleteBatch(String batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (batch.equals(items.get(i).batch)) {
                items.remove(i);
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    public synchronized int clearAll() {
        int n = items.size();
        items.clear();
        save();
        return n;
    }

    /** 某一天的记录，重点在前、金额大的在前。 */
    public synchronized List<Entry> onDay(String date) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : items) {
            if (date.equals(e.date)) {
                out.add(e);
            }
        }
        sortForList(out);
        return out;
    }

    private void sortForList(ArrayList<Entry> out) {
        Collections.sort(out, (a, b) -> {
            if (a.important != b.important) {
                return a.important ? -1 : 1;
            }
            if (a.type != b.type) {
                return a.type - b.type;
            }
            if (a.amount != b.amount) {
                return Double.compare(b.amount, a.amount);
            }
            return Long.compare(b.createdAt, a.createdAt);
        });
    }

    /** {支出合计, 收入合计} */
    public synchronized double[] dayTotals(String date) {
        double[] r = new double[2];
        for (Entry e : items) {
            if (date.equals(e.date)) {
                r[e.type] += e.amount;
            }
        }
        return r;
    }

    /** {支出合计, 收入合计}，prefix 形如 2026-10 或 2026- */
    public synchronized double[] rangeTotals(String prefix) {
        double[] r = new double[2];
        for (Entry e : items) {
            if (e.date != null && e.date.startsWith(prefix)) {
                r[e.type] += e.amount;
            }
        }
        return r;
    }

    public static String monthPrefix(YearMonth ym) {
        return String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
    }

    /** 某天是否有收支，用于日历打点。 */
    public synchronized boolean[] dayFlags(String date) {
        boolean[] r = new boolean[2];
        for (Entry e : items) {
            if (date.equals(e.date)) {
                r[0] = r[0] || e.type == Entry.EXPENSE;
                r[1] = r[1] || e.type == Entry.INCOME;
            }
        }
        return r;
    }

    /**
     * 分类统计：大类 → 小类 → 金额。只保留金额大于 0 的项。
     */
    public synchronized LinkedHashMap<String, LinkedHashMap<String, Double>> byCategory(String prefix, int type) {
        LinkedHashMap<String, LinkedHashMap<String, Double>> out = new LinkedHashMap<>();
        for (String big : Categories.bigNames(type)) {
            LinkedHashMap<String, Double> subs = new LinkedHashMap<>();
            for (String sub : Categories.subs(type, big)) {
                subs.put(sub, 0.0);
            }
            out.put(big, subs);
        }
        for (Entry e : items) {
            if (e.date == null || !e.date.startsWith(prefix) || e.type != type) {
                continue;
            }
            String big = e.category == null || e.category.isEmpty() ? "其他" : e.category;
            String sub = e.sub == null || e.sub.isEmpty() ? e.label() : e.sub;
            LinkedHashMap<String, Double> subs = out.get(big);
            if (subs == null) {
                subs = new LinkedHashMap<>();
                out.put(big, subs);
            }
            Double cur = subs.get(sub);
            subs.put(sub, (cur == null ? 0 : cur) + e.amount);
        }
        // 去掉没有任何金额的大类与空小类
        LinkedHashMap<String, LinkedHashMap<String, Double>> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Double>> en : out.entrySet()) {
            LinkedHashMap<String, Double> keep = new LinkedHashMap<>();
            double sum = 0;
            for (Map.Entry<String, Double> s : en.getValue().entrySet()) {
                if (s.getValue() > 0) {
                    keep.put(s.getKey(), s.getValue());
                    sum += s.getValue();
                }
            }
            if (sum > 0) {
                cleaned.put(en.getKey(), keep);
            }
        }
        return cleaned;
    }

    /** 结算：按选中的小类统计总支出。 */
    public synchronized double settleExpense(String prefix, List<String> selectedSubs) {
        double sum = 0;
        for (Entry e : items) {
            if (e.date == null || !e.date.startsWith(prefix) || e.type != Entry.EXPENSE) {
                continue;
            }
            String sub = e.sub == null || e.sub.isEmpty() ? e.label() : e.sub;
            if (selectedSubs.contains(sub)) {
                sum += e.amount;
            }
        }
        return sum;
    }

    // ───────────────────── 导入 / 合并 ─────────────────────

    public synchronized JSONArray exportArray() {
        JSONArray arr = new JSONArray();
        for (Entry e : items) {
            arr.put(e.toJson());
        }
        return arr;
    }

    public synchronized TodoStore.MergeResult mergeArray(JSONArray arr, boolean replace) {
        TodoStore.MergeResult r = new TodoStore.MergeResult();
        if (arr == null) {
            return r;
        }
        ArrayList<Entry> incoming = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                incoming.add(Entry.fromJson(o));
            }
        }
        r.total = incoming.size();
        r.replace = replace;

        if (replace) {
            items.clear();
            for (Entry e : incoming) {
                e.id = nextId++;
                normalize(e, true);
                items.add(e);
            }
            r.added = incoming.size();
            save();
            return r;
        }

        HashMap<String, Entry> byUid = new HashMap<>();
        for (Entry e : items) {
            if (e.uid != null && !e.uid.isEmpty()) {
                byUid.put(e.uid, e);
            }
        }
        for (Entry in : incoming) {
            Entry local = byUid.get(in.uid);
            if (local == null) {
                in.id = nextId++;
                normalize(in, true);
                items.add(in);
                byUid.put(in.uid, in);
                r.added++;
            } else if (in.updatedAt > local.updatedAt) {
                Entry copy = in.copy();
                copy.id = local.id;
                copy.uid = local.uid;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).id == local.id) {
                        items.set(i, copy);
                        break;
                    }
                }
                byUid.put(copy.uid, copy);
                r.updated++;
            } else {
                r.skipped++;
            }
        }
        save();
        return r;
    }

    // ───────────────────── 内部 ─────────────────────

    private void normalize(Entry e, boolean isNew) {
        long now = System.currentTimeMillis();
        if (e.uid == null || e.uid.isEmpty()) {
            e.uid = UUID.randomUUID().toString();
        }
        if (e.date == null || e.date.isEmpty()) {
            e.date = Entry.today();
        }
        if (e.createdAt == 0) {
            e.createdAt = now;
        }
        if (isNew || e.updatedAt == 0) {
            e.updatedAt = now;
        }
    }

    private void load() {
        items.clear();
        nextId = 1;
        File f = file;
        if (!f.exists()) {
            // 主文件没了但备份还在（例如写一半被打断），用备份救回来
            File bak = new File(f.getParentFile(), FILE_NAME + ".bak");
            if (bak.exists()) {
                f = bak;
            } else {
                seed();
                return;
            }
        }
        try {
            String raw = readAll(f);
            if (raw == null || raw.trim().isEmpty()) {
                throw new Exception("empty");
            }
            JSONObject root = new JSONObject(raw);
            nextId = root.optLong("nextId", 1);
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Entry e = Entry.fromJson(o);
                    if (e.id <= 0) {
                        e.id = nextId++;
                    }
                    if (e.id >= nextId) {
                        nextId = e.id + 1;
                    }
                    items.add(e);
                }
            }
        } catch (Exception broken) {
            // 主文件读坏了，退回备份再试一次
            File bak = new File(file.getParentFile(), FILE_NAME + ".bak");
            if (bak.exists() && !bak.equals(f)) {
                try {
                    items.clear();
                    JSONObject root = new JSONObject(readAll(bak));
                    JSONArray arr = root.optJSONArray("items");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.optJSONObject(i);
                            if (o != null) {
                                Entry e = Entry.fromJson(o);
                                if (e.id <= 0) {
                                    e.id = nextId++;
                                }
                                items.add(e);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 首次使用时放几笔示例，让「记账」一进去就有东西看。 */
    private void seed() {
        long now = System.currentTimeMillis();
        String today = Entry.today();
        String yesterday = Entry.dayOf(java.time.LocalDate.now().minusDays(1));
        String lastMonth = Entry.dayOf(java.time.LocalDate.now().minusMonths(1).withDayOfMonth(15));

        Entry a = new Entry(today, Entry.EXPENSE, 12.5, "日常餐饮", "午餐");
        a.note = "食堂";
        a.platform = "校园卡";
        items.add(a);

        Entry b = new Entry(today, Entry.EXPENSE, 3.0, "交通出行", "公交地铁");
        b.platform = "微信";
        items.add(b);

        Entry c = new Entry(yesterday, Entry.EXPENSE, 45.0, "购物消费", "书籍文具");
        c.note = "专业书";
        c.important = true;
        c.platform = "支付宝";
        items.add(c);

        Entry d = new Entry(Entry.dayOf(java.time.LocalDate.now().withDayOfMonth(1)),
                Entry.INCOME, 1500.0, "校园收入", "家人生活费");
        d.platform = "银行卡";
        items.add(d);

        Entry e = new Entry(lastMonth, Entry.EXPENSE, 30.0, "日常餐饮", "外卖");
        e.platform = "微信";
        items.add(e);

        for (Entry x : items) {
            normalize(x, true);
            x.createdAt = now;
            x.updatedAt = now;
            x.id = nextId++;
        }
        save();
    }

    public synchronized void save() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            root.put("nextId", nextId);
            for (Entry e : items) {
                arr.put(e.toJson());
            }
            root.put("items", arr);
            byte[] data = root.toString().getBytes(UTF8);
            // 写之前先把上一版留成 .bak：万一新文件写坏，还能退回去
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
                // 忽略
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

    private static void copyFile(File from, File to) {
        try (RandomAccessFile in = new RandomAccessFile(from, "r");
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }
}
