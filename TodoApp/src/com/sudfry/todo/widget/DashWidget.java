package com.sudfry.todo.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.widget.RemoteViews;

import com.sudfry.todo.MainActivity;
import com.sudfry.todo.R;
import com.sudfry.todo.StatsActivity;
import com.sudfry.todo.model.Entry;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;
import com.sudfry.todo.util.Urgency;

import java.time.LocalDate;
import java.util.List;

/**
 * 4×2 数据看板小组件：今日完成进度环 + 今日收支 + 下一个待办。
 *
 * RemoteViews 里画不了圆弧，所以进度环是先在内存里画成位图再塞进去的。
 * 点环进统计报表，点收支/待办打开应用。
 */
public class DashWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            mgr.updateAppWidget(id, build(c));
        }
    }

    public static void refresh(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(c, DashWidget.class));
            if (ids == null || ids.length == 0) {
                return;
            }
            RemoteViews rv = build(c);
            for (int id : ids) {
                mgr.updateAppWidget(id, rv);
            }
        } catch (Throwable ignored) {
        }
    }

    private static RemoteViews build(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_dash);
        long now = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();

        // ── 今日完成进度 ──
        List<Todo> all = TodoStore.get(c).all();
        int total = 0;
        int done = 0;
        for (Todo t : all) {
            if (t.due <= 0) {
                continue;
            }
            LocalDate d = Todo.toLocal(t.due).toLocalDate();
            if (!d.equals(today)) {
                continue;
            }
            total++;
            if (t.done) {
                done++;
            }
        }
        // 今天没排任务时，用「今天已完成的」兜底，别显示成空环
        if (total == 0) {
            for (Todo t : all) {
                if (t.done && t.doneAt > 0
                        && Todo.toLocal(t.doneAt).toLocalDate().equals(today)) {
                    total++;
                    done++;
                }
            }
        }
        int accent = Theme.accent(c);
        rv.setImageViewBitmap(R.id.dash_ring, ring(c, done, total, accent,
                Ui.color(c, R.color.surface_alt)));
        rv.setTextViewText(R.id.dash_done, done + "/" + total);
        rv.setTextViewText(R.id.dash_rate, total == 0 ? "今天没排任务"
                : (done >= total ? "全部完成" : "完成 " + (done * 100 / total) + "%"));

        // ── 今日收支 ──
        double in = 0;
        double out = 0;
        for (Entry e : EntryStore.get(c).all()) {
            if (!todayStr.equals(e.date)) {
                continue;
            }
            if (e.isIncome()) {
                in += e.amount;
            } else {
                out += e.amount;
            }
        }
        rv.setTextViewText(R.id.dash_money,
                "支出 " + Money.yuan(out) + " · 收入 " + Money.yuan(in));

        // ── 下一个待办 ──
        Todo next = null;
        for (Todo t : all) {
            if (t.done) {
                continue;
            }
            // 逾期的优先，其次今天最靠前的
            int lv = Urgency.level(t, now);
            if (lv != Urgency.OVERDUE && !(t.due > 0
                    && Todo.toLocal(t.due).toLocalDate().equals(today))) {
                continue;
            }
            if (next == null || Urgency.compare(t, next, now) < 0) {
                next = t;
            }
        }
        if (next == null) {
            rv.setTextViewText(R.id.dash_next, "今天没有待办，轻松一下");
            rv.setTextViewText(R.id.dash_next_time, "");
            rv.setTextColor(R.id.dash_next_time, Ui.color(c, R.color.text_dim));
        } else {
            rv.setTextViewText(R.id.dash_next,
                    next.title == null || next.title.isEmpty() ? "（无标题）" : next.title);
            int lv = Urgency.level(next, now);
            String when = Urgency.shortRemaining(next, now);
            if (next.hasTime) {
                when = com.sudfry.todo.util.Fmt.time(next.due) + " · " + when;
            }
            rv.setTextViewText(R.id.dash_next_time, when);
            rv.setTextColor(R.id.dash_next_time,
                    lv <= Urgency.TODAY ? Theme.urgency(c, lv) : Ui.color(c, R.color.text_dim));
        }

        // ── 点击 ──
        rv.setOnClickPendingIntent(R.id.dash_left, activity(c, StatsActivity.class, 8101));
        rv.setOnClickPendingIntent(R.id.dash_ring, activity(c, StatsActivity.class, 8102));
        rv.setOnClickPendingIntent(R.id.dash_money_box, activity(c, MainActivity.class, 8103));
        rv.setOnClickPendingIntent(R.id.dash_next_box, activity(c, MainActivity.class, 8104));
        rv.setOnClickPendingIntent(R.id.dash_root, activity(c, MainActivity.class, 8105));
        return rv;
    }

    /** 画一个进度环：底环 + 按比例的前景弧，中间留空。 */
    private static Bitmap ring(Context c, int done, int total, int accent, int track) {
        float density = c.getResources().getDisplayMetrics().density;
        int size = Math.max(48, Math.round(76 * density));
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.11f);
        p.setStrokeCap(Paint.Cap.ROUND);
        float inset = p.getStrokeWidth() / 2f + size * 0.02f;
        RectF box = new RectF(inset, inset, size - inset, size - inset);
        p.setColor(track);
        cv.drawArc(box, 0, 360, false, p);
        if (total > 0 && done > 0) {
            p.setColor(accent);
            cv.drawArc(box, -90, 360f * done / total, false, p);
        }
        return bmp;
    }

    private static int flags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return f;
    }

    private static PendingIntent activity(Context c, Class<?> cls, int req) {
        Intent i = new Intent(c, cls);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, flags());
    }
}
