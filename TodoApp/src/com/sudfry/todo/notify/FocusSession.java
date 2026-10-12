package com.sudfry.todo.notify;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.sudfry.todo.FocusActivity;
import com.sudfry.todo.R;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.util.AppPrefs;

/**
 * 一次专注（番茄钟）会话。
 *
 * 会话状态放在 SharedPreferences 里，而不是 Activity 里 ——
 * 这样用户中途退出应用，到点照样能被闹钟叫回来结算。
 * 「写入累计时长」只允许发生一次：靠 active 标志保证，避免 Activity 和闹钟重复记。
 */
public final class FocusSession {

    public static final String CHANNEL_ID = "focus_session";
    private static final String K_ID = "focus_id";
    private static final String K_START = "focus_start";
    private static final String K_MIN = "focus_min";
    private static final String K_ACTIVE = "focus_active";

    private FocusSession() {
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "专注计时",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("番茄钟到点提醒");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    public static void start(Context c, long todoId, int minutes) {
        ensureChannel(c);
        AppPrefs.get(c).setFocus(todoId, System.currentTimeMillis(), minutes, true);
        scheduleAlarm(c, minutes);
    }

    public static boolean isActive(Context c) {
        return AppPrefs.get(c).focusActive();
    }

    public static long todoId(Context c) {
        return AppPrefs.get(c).focusTodoId();
    }

    public static long startedAt(Context c) {
        return AppPrefs.get(c).focusStart();
    }

    public static int plannedMin(Context c) {
        return AppPrefs.get(c).focusMinutes();
    }

    /** 距离到点还有多少秒（可能为负）。 */
    public static long remainSeconds(Context c) {
        long end = startedAt(c) + plannedMin(c) * 60000L;
        return (end - System.currentTimeMillis()) / 1000L;
    }

    /** 结束会话并把实际时长记到任务上。重复调用只会记一次。 */
    public static void commit(Context c, int actualMin) {
        if (!isActive(c)) {
            return;
        }
        long id = todoId(c);
        AppPrefs.get(c).clearFocus();
        cancelAlarm(c);
        TodoStore store = TodoStore.get(c);
        Todo t = store.byId(id);
        if (t != null && actualMin > 0) {
            Todo copy = t.copy();
            copy.spentMin = copy.spentMin + actualMin;
            store.update(copy);
        }
        notifyDone(c, t == null ? "任务" : t.title, actualMin);
    }

    public static void cancel(Context c) {
        AppPrefs.get(c).clearFocus();
        cancelAlarm(c);
    }

    // ───────────────── 闹钟 ─────────────────

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, FocusReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, 9931, i, flags);
    }

    private static void scheduleAlarm(Context c, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long at = System.currentTimeMillis() + minutes * 60000L;
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c));
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi(c));
            }
        } catch (Throwable t) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi(c));
        }
    }

    private static void cancelAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pi(c));
        }
    }

    private static void notifyDone(Context c, String title, int minutes) {
        ensureChannel(c);
        Intent open = new Intent(c, FocusActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent p = PendingIntent.getActivity(c, 9932, open, flags);
        android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_todo)
                .setContentTitle("专注结束 · 已完成 " + minutes + " 分钟")
                .setContentText(title)
                .setAutoCancel(true)
                .setContentIntent(p)
                .build();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(9931, n);
        }
    }
}
