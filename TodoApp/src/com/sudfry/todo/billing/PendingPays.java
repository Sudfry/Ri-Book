package com.sudfry.todo.billing;

import android.content.Context;

import com.sudfry.todo.model.Entry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * 「来自支付通知」的待确认记录。
 *
 * 通知里只能拿到一段文本（例如「微信支付 向 XX 付款 ¥12.50」），
 * 金额和方向能猜出来，分类猜不准，所以不直接写进账本，
 * 先放在这里等用户点确认 —— 自动记账也要能对得上账。
 */
public final class PendingPays {

    private static final String FILE_NAME = "pending_pays.json";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX = 200;

    private PendingPays() {
    }

    public static class Item {
        public String uid = "";
        public String platform = "";
        public String date = "";
        public int type = Entry.EXPENSE;
        public double amount;
        public String title = "";
        public String text = "";
        public String packageName = "";
        public long at;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE_NAME);
    }

    public static synchronized ArrayList<Item> all(Context c) {
        ArrayList<Item> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) {
            return out;
        }
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            JSONArray arr = new JSONObject(new String(buf, UTF8)).optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        out.add(fromJson(o));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized int count(Context c) {
        return all(c).size();
    }

    /** 返回 true 表示确实新增了（不是重复通知）。 */
    public static synchronized boolean add(Context c, Item it) {
        ArrayList<Item> list = all(c);
        for (Item x : list) {
            if (x.uid.equals(it.uid)) {
                return false;
            }
        }
        list.add(0, it);
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        save(c, list);
        return true;
    }

    public static synchronized void remove(Context c, String uid) {
        ArrayList<Item> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).uid.equals(uid)) {
                list.remove(i);
            }
        }
        save(c, list);
    }

    public static synchronized void clear(Context c) {
        save(c, new ArrayList<>());
    }

    private static void save(Context c, ArrayList<Item> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : list) {
                arr.put(toJson(it));
            }
            JSONObject root = new JSONObject();
            root.put("items", arr);
            File f = file(c);
            File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(root.toString().getBytes(UTF8));
                fos.flush();
                fos.getFD().sync();
            }
            if (f.exists() && !f.delete()) {
                // 忽略
            }
            if (!tmp.renameTo(f)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(root.toString().getBytes(UTF8));
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static JSONObject toJson(Item it) {
        JSONObject o = new JSONObject();
        try {
            o.put("uid", it.uid);
            o.put("platform", it.platform);
            o.put("date", it.date);
            o.put("type", it.type == Entry.INCOME ? "income" : "expense");
            o.put("amount", it.amount);
            o.put("title", it.title);
            o.put("text", it.text);
            o.put("package", it.packageName);
            o.put("at", it.at);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Item fromJson(JSONObject o) {
        Item it = new Item();
        it.uid = o.optString("uid", "");
        it.platform = o.optString("platform", "");
        it.date = o.optString("date", "");
        it.type = "income".equals(o.optString("type")) ? Entry.INCOME : Entry.EXPENSE;
        it.amount = o.optDouble("amount", 0);
        it.title = o.optString("title", "");
        it.text = o.optString("text", "");
        it.packageName = o.optString("package", "");
        it.at = o.optLong("at");
        return it;
    }

    /** 转成正式账目。 */
    public static Entry toEntry(Item it) {
        String[] cat = CategoryRules.classify(it.type, "", it.title, it.text);
        Entry e = new Entry(it.date == null || it.date.isEmpty() ? Entry.today() : it.date,
                it.type, it.amount, cat[0], cat[1]);
        e.uid = "pay:" + it.uid;
        e.platform = it.platform;
        String note = it.text == null ? "" : it.text.replace('\n', ' ').trim();
        e.note = note.length() > 60 ? note.substring(0, 60) : note;
        return e;
    }
}
