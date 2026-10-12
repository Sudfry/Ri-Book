package com.sudfry.todo.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

/** 一条待办。due 用 epoch 毫秒表示，0 表示「无期限」。 */
public class Todo {

    public static final int PRI_LOW = 0;
    public static final int PRI_MID = 1;
    public static final int PRI_HIGH = 2;
    public static final int PRI_URGENT = 3;

    public static final int REPEAT_NONE = 0;
    public static final int REPEAT_DAILY = 1;
    public static final int REPEAT_WEEKLY = 2;
    public static final int REPEAT_MONTHLY = 3;

    public long id;
    /** 跨设备稳定标识：同一台设备上导出再导入到另一台，靠它认出「是同一条」。 */
    public String uid = "";
    public String title = "";
    public String note = "";
    public String tag = "";
    /** 截止时间，epoch 毫秒。0 表示没有期限。 */
    public long due;
    /** 是否指定了具体时刻（否则当天任意时间）。 */
    public boolean hasTime;
    /** 预计耗时（分钟），只在时间轴里影响色块高度。 */
    public int durationMin = 60;
    public int priority = PRI_MID;
    public int repeat = REPEAT_NONE;
    /** 提前多少分钟提醒，-1 表示不提醒。 */
    public int remindBefore = 10;
    public boolean done;
    public long doneAt;
    public long createdAt;
    /** 最后一次修改时间，多设备合并时「谁新听谁的」。 */
    public long updatedAt;
    /** 子任务（清单步骤）。 */
    public final ArrayList<Subtask> subtasks = new ArrayList<>();
    /** 累计专注分钟数（番茄钟）。 */
    public int spentMin;

    /** 一条子任务。 */
    public static class Subtask {
        public long id;
        public String title = "";
        public boolean done;

        public Subtask() {
        }

        public Subtask(long id, String title) {
            this.id = id;
            this.title = title;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("title", title == null ? "" : title);
                o.put("done", done);
            } catch (JSONException ignored) {
            }
            return o;
        }

