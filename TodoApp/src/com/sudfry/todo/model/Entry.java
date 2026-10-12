package com.sudfry.todo.model;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 一条收支记录。 */
public class Entry {

    public static final int EXPENSE = 0;
    public static final int INCOME = 1;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public long id;
    /** 跨设备稳定标识，与任务一样用于合并。 */
    public String uid = "";
    /** yyyy-MM-dd */
    public String date = "";
    public int type = EXPENSE;
    public double amount;
    /** 大类 */
    public String category = "";
    /** 小类 */
    public String sub = "";
    public String note = "";
    /** 重点标记（星标） */
    public boolean important;
    public String platform = "银行卡";
    /** 导入批次：批量导入的账目共用同一个 id，方便整体撤销。 */
    public String batch = "";
    public long createdAt;
    public long updatedAt;

    public Entry() {
    }

    public Entry(String date, int type, double amount, String category, String sub) {
        this.date = date;
        this.type = type;
        this.amount = amount;
        this.category = category;
        this.sub = sub;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public static String today() {
        return LocalDate.now().format(DAY);
    }

    public static String dayOf(LocalDate d) {
        return d.format(DAY);
    }

    public static LocalDate parseDay(String s) {
        try {
            return LocalDate.parse(s, DAY);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    public Entry copy() {
        Entry e = new Entry();
        e.id = id;
        e.uid = uid;
        e.date = date;
        e.type = type;
        e.amount = amount;
        e.category = category;
        e.sub = sub;
        e.note = note;
        e.important = important;
        e.platform = platform;
        e.batch = batch;
        e.createdAt = createdAt;
        e.updatedAt = updatedAt;
        return e;
    }

    /** 一条记录在列表里显示的标题：小类优先，没有就用大类。 */
    public String label() {
        if (sub != null && !sub.isEmpty() && !"自定义".equals(sub)) {
            return sub;
        }
        return category == null || category.isEmpty() ? "未分类" : category;
    }

    public boolean isIncome() {
        return type == INCOME;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("uid", uid == null ? "" : uid);
            o.put("date", date == null ? "" : date);
            o.put("type", type == EXPENSE ? "expense" : "income");
            o.put("amount", amount);
            o.put("category", category == null ? "" : category);
            o.put("subcategory", sub == null ? "" : sub);
            o.put("note", note == null ? "" : note);
            o.put("important", important);
            o.put("platform", platform == null ? "" : platform);
            o.put("batch", batch == null ? "" : batch);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Entry fromJson(JSONObject o) {
        Entry e = new Entry();
        e.id = o.optLong("id");
        e.uid = o.optString("uid", "");
        e.date = o.optString("date", "");
        String t = o.optString("type", "expense");
        e.type = "income".equals(t) ? INCOME : EXPENSE;
        e.amount = o.optDouble("amount", 0);
        e.category = o.optString("category", "");
        e.sub = o.optString("subcategory", o.optString("sub", ""));
        e.note = o.optString("note", "");
        e.important = o.optBoolean("important");
        e.platform = o.optString("platform", "银行卡");
        e.batch = o.optString("batch", "");
        e.createdAt = o.optLong("createdAt");
        e.updatedAt = o.optLong("updatedAt", e.createdAt);
        if (e.uid == null || e.uid.isEmpty()) {
            e.uid = UUID.randomUUID().toString();
        }
        return e;
    }
}
