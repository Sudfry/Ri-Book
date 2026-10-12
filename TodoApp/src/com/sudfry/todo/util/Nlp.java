package com.sudfry.todo.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言解析：从一句中文里认出时间、优先级，剩下的当作标题。
 *
 * 只做「大家习惯的那几种说法」，行为可预期、认不出就原样保留标题：
 *   明天下午3点开会  → 标题「开会」，10/12 15:00
 *   周五交周报 ！     → 标题「交周报」，本周五，高优先级
 *   10月20日 体检     → 标题「体检」，10/20
 *
 * 认出来的东西会显示在编辑页上，保存前你就能看见，认错了可以自己改。
 */
public final class Nlp {

    public static class Result {
        public String title = "";
        public LocalDate date;
        public boolean hasTime;
        public int hour = -1;
        public int minute = 0;
        public int priority;
        /** 给人看的一句话，例如「明天 15:00」。 */
        public String human;

        public boolean isEmpty() {
            return date == null && !hasTime && priority == 0;
        }
    }

    private static final Pattern P_DAY = Pattern.compile("(大后天|后天|明天|明日|今天|今日|今晚)");
    private static final Pattern P_WEEK = Pattern.compile("(下下|下|这|本)?\\s*(周|星期|礼拜)\\s*([一二三四五六日天末])");
    private static final Pattern P_MD = Pattern.compile("(\\d{1,2})\\s*[月/]\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern P_TIME = Pattern.compile(
            "(上午|早上|早晨|中午|下午|傍晚|晚上|夜里|凌晨)?\\s*(\\d{1,2})\\s*[点时:：]\\s*(半|\\d{1,2})?\\s*分?");
    private static final Pattern P_PRI = Pattern.compile("(非常重要|很重要|重要|紧急|加急|!!+|!!!|!!|!|！+)");

    private Nlp() {
    }

    public static Result parse(String raw) {
        return parse(raw, LocalDate.now(), java.time.LocalTime.now().getHour());
    }

    /**
     * @param today   今天（方便测试注入）
     * @param nowHour 现在几点（判断「3点」该算凌晨还是下午）
     */
    public static Result parse(String raw, LocalDate today, int nowHour) {
        Result r = new Result();
        if (raw == null || raw.trim().isEmpty()) {
            r.title = "";
            return r;
        }
        char[] chars = raw.toCharArray();
        boolean[] eaten = new boolean[chars.length];
        StringBuilder human = new StringBuilder();

        Matcher m;

        // ── 日期：今天/明天/后天 ──
        m = P_DAY.matcher(raw);
        if (m.find()) {
            String w = m.group(1);
            switch (w) {
                case "今天":
                case "今日":
                    r.date = today;
                    break;
                case "明天":
                case "明日":
                    r.date = today.plusDays(1);
                    break;
                case "后天":
                    r.date = today.plusDays(2);
                    break;
                case "大后天":
                    r.date = today.plusDays(3);
                    break;
                case "今晚":
                    r.date = today;
                    r.hasTime = true;
                    r.hour = 20;
                    break;
                default:
                    break;
            }
            eat(eaten, m.start(), m.end());
        }

        // ── 日期：周几 ──
        if (r.date == null) {
            m = P_WEEK.matcher(raw);
            if (m.find()) {
                String pre = m.group(1) == null ? "" : m.group(1);
                LocalDate base = weekDay(today, m.group(3));
                if ("下".equals(pre)) {
                    base = base.plusWeeks(1);
                } else if ("下下".equals(pre)) {
                    base = base.plusWeeks(2);
                } else if (base.isBefore(today)) {
                    // 没写「下周」但那天已经过了 → 按下一个来（和常见待办应用一致）
                    base = base.plusWeeks(1);
                }
                r.date = base;
                eat(eaten, m.start(), m.end());
            }
        }

        // ── 日期：10月20日 / 10/20 ──
        if (r.date == null) {
            m = P_MD.matcher(raw);
            if (m.find()) {
                int mo = Integer.parseInt(m.group(1));
                int day = Integer.parseInt(m.group(2));
                if (mo >= 1 && mo <= 12 && day >= 1 && day <= 31) {
                    LocalDate d = safeDate(today.getYear(), mo, day);
                    if (d != null) {
                        if (d.isBefore(today)) {
                            LocalDate nx = safeDate(today.getYear() + 1, mo, day);
                            if (nx != null) {
                                d = nx;
                            }
                        }
                        r.date = d;
                        eat(eaten, m.start(), m.end());
                    }
                }
            }
        }

        // ── 时间：下午3点半 ──
        m = P_TIME.matcher(raw);
        if (m.find()) {
            String period = m.group(1);
            int h = Integer.parseInt(m.group(2));
            int min = 0;
            String mm = m.group(3);
            if (mm != null) {
                min = "半".equals(mm) ? 30 : Integer.parseInt(mm);
            }
            if (h <= 24 && min < 60) {
                h = applyPeriod(h, period, nowHour);
                r.hasTime = true;
                r.hour = Math.min(23, Math.max(0, h));
                r.minute = min;
                eat(eaten, m.start(), m.end());
            }
        }

        // ── 优先级 ──
        m = P_PRI.matcher(raw);
        if (m.find()) {
            String w = m.group(1);
            r.priority = (w.contains("非常") || w.contains("很") || w.startsWith("!")
                    || w.startsWith("！")) ? 3 : 2;
            eat(eaten, m.start(), m.end());
        }

        // ── 标题 = 去掉吃掉的部分 ──
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            sb.append(eaten[i] ? ' ' : chars[i]);
        }
        r.title = sb.toString().replaceAll("\\s{2,}", " ").trim();

