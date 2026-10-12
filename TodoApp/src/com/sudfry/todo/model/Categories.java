package com.sudfry.todo.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 收支分类表（二级：大类 → 小类），与 ribook 的记账分类保持一致。 */
public final class Categories {

    private Categories() {
    }

    public static final LinkedHashMap<String, String[]> EXPENSE = new LinkedHashMap<>();
    public static final LinkedHashMap<String, String[]> INCOME = new LinkedHashMap<>();

    static {
        EXPENSE.put("日常餐饮", new String[]{"早餐", "午餐", "晚餐", "零食饮料", "外卖", "水果"});
        EXPENSE.put("交通出行", new String[]{"公交地铁", "打车", "加油充电", "火车机票", "共享单车"});
        EXPENSE.put("购物消费", new String[]{"日用品", "服饰", "数码电器", "书籍文具", "美妆护理"});
        EXPENSE.put("居住缴费", new String[]{"房租", "水电燃气", "物业", "通讯网络"});
        EXPENSE.put("校园生活", new String[]{"教材资料", "校园卡充值", "宿舍用品", "社团活动", "考试报名", "打印复印", "校园网"});
        EXPENSE.put("休闲与健康", new String[]{"娱乐", "电影演出", "旅行", "运动健身", "医疗", "宠物"});
        EXPENSE.put("人情与金融", new String[]{"人情往来", "红包礼金", "保险", "还款", "其他支出"});
        EXPENSE.put("自定义", new String[]{"自定义"});
    }

    static {
        INCOME.put("固定收入", new String[]{"工资", "奖金", "奖学金"});
        INCOME.put("校园收入", new String[]{"助学金", "勤工助学", "家人生活费", "兼职"});
        INCOME.put("其他收入", new String[]{"投资收益", "报销", "红包礼金", "退款", "其他收入"});
        INCOME.put("自定义", new String[]{"自定义"});
    }

    /** 预设的支付平台。 */
    public static final String[] PLATFORMS = {"银行卡", "微信", "支付宝", "现金", "校园卡", "其他"};

    public static Map<String, String[]> groups(int type) {
        return type == Entry.EXPENSE ? EXPENSE : INCOME;
    }

    public static List<String> bigNames(int type) {
        return new ArrayList<>(groups(type).keySet());
    }

    public static String[] subs(int type, String big) {
        String[] arr = groups(type).get(big);
        return arr == null ? new String[0] : arr;
    }

    /** 支出全部分类，用于结算页的勾选列表。 */
    public static List<String> allExpenseSubs() {
        ArrayList<String> out = new ArrayList<>();
        for (String[] arr : EXPENSE.values()) {
            for (String s : arr) {
                out.add(s);
            }
        }
        return out;
    }

    public static String typeName(int type) {
        return type == Entry.EXPENSE ? "支出" : "收入";
    }
}