        public static Subtask fromJson(JSONObject o) {
            Subtask s = new Subtask();
            s.id = o.optLong("id");
            s.title = o.optString("title", "");
            s.done = o.optBoolean("done");
            return s;
        }
    }

    public int subTotal() {
        return subtasks.size();
    }

    public int subDone() {
        int n = 0;
        for (Subtask s : subtasks) {
            if (s.done) {
                n++;
            }
        }
        return n;
    }

    public void addSub(String title) {
        if (title == null || title.trim().isEmpty()) {
            return;
        }
        long maxId = 0;
        for (Subtask s : subtasks) {
            maxId = Math.max(maxId, s.id);
        }
        subtasks.add(new Subtask(maxId + 1, title.trim()));
    }

    public void removeSub(long subId) {
        for (int i = subtasks.size() - 1; i >= 0; i--) {
            if (subtasks.get(i).id == subId) {
                subtasks.remove(i);
            }
        }
    }

    public void toggleSub(long subId, boolean done) {
        for (Subtask s : subtasks) {
            if (s.id == subId) {
                s.done = done;
            }
        }
    }

    public Todo() {
    }

    public Todo(String title, long due, boolean hasTime) {
        this.title = title;
        this.due = due;
        this.hasTime = hasTime;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public Todo copy() {
        Todo t = new Todo();
        t.id = id;
        t.uid = uid;
        t.title = title;
        t.note = note;
        t.tag = tag;
        t.due = due;
        t.hasTime = hasTime;
        t.durationMin = durationMin;
        t.priority = priority;
        t.repeat = repeat;
        t.remindBefore = remindBefore;
        t.done = done;
        t.doneAt = doneAt;
        t.createdAt = createdAt;
        t.updatedAt = updatedAt;
        t.subtasks.clear();
        for (Subtask s : subtasks) {
            Subtask c = new Subtask(s.id, s.title);
            c.done = s.done;
            t.subtasks.add(c);
        }
        t.spentMin = spentMin;
        return t;
    }

    /** 内容指纹：万一对方没有 uid（旧版本导出的数据），用它兜底判重。 */
    public String signature() {
        return (title == null ? "" : title.trim()) + "|" + due + "|" + (hasTime ? 1 : 0);
    }

    public boolean isRepeating() {
        return repeat != REPEAT_NONE;
    }

    /** 重复任务下一次的截止时间。 */
    public long nextDue() {
        if (due <= 0) {
            return 0;
        }
        LocalDateTime dt = toLocal(due);
        switch (repeat) {
            case REPEAT_DAILY:
                dt = dt.plusDays(1);
                break;
            case REPEAT_WEEKLY:
                dt = dt.plusWeeks(1);
                break;
            case REPEAT_MONTHLY:
                dt = dt.plusMonths(1);
                break;
            default:
                return 0;
        }
        return toMillis(dt);
    }

    public static LocalDateTime toLocal(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    public static long toMillis(LocalDateTime dt) {
        return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public static String repeatName(int r) {
        switch (r) {
            case REPEAT_DAILY:
                return "每天";
            case REPEAT_WEEKLY:
                return "每周";
            case REPEAT_MONTHLY:
                return "每月";
            default:
                return "不重复";
        }
    }

    public static String priorityName(int p) {
        switch (p) {
            case PRI_URGENT:
                return "紧急";
            case PRI_HIGH:
                return "高";
            case PRI_MID:
                return "中";
            default:
                return "低";
        }
    }

    public static String remindName(int m) {
        if (m < 0) {
            return "不提醒";
        }
        if (m == 0) {
            return "准时";
        }
        if (m < 60) {
            return m + " 分钟前";
        }
        if (m == 60) {
            return "1 小时前";
        }
        if (m < 1440) {
            return (m / 60) + " 小时前";
        }
        return (m / 1440) + " 天前";
    }

    /** 距离截止还有多少分钟（负数表示已逾期）。 */
    public long minutesLeft(long now) {
        if (due <= 0) {
            return Long.MAX_VALUE;
        }
        return ChronoUnit.MINUTES.between(toLocal(now), toLocal(due));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("uid", uid == null ? "" : uid);
            o.put("title", title == null ? "" : title);
            o.put("note", note == null ? "" : note);
            o.put("tag", tag == null ? "" : tag);
            o.put("due", due);
            o.put("hasTime", hasTime);
            o.put("durationMin", durationMin);
            o.put("priority", priority);
            o.put("repeat", repeat);
            o.put("remindBefore", remindBefore);
            o.put("done", done);
            o.put("doneAt", doneAt);
            o.put("createdAt", createdAt);
            o.put("updatedAt", updatedAt);
            o.put("spentMin", spentMin);
            if (!subtasks.isEmpty()) {
                JSONArray subs = new JSONArray();
                for (Subtask s : subtasks) {
                    subs.put(s.toJson());
                }
                o.put("subtasks", subs);
            }
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Todo fromJson(JSONObject o) {
        Todo t = new Todo();
        t.id = o.optLong("id");
        t.uid = o.optString("uid", "");
        t.title = o.optString("title", "");
        t.note = o.optString("note", "");
        t.tag = o.optString("tag", "");
        t.due = o.optLong("due");
        t.hasTime = o.optBoolean("hasTime");
        t.durationMin = o.optInt("durationMin", 60);
        t.priority = o.optInt("priority", PRI_MID);
        t.repeat = o.optInt("repeat", REPEAT_NONE);
        t.remindBefore = o.optInt("remindBefore", 10);
        t.done = o.optBoolean("done");
        t.doneAt = o.optLong("doneAt");
        t.createdAt = o.optLong("createdAt");
        t.updatedAt = o.optLong("updatedAt", t.createdAt);
        t.spentMin = o.optInt("spentMin", 0);
        t.subtasks.clear();
        JSONArray subs = o.optJSONArray("subtasks");
        if (subs != null) {
            for (int i = 0; i < subs.length(); i++) {
                JSONObject so = subs.optJSONObject(i);
                if (so != null) {
                    t.subtasks.add(Subtask.fromJson(so));
                }
            }
        }
        return t;
    }
}
