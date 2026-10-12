package com.sudfry.todo.util;

import com.sudfry.todo.R;
import com.sudfry.todo.model.Todo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 紧急程度模型 —— 整个应用「一眼看出哪件事最急」的核心。
 *
 * level 是 5 档配色分级，列表、日历小条、时间轴色块全部使用它：
 *   0 已逾期（红） 1 今天（橙） 2 临近（黄） 3 以后（绿） 4 无期限（灰）
 */
public final class Urgency {

    public static final int OVERDUE = 0;
    public static final int TODAY = 1;
    public static final int SOON = 2;
    public static final int LATER = 3;
    public static final int NONE = 4;

    public static final String[] LEVEL_NAME = {"已逾期", "今天", "临近", "以后", "无期限"};

    private Urgency() {
    }

    private static final int[] COLOR_RES = {
            R.color.overdue, R.color.today, R.color.soon, R.color.later, R.color.none
    };

    public static int colorRes(int level) {
        if (level < 0 || level >= COLOR_RES.length) {
            return R.color.none;
        }
        return COLOR_RES[level];
    }

    public static int level(Todo t, long now) {
        if (t.due <= 0) {
            return NONE;
        }
        LocalDateTime dueAt = Todo.toLocal(t.due);
        LocalDate dueDay = dueAt.toLocalDate();
        LocalDate today = Todo.toLocal(now).toLocalDate();
        if (dueDay.isBefore(today)) {
            return OVERDUE;
        }
        // 当天但具体时刻已经过去 → 也算逾期
        if (t.hasTime && t.due < now && dueDay.isEqual(today)) {
            return OVERDUE;
        }
        if (dueDay.isEqual(today)) {
            return TODAY;
        }
        long days = ChronoUnit.DAYS.between(today, dueDay);
        return days <= 2 ? SOON : LATER;
    }

    /** 列表分组用的更细分档。 */
    public static final int B_OVERDUE = 0;
    public static final int B_TODAY = 1;
    public static final int B_TOMORROW = 2;
    public static final int B_WEEK = 3;
    public static final int B_LATER = 4;
    public static final int B_NONE = 5;
    public static final int B_DONE = 6;

    public static final String[] BUCKET_NAME = {
            "已逾期", "今天", "明天", "3 天内", "以后", "无期限", "已完成"
    };

    public static int bucket(Todo t, long now) {
        if (t.done) {
            return B_DONE;
        }
        int lv = level(t, now);
        if (lv == OVERDUE) {
            return B_OVERDUE;
        }
        if (lv == TODAY) {
            return B_TODAY;
        }
        if (lv == NONE) {
            return B_NONE;
        }
        long days = ChronoUnit.DAYS.between(Todo.toLocal(now).toLocalDate(),
                Todo.toLocal(t.due).toLocalDate());
        if (days == 1) {
            return B_TOMORROW;
        }
        if (days <= 3) {
            return B_WEEK;
        }
        return B_LATER;
    }

    // ───────────────── 四象限（重要 / 紧急） ─────────────────

    /** 重要：优先级为「高」或「紧急」。 */
    public static boolean isImportant(Todo t) {
        return t.priority >= Todo.PRI_HIGH;
    }

    /** 紧急：已经逾期，或今天到期。 */
    public static boolean isUrgent(Todo t, long now) {
        int lv = level(t, now);
        return lv == OVERDUE || lv == TODAY;
    }

    /** 0 重要且紧急 / 1 重要不紧急 / 2 紧急不重要 / 3 都不 */
    public static int quadrant(Todo t, long now) {
        boolean imp = isImportant(t);
        boolean urg = isUrgent(t, now);
        if (imp && urg) {
            return 0;
        }
        if (imp) {
            return 1;
        }
        if (urg) {
            return 2;
        }
        return 3;
    }

    public static final String[] QUADRANT_NAME = {
            "重要且紧急 · 马上做",
            "重要不紧急 · 计划做",
            "紧急不重要 · 尽快做",
            "不重要不紧急 · 有空再说",
    };

