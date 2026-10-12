package com.sudfry.todo.util;

import java.text.DecimalFormat;

/** 金额格式化。 */
public final class Money {

    private static final DecimalFormat FMT = new DecimalFormat("#,##0.00");

    private Money() {
    }

    /** ¥1,234.56 */
    public static String yuan(double v) {
        return "¥" + FMT.format(v);
    }

    /** 带正负号：+¥1,234.56 / -¥12.00 */
    public static String signed(double v, boolean income) {
        return (income ? "+" : "-") + yuan(Math.abs(v));
    }

    /** 简洁写法：¥12 / ¥12.5 / ¥1,234.56 */
    public static String brief(double v) {
        if (v == Math.rint(v)) {
            return "¥" + new DecimalFormat("#,##0").format(v);
        }
        return yuan(v);
    }

    /** 解析用户输入，失败返回 -1。 */
    public static double parse(String s) {
        if (s == null) {
            return -1;
        }
        try {
            return Double.parseDouble(s.trim().replace("¥", "").replace(",", ""));
        } catch (Exception e) {
            return -1;
        }
    }
}
