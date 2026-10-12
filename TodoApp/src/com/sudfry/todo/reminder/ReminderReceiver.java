package com.sudfry.todo.reminder;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.sudfry.todo.MainActivity;
import com.sudfry.todo.R;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.util.Fmt;

public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long id = intent.getLongExtra(Reminders.EXTRA_ID, 0);
        TodoStore store = TodoStore.get(context);
        Todo t = store.byId(id);
        if (t == null || t.done) {
            return;
        }
        Reminders.ensureChannel(context);

        String when = t.due > 0 ? (Fmt.date(t.due) + " " + (t.hasTime ? Fmt.time(t.due) : "全天")) : "无期限";
        String text = "截止 " + when;
        if (t.note != null && !t.note.isEmpty()) {
            text = text + " · " + t.note;
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra(Reminders.EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(context, (int) (id & 0x7FFFFFFF), open, flags);

        Notification.Builder b = new Notification.Builder(context, Reminders.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_todo)
                .setContentTitle("待办提醒：" + t.title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        try {
            b.setColorized(false);
        } catch (Throwable ignored) {
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify((int) (id & 0x7FFFFFFF), b.build());
        }
    }
}
