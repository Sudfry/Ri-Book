package com.sudfry.todo.store;

import android.content.Context;

import com.sudfry.todo.model.Categories;
import com.sudfry.todo.util.AppPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 整包备份：任务 + 账目 + 预算设置，一份 JSON 走遍所有传输方式
 * （文件 / 剪贴板 / 局域网 / iOS 网页版）。
 */
public final class Backup {

    public static final String APP_TAG = "sudfry-todo";
    public static final int VERSION = 3;

    private Backup() {
    }

    public static class Result {
        public TodoStore.MergeResult tasks = new TodoStore.MergeResult();
        public TodoStore.MergeResult entries = new TodoStore.MergeResult();
        public boolean replace;

        public String describe() {
            String head = replace ? "已用对方数据覆盖本机：" : "";
            return head + "任务 " + tasks.added + " 新增/" + tasks.updated + " 更新，账目 "
                    + entries.added + " 新增/" + entries.updated + " 更新";
        }

        public String detail() {
            return "任务：新增 " + tasks.added + "、更新 " + tasks.updated + "、无变化 " + tasks.skipped
                    + "\n账目：新增 " + entries.added + "、更新 " + entries.updated + "、无变化 " + entries.skipped;
        }
    }

    /** 组装完整备份（格式对 Android 与 iOS 网页版一致）。 */
    public static String export(Context c, String deviceName) {
        TodoStore todos = TodoStore.get(c);
        EntryStore entries = EntryStore.get(c);
        AppPrefs p = AppPrefs.get(c);
        try {
            JSONObject root = new JSONObject();
            root.put("app", APP_TAG);
            root.put("version", VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("device", deviceName == null ? "" : deviceName);
            root.put("items", todos.exportArray());
            root.put("entries", entries.exportArray());
            root.put("budget", p.dailyBudget());
            root.put("livingFee", p.livingFee());
            JSONArray cats = new JSONArray();
            for (String s : p.settleCats()) {
                cats.put(s);
            }
            root.put("settleCats", cats);
            JSONObject theme = new JSONObject();
            theme.put("hue", p.hue());
            theme.put("sat", p.sat());
            theme.put("dark", p.themeMode() == 2);
            root.put("theme", theme);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 导入 / 合并。replace=true 时先清空再导入；否则按 uid + updatedAt 合并。
     */
    public static Result importJson(Context c, String raw, boolean replace) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new Exception("内容是空的");
        }
        JSONObject root;
        if (text.startsWith("[")) {
            root = new JSONObject();
            root.put("items", new JSONArray(text));
        } else {
            root = new JSONObject(text);
        }
        JSONArray items = root.optJSONArray("items");
        JSONArray entries = root.optJSONArray("entries");
        if (items == null && entries == null) {
            throw new Exception("数据里既没有任务也没有账目");
        }

        Result r = new Result();
        r.replace = replace;
        TodoStore todos = TodoStore.get(c);
        EntryStore entryStore = EntryStore.get(c);

        if (items != null) {
            r.tasks = todos.mergeArray(items, replace);
        }
        if (entries != null) {
            r.entries = entryStore.mergeArray(entries, replace);
        }

        // 预算 / 生活费 / 结算分类：对方带了就一并接受（同一个人的多设备）
        AppPrefs p = AppPrefs.get(c);
        if (root.has("budget")) {
            p.setDailyBudget(root.optDouble("budget", p.dailyBudget()));
        }
        if (root.has("livingFee")) {
            p.setLivingFee(root.optDouble("livingFee", p.livingFee()));
        }
        JSONArray cats = root.optJSONArray("settleCats");
        if (cats != null && cats.length() > 0) {
            ArrayList<String> list = new ArrayList<>();
            for (int i = 0; i < cats.length(); i++) {
                String s = cats.optString(i, "");
                if (!s.isEmpty() && !list.contains(s)) {
                    list.add(s);
                }
            }
            if (!list.isEmpty()) {
                p.setSettleCats(list);
            }
        }
        JSONObject theme = root.optJSONObject("theme");
        if (theme != null && !replace) {
            // 合并模式下不覆盖本机外观，避免同步一次颜色就变了
        } else if (theme != null) {
            p.setAccent(theme.optInt("hue", p.hue()), theme.optInt("sat", p.sat()));
        }
        return r;
    }

    /** 兼容：只含任务数组的旧备份。 */
    public static List<String> allSettleSubs() {
        return Categories.allExpenseSubs();
    }
}