    public static final String[] QUADRANT_HINT = {
            "今天到期或已逾期，且优先级高",
            "优先级高但还有时间 —— 别拖进第一格",
            "今天到期但优先级不高，能委托就委托",
            "既不急也不重要，闲下来再说",
    };

    /** 排序键：未完成在前 → 逾期最前 → 优先级高在前 → 时间早在前。 */
    public static int compare(Todo a, Todo b, long now) {
        if (a.done != b.done) {
            return a.done ? 1 : -1;
        }
        if (a.done) {
            return Long.compare(b.doneAt, a.doneAt);
        }
        int la = level(a, now);
        int lb = level(b, now);
        if (la != lb) {
            return la - lb;
        }
        if (a.priority != b.priority) {
            return b.priority - a.priority;
        }
        if (a.due == 0 && b.due != 0) {
            return 1;
        }
        if (b.due == 0 && a.due != 0) {
            return -1;
        }
        if (a.due != b.due) {
            return Long.compare(a.due, b.due);
        }
        return Long.compare(a.createdAt, b.createdAt);
    }

    /** 剩余时间的人话描述，这是「紧迫感」的第二重表达。 */
    public static String remaining(Todo t, long now) {
        if (t.due <= 0) {
            return "没有截止时间";
        }
        LocalDateTime dueAt = Todo.toLocal(t.due);
        LocalDateTime nowAt = Todo.toLocal(now);
        long mins = ChronoUnit.MINUTES.between(nowAt, dueAt);

        LocalDate dueDay = dueAt.toLocalDate();
        LocalDate today = nowAt.toLocalDate();
        long days = ChronoUnit.DAYS.between(today, dueDay);

        // 只指定了日期、没有具体时刻的任务，不该按「还剩几分」来报数
        if (!t.hasTime) {
            if (days < 0) {
                return "已逾期 " + (-days) + " 天";
            }
            if (days == 0) {
                return "今天内完成";
            }
            if (days == 1) {
                return "明天完成";
            }
            if (days <= 7) {
                return days + " 天内完成";
            }
            return Fmt.date(t.due) + "完成";
        }

        if (mins < 0) {
            long overdue = -mins;
            if (overdue < 60) {
                return "已逾期 " + overdue + " 分钟";
            }
            if (overdue < 60 * 24) {
                return "已逾期 " + (overdue / 60) + " 小时";
            }
            return "已逾期 " + (overdue / (60 * 24)) + " 天";
        }
        if (mins < 60) {
            return "还剩 " + mins + " 分钟";
        }
        if (days == 0) {
            long h = mins / 60;
            long m = mins % 60;
            return m == 0 ? ("还剩 " + h + " 小时") : ("还剩 " + h + " 小时 " + m + " 分");
        }
        if (days == 1) {
            return "明天到期";
        }
        if (days <= 7) {
            return days + " 天后到期";
        }
        return Fmt.date(t.due) + "到期";
    }

    /** 时间轴 / 日历上的短标签。 */
    public static String shortRemaining(Todo t, long now) {
        if (t.due <= 0) {
            return "无期限";
        }
        LocalDate dueDay = Todo.toLocal(t.due).toLocalDate();
        LocalDate today = Todo.toLocal(now).toLocalDate();
        long days = ChronoUnit.DAYS.between(today, dueDay);
        if (t.due < now) {
            return level(t, now) == OVERDUE && days <= 0 ? "已逾期" : "已逾期";
        }
        if (days == 0) {
            return "今天";
        }
        if (days == 1) {
            return "明天";
        }
        if (days <= 7) {
            return days + " 天后";
        }
        return Fmt.dateShort(t.due);
    }

    /** 小时段的辅助文本（今天 14:00 这种）。 */
    public static String atText(Todo t) {
        if (t.due <= 0) {
            return "";
        }
        LocalDateTime dt = Todo.toLocal(t.due);
        LocalTime tm = dt.toLocalTime();
        if (!t.hasTime) {
            return "全天";
        }
        return Fmt.hm(tm.getHour() * 60 + tm.getMinute());
    }
}
