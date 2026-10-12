package com.sudfry.todo.notify;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.Log;

import com.sudfry.todo.billing.PendingPays;
import com.sudfry.todo.util.AppPrefs;
import com.sudfry.todo.model.Entry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过「通知使用权」读取支付宝 / 微信的支付通知，自动生成待确认账目。
 *
 * 为什么用通知而不用别的：
 *   - 支付宝/微信的账单数据在各自的应用私有目录里（微信还是加密库），第三方读不到；
 *   - 无障碍服务虽然能做到，但要读屏/模拟点击，既脆弱又把这些应用的屏幕内容全暴露了；
 *   - 通知使用权是系统给的正规接口，只需要用户手动开一次，不用 root。
 *
 * 代价是：只能拿到「开启之后」的新通知，历史记录要靠官方账单导入。
 */
public class PayNotifyService extends NotificationListenerService {

    private static final String TAG = "PayNotify";

    public static final String PKG_WECHAT = "com.tencent.mm";
    public static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";

    /** ¥12.50 / ￥12.5 / 12.50元 / RMB 12.50 */
    private static final Pattern AMOUNT = Pattern.compile(
            "[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)|([0-9]+(?:\\.[0-9]{1,2})?)\\s*元"
                    + "|RMB\\s*([0-9]+(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    /**
     * 收入词 —— 必须放在支出词前面判断。
     * 「XX 向你付款」「收款到账」这些字面上带「付款」，但钱是进来的。
     */
    private static final String[] INCOME_WORDS = {
            "向你付款", "向你转账", "收款", "已到账", "到账", "收到", "收入",
            "退款", "退回", "转入", "红包到账", "已收款", "退款成功", "退款的", "利息到账"
    };
    private static final String[] EXPENSE_WORDS = {
            "付款", "支出", "支付成功", "已支付", "消费", "扣款", "转出", "缴费",
            "交易成功", "已付款", "扣费", "还款成功", "还款", "免密支付", "自动扣款",
            "付款成功", "支付凭证", "已扣款", "支付了"
    };

    /** 服务是否连上过（进程内），用来在界面上显示状态。 */
    private static volatile boolean connected;
    private static volatile long connectedAt;

    public static boolean isConnected() {
        return connected;
    }

    public static long connectedAt() {
        return connectedAt;
    }

    public static void markConnected() {
        connected = true;
        connectedAt = System.currentTimeMillis();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || sbn.getNotification() == null) {
                return;
            }
            String pkg = sbn.getPackageName();
            String platform = platformOf(pkg);
            if (platform == null) {
                return;
            }
            // 群组摘要只有「你收到 N 条新消息」，没有金额，跳过
            int flags = sbn.getNotification().flags;
            if ((flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                return;
            }

            Bundle ex = sbn.getNotification().extras;
            if (ex == null) {
                return;
            }
            String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            String big = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
            String all = (title + " " + text + " " + big + " " + sub).trim();

            Double amount = findAmount(all);
            if (amount == null || amount <= 0) {
                return;
            }
            Integer type = findDirection(all);
            if (type == null) {
                return;
            }

            PendingPays.Item it = new PendingPays.Item();
            it.platform = platform;
            it.packageName = pkg;
            it.title = title;
            it.text = TextUtils.isEmpty(text) ? big : text;
            it.amount = amount;
            it.type = type;
            it.date = Entry.today();
            it.at = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
            // 同一台设备上重复推送的同一条通知（两分钟内）算重复
            it.uid = Integer.toHexString(
                    (pkg + "|" + title + "|" + it.text + "|" + amount).hashCode())
                    + "-" + (it.at / 120000L);
            PendingPays.add(this, it);
            // 记一笔「最近捕获」，界面上能证明它真的在工作
            AppPrefs.get(this).setLastCapture(System.currentTimeMillis(),
                    platform + " · " + (type == Entry.INCOME ? "收入" : "支出")
                            + " ¥" + String.format(java.util.Locale.US, "%.2f", amount)
                            + " · " + (TextUtils.isEmpty(it.text) ? it.title : it.text));
            Log.i(TAG, "捕获 " + platform + " " + (type == Entry.INCOME ? "收入" : "支出")
                    + " " + amount + " ← " + title + " / " + it.text);
        } catch (Throwable t) {
            Log.w(TAG, "解析通知失败：" + t);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        markConnected();
        Log.i(TAG, "通知监听已连接");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        Log.i(TAG, "通知监听断开");
    }

    // ───────────────────── 工具 ─────────────────────

    public static String platformOf(String pkg) {
        if (PKG_WECHAT.equals(pkg)) {
            return "微信";
        }
        if (PKG_ALIPAY.equals(pkg)) {
            return "支付宝";
        }
        return null;
    }

    static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().replace('\n', ' ').trim();
    }

    /** 从通知文本里取金额；同一段文本里有多个金额时取最大的那个（总额通常在后面）。 */
    static Double findAmount(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = AMOUNT.matcher(s);
        Double best = null;
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1)
                    : (m.group(2) != null ? m.group(2) : m.group(3));
            try {
                double v = Double.parseDouble(g);
                if (best == null || v > best) {
                    best = v;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    /**
     * 自检：拿几条真实样式的通知文本过一遍解析，把结果摊开给用户看。
     * 这样「实时记账有没有用」不用靠等一条真通知才知道。
     *
     * @return 每项 {来源, 原文, 结论}
     */
    public static java.util.List<String[]> selfTest() {
        String[][] samples = {
                {"微信", "微信支付", "向 星巴克(国贸店) 付款 ¥32.00"},
                {"微信", "微信支付收款", "收款到账 ￥88.00"},
                {"微信", "微信支付", "已付款 12.50元"},
                {"支付宝", "支付宝", "你已成功付款 15.80 元"},
                {"支付宝", "支付宝", "支付宝收款 9.90 元"},
                {"支付宝", "余额宝", "转入到账 ¥200.00"},
                {"微信", "张三", "在吗？晚上一起吃饭"},
        };
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
        for (String[] s : samples) {
            String pkg = "微信".equals(s[0]) ? PKG_WECHAT : PKG_ALIPAY;
            String all = s[1] + " " + s[2];
            Double amt = findAmount(all);
            Integer dir = findDirection(all);
            String verdict;
            if (amt == null || dir == null) {
                verdict = "不计入（不是支付通知）";
            } else {
                verdict = "记 " + (dir == Entry.INCOME ? "收入" : "支出")
                        + " +¥" + String.format(java.util.Locale.US, "%.2f", amt);
            }
            out.add(new String[]{s[0], s[1] + " / " + s[2], verdict,
                    pkg, String.valueOf(amt), String.valueOf(dir)});
        }
        return out;
    }

    /** 判断收支方向；收入词优先（「退款到账」这种要算收入）。 */
    static Integer findDirection(String s) {
        if (s == null) {
            return null;
        }
        for (String w : INCOME_WORDS) {
            if (s.contains(w)) {
                return Entry.INCOME;
            }
        }
        for (String w : EXPENSE_WORDS) {
            if (s.contains(w)) {
                return Entry.EXPENSE;
            }
        }
        return null;
    }

    /** 用户是否已经授予通知使用权。 */
    public static boolean isEnabled(Context c) {
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
