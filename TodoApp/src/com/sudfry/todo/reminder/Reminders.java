package com.sudfry.todo.reminder;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.sudfry.todo.model.Todo;
import com.sudfry.todo.store.TodoStore;

import java.time.LocalDateTime;

/** 到期提醒：AlarmManager 精确闹钟 + 通知。 */
public final class Reminders {

    public static final String CHANNEL_ID = "todo_reminder";
    public static final String EXTRA_ID = "todo_id";

    private Reminders() {
    }

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
            ch = new NotificationChannel(CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("任务到期前的提醒");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    /** 触发时刻，0 表示不需要提醒。 */
    public static long triggerAt(Todo t) {
        if (t.due <= 0 || t.remindBefore < 0 || t.done) {
            return 0;
        }
        long mins = t.remindBefore;
        if (t.hasTime) {
            return t.due - mins * 60000L;
        }
        LocalDateTime nine = Todo.toLocal(t.due).withHour(9).withMinute(0).withSecond(0).withNano(0);
        return Todo.toMillis(nine) - mins * 60000L;
    }

    private static PendingIntent intentFor(Context c, Todo t) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.putExtra(EXTRA_ID, t.id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, (int) (t.id & 0x7FFFFFFF), i, flags);
    }

    public static void schedule(Context c, Todo t) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long at = triggerAt(t);
        PendingIntent pi = intentFor(c, t);
        if (at <= System.currentTimeMillis()) {
            am.cancel(pi);
            return;
        }
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    public static void cancel(Context c, Todo t) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(intentFor(c, t));
        }
    }

    /** 开机 / 数据变化后重建全部提醒。 */
    public static void syncAll(Context c) {
        ensureChannel(c);
        TodoStore store = TodoStore.get(c);
        for (Todo t : store.all()) {
            if (t.done) {
                cancel(c, t);
            } else {
                schedule(c, t);
            }
        }
    }
}
