package com.sudfry.todo.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import com.sudfry.todo.EditActivity;
import com.sudfry.todo.MainActivity;
import com.sudfry.todo.R;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.reminder.Reminders;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.util.Urgency;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌面小组件：主屏直接看今天要做什么，点一下就能勾掉，不用打开应用。
 *
 * RemoteViews 只能用系统控件，所以行是写死的 4 行（够用，且比 ListView 稳），
 * 点某一行 → 发广播回来 → 直接改数据 → 刷新组件。
 */
public class TodayWidget extends AppWidgetProvider {

    public static final String ACTION_TOGGLE = "com.sudfry.todo.widget.TOGGLE";
    public static final String EXTRA_ID = "todo_id";

    private static final int[] ROW = {R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4};
    private static final int[] BOX = {R.id.w_box1, R.id.w_box2, R.id.w_box3, R.id.w_box4};
    private static final int[] TITLE = {R.id.w_title1, R.id.w_title2, R.id.w_title3, R.id.w_title4};
    private static final int[] TAG = {R.id.w_tag1, R.id.w_tag2, R.id.w_tag3, R.id.w_tag4};

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            mgr.updateAppWidget(id, build(c));
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        super.onReceive(c, intent);
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            long id = intent.getLongExtra(EXTRA_ID, 0);
            TodoStore store = TodoStore.get(c);
            Todo t = store.byId(id);
            if (t != null) {
                Todo next = store.setDone(id, !t.done);
                if (next != null) {
                    Reminders.schedule(c, next);
                } else if (!t.done) {
                    Reminders.cancel(c, t);
                }
            }
            refresh(c);
        }
    }

    /** 数据变了就调这里，让所有组件实例跟着刷新。 */
    public static void refresh(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            ComponentName me = new ComponentName(c, TodayWidget.class);
            int[] ids = mgr.getAppWidgetIds(me);
            if (ids == null || ids.length == 0) {
                return;
            }
            for (int id : ids) {
                mgr.updateAppWidget(id, build(c));
            }
        } catch (Throwable ignored) {
        }
    }

    private static RemoteViews build(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_today);

        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        List<Todo> all = TodoStore.get(c).all();

        // 今天要处理的：逾期的 + 今天到期的，按紧急度排
        ArrayList<Todo> show = new ArrayList<>();
        int overdue = 0;
        for (Todo t : all) {
            if (t.done || t.due <= 0) {
                continue;
            }
            LocalDate d = Todo.toLocal(t.due).toLocalDate();
            int lv = Urgency.level(t, now);
            if (lv == Urgency.OVERDUE) {
                overdue++;
                show.add(t);
            } else if (d.equals(today)) {
                show.add(t);
            }
        }
        Collections.sort(show, (a, b) -> Urgency.compare(a, b, now));

        String sub;
        if (show.isEmpty()) {
            sub = "今天没有压着的任务";
        } else if (overdue > 0) {
            sub = show.size() + " 项待办 · 其中 " + overdue + " 项已逾期";
        } else {
            sub = show.size() + " 项待办";
        }
        rv.setTextViewText(R.id.widget_sub, sub);
        rv.setViewVisibility(R.id.widget_empty, show.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.widget_more,
                show.size() > ROW.length ? View.VISIBLE : View.GONE);
        if (show.size() > ROW.length) {
            rv.setTextViewText(R.id.widget_more,
                    "还有 " + (show.size() - ROW.length) + " 项，点这里打开应用");
        }

        int shown = Math.min(ROW.length, show.size());
        for (int i = 0; i < ROW.length; i++) {
            if (i >= shown) {
                rv.setViewVisibility(ROW[i], View.GONE);
                continue;
            }
            Todo t = show.get(i);
            rv.setViewVisibility(ROW[i], View.VISIBLE);
            rv.setTextViewText(TITLE[i], t.title == null || t.title.isEmpty() ? "（无标题）" : t.title);
            String tag = Urgency.shortRemaining(t, now);
            if (t.priority >= Todo.PRI_HIGH) {
                tag = Todo.priorityName(t.priority) + " · " + tag;
            }
            rv.setTextViewText(TAG[i], tag);
            rv.setTextColor(TAG[i], Urgency.level(t, now) <= Urgency.TODAY
                    ? com.sudfry.todo.util.Theme.urgency(c, Urgency.level(t, now))
                    : com.sudfry.todo.util.Ui.color(c, R.color.text_dim));
            rv.setTextViewText(BOX[i], "☐");
            rv.setTextColor(BOX[i], com.sudfry.todo.util.Theme.urgency(c, Urgency.level(t, now)));
            rv.setOnClickPendingIntent(ROW[i], toggleIntent(c, t.id, (int) (t.id & 0x7FFFFFFF)));
        }

        // 点标题区/空白区打开应用
        rv.setOnClickPendingIntent(R.id.widget_title, openApp(c, 7001));
        rv.setOnClickPendingIntent(R.id.widget_sub, openApp(c, 7002));
        rv.setOnClickPendingIntent(R.id.widget_empty, openApp(c, 7003));
        rv.setOnClickPendingIntent(R.id.widget_more, openApp(c, 7004));
        // ＋ 直接新建任务
        rv.setOnClickPendingIntent(R.id.widget_add, newTask(c));
        rv.setOnClickPendingIntent(R.id.widget_root, openApp(c, 7005));
        return rv;
    }

    private static int flags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return f;
    }

    private static PendingIntent toggleIntent(Context c, long todoId, int req) {
        Intent i = new Intent(c, TodayWidget.class);
        i.setAction(ACTION_TOGGLE);
        i.putExtra(EXTRA_ID, todoId);
        return PendingIntent.getBroadcast(c, req, i, flags());
    }

    private static PendingIntent openApp(Context c, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, flags());
    }

    private static PendingIntent newTask(Context c) {
        Intent i = new Intent(c, EditActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 7006, i, flags());
    }
}