        // ── 给人看的说明 ──
        if (r.date != null) {
            human.append(dayWord(r.date, today));
            if (r.hasTime) {
                human.append(' ');
            }
        }
        if (r.hasTime) {
            human.append(String.format(java.util.Locale.US, "%02d:%02d", r.hour, r.minute));
        }
        if (r.priority > 0) {
            if (human.length() > 0) {
                human.append(" · ");
            }
            human.append(r.priority >= 3 ? "最高优先级" : "高优先级");
        }
        r.human = human.toString();
        return r;
    }

    /** 说明里优先用「明天/后天」这种相对说法，更好懂。 */
    private static String dayWord(LocalDate d, LocalDate today) {
        long gap = java.time.temporal.ChronoUnit.DAYS.between(today, d);
        if (gap == 0) {
            return "今天";
        }
        if (gap == 1) {
            return "明天";
        }
        if (gap == 2) {
            return "后天";
        }
        if (gap == -1) {
            return "昨天";
        }
        if (gap > 2 && gap < 7) {
            return "本周" + wd(d);
        }
        return (d.getMonthValue()) + "月" + d.getDayOfMonth() + "日 " + wd(d);
    }

    private static String wd(LocalDate d) {
        String[] w = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return w[d.getDayOfWeek().getValue() - 1];
    }

    private static void eat(boolean[] eaten, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(eaten.length, to); i++) {
            eaten[i] = true;
        }
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (Exception e) {
            return null;
        }
    }

    /** 本周的某个星期几（周一是第一天，和界面上一致）。 */
    private static LocalDate weekDay(LocalDate today, String cn) {
        int target;
        switch (cn) {
            case "一":
                target = 1;
                break;
            case "二":
                target = 2;
                break;
            case "三":
                target = 3;
                break;
            case "四":
                target = 4;
                break;
            case "五":
                target = 5;
                break;
            case "六":
                target = 6;
                break;
            default:
                target = 7;   // 日 / 天 / 末
                break;
        }
        LocalDate monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.plusDays(target - 1);
    }

    /**
     * 上午/下午这些词怎么改小时。
     * 没写前缀的「3点」：如果现在已经过了早上，就当成下午 3 点 —— 这是多数待办应用的做法，
     * 而且识别结果会显示出来，看错了随时能改。
     */
    private static int applyPeriod(int h, String period, int nowHour) {
        if (period == null) {
            if (h >= 1 && h <= 8 && nowHour >= 9) {
                return h + 12;
            }
            return h;
        }
        switch (period) {
            case "上午":
            case "早上":
            case "早晨":
                return h == 12 ? 0 : h;
            case "中午":
                return h < 12 ? 12 : h;
            case "下午":
            case "傍晚":
                return h < 12 ? h + 12 : h;
            case "晚上":
            case "夜里":
                if (h < 12) {
                    return h + 12;
                }
                return h;
            case "凌晨":
                return h == 12 ? 0 : h;
            default:
                return h;
        }
    }
}
