package com.sudfry.todo.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.sudfry.todo.MainActivity;
import com.sudfry.todo.R;
import com.sudfry.todo.model.Entry;

/**
 * 「快捷记账」常驻通知。
 *
 * 为什么单独建一个渠道，而不是复用 Reminders.CHANNEL_ID：
 *   待办提醒是 IMPORTANCE_HIGH，会响铃、震动、弹横幅；这一条是 IMPORTANCE_LOW，
 *   只安静地待在通知栏里。如果共用一个渠道，用户要么被常驻通知一直骚扰，
 *   要么为了清静把待办提醒也一起关掉——两件事应该能分别设置。
 */
public final class QuickNotifier {

    /** 独立渠道 id。 */
    public static final String CHANNEL_ID = "quick_entry";
    private static final String CHANNEL_NAME = "快捷记账";

    /** 固定通知 id：常驻通知只需要一条，重复 show 就是原地更新。 */
    private static final int NOTIFY_ID = 9001;

    // 三个 PendingIntent 的 requestCode 必须互不相同，否则后建的会覆盖前一个
    private static final int RC_EXPENSE = 9101;
    private static final int RC_INCOME = 9102;
    private static final int RC_OPEN = 9103;

    private QuickNotifier() {
    }

    /** 建渠道。重复调用是安全的，已有渠道不会被覆盖（用户改过的设置也不会被重置）。 */
    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
        if (ch == null) {
            ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("通知栏里常驻的「快速记一笔」入口");
            ch.setShowBadge(false);
            // 常驻通知不该每次刷新都响一下，这里关掉震动，交给 IMPORTANCE_LOW 保持安静
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    /** 发（或刷新）那条常驻通知。 */
    public static void show(Context c) {
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        Notification.Builder b = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_todo)
                .setContentTitle("快速记一笔")
                .setContentText("下拉点一下就能记账")
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent(c));

        // 3 个 action：两个直接落到记账页（带类型），一个是普通的打开应用
        b.addAction(new Notification.Action.Builder(null, "记支出", addIntent(c, Entry.EXPENSE)).build());
        b.addAction(new Notification.Action.Builder(null, "记收入", addIntent(c, Entry.INCOME)).build());
        b.addAction(new Notification.Action.Builder(null, "打开", openIntent(c)).build());

        try {
            b.setColorized(false);
        } catch (Throwable ignored) {
            // 个别 ROM 不支持，静默跳过
        }
        nm.notify(NOTIFY_ID, b.build());
    }

    /** 收掉常驻通知（例如用户在设置里关掉这个入口时）。 */
    public static void hide(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFY_ID);
        }
    }

    /**
     * 「记支出 / 记收入」：广播给 QuickAddReceiver，由它拉起 QuickEntryActivity。
     * 为什么不直接 getActivity 指向记账页：getActivity 的 PendingIntent 只能带
     * data/extra，用它也能实现，但广播接收器还能在以后加「金额预填」「直接入库」
     * 这类不依赖界面的行为，入口留宽一点更省事。
     */
    private static PendingIntent addIntent(Context c, int type) {
        Intent i = new Intent(c, QuickAddReceiver.class);
        i.setAction(QuickAddReceiver.ACTION_QUICK_ADD);
        i.putExtra(QuickAddReceiver.EXTRA_TYPE, type);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c,
                type == Entry.EXPENSE ? RC_EXPENSE : RC_INCOME, i, flags);
    }

    private static PendingIntent openIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(c, RC_OPEN, i, flags);
    }
}
