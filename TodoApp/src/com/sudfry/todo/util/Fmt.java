package com.sudfry.todo.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/** 中文日期时间格式化。 */
public final class Fmt {

    /** DayOfWeek 从 1=周一 开始，与中文习惯一致。 */
    public static final String[] WD = {"一", "二", "三", "四", "五", "六", "日"};

    private Fmt() {
    }

    public static String wd(LocalDate d) {
        return "周" + WD[d.getDayOfWeek().getValue() - 1];
    }

    public static String date(LocalDate d) {
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    public static String date(long ms) {
        return date(com.sudfry.todo.model.Todo.toLocal(ms).toLocalDate());
    }

    public static String dateFull(long ms) {
        LocalDate d = com.sudfry.todo.model.Todo.toLocal(ms).toLocalDate();
        return d.getYear() + "年" + date(d) + " " + wd(d);
    }

    public static String dateShort(long ms) {
        LocalDate d = com.sudfry.todo.model.Todo.toLocal(ms).toLocalDate();
        return d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    public static String hm(int minutes) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    public static String time(long ms) {
        LocalDateTime dt = com.sudfry.todo.model.Todo.toLocal(ms);
        return hm(dt.getHour() * 60 + dt.getMinute());
    }

    /** 今天 / 明天 / 昨天 / 10月15日 */
    public static String dayLabel(LocalDate d, LocalDate today) {
        long diff = java.time.temporal.ChronoUnit.DAYS.between(today, d);
        if (diff == 0) {
            return "今天";
        }
        if (diff == 1) {
            return "明天";
        }
        if (diff == -1) {
            return "昨天";
        }
        return date(d);
    }

    /** 2026年10月 */
    public static String monthTitle(YearMonth ym) {
        return ym.getYear() + "年" + ym.getMonthValue() + "月";
    }
}
