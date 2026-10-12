package com.sudfry.todo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.sudfry.todo.R;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;
import com.sudfry.todo.util.Urgency;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 周视图：7 列 × 小时网格，任务按时间画成色块，颜色 = 紧急度。
 * 一天里所有任务的位置关系、哪几天扎堆，一眼能看出来。
 */
public class WeekView extends View {

    public interface Listener {
        void onTaskClick(Todo t);

        void onEmptyClick(LocalDate day, int minute);

        /** 拖动某条任务改了日期和时间。 */
        void onTaskMoved(Todo t, LocalDate day, int minute);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float d;
    private final float headerH;
    private final float laneH;
    private final float hourH;
    private final float gutter;
    private final float padBottom;
    private final int startMin = 6 * 60;

    private final int cDivider;
    private final int cDim;
    private final int cText;
    private final int cOverdue;
    private final int cAccent;
    private final int cSurface;
    private final int[] levelColor = new int[5];

    private List<Todo> items = new ArrayList<>();
    private LocalDate weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
    private long now = System.currentTimeMillis();
    private Listener listener;

    private final ArrayList<RectF> hits = new ArrayList<>();
    private final ArrayList<Todo> hitTodos = new ArrayList<>();

    public WeekView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        headerH = 40 * d;
        laneH = 18 * d;
        hourH = 46 * d;
        gutter = 34 * d;
        padBottom = 12 * d;
        cDivider = Ui.color(c, R.color.divider);
        cDim = Ui.color(c, R.color.text_dim);
        cText = Ui.color(c, R.color.text);
        cOverdue = Ui.color(c, R.color.overdue);
        cAccent = Theme.accent(c);
        cSurface = Ui.color(c, R.color.surface);
        for (int i = 0; i < 5; i++) {
            levelColor[i] = Theme.urgency(c, i);
        }
        setBackgroundColor(cSurface);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setWeek(LocalDate anyDayInWeek) {
        weekStart = anyDayInWeek.with(java.time.DayOfWeek.MONDAY);
        invalidate();
    }

    public LocalDate weekStart() {
        return weekStart;
    }

    public void setData(List<Todo> all) {
        items = all == null ? new ArrayList<>() : all;
        now = System.currentTimeMillis();
        requestLayout();
        invalidate();
    }

    /** 返回某天在这个周里的序号（0=周一）。 */
    public static int dayIndex(LocalDate day, LocalDate monday) {
        return (int) java.time.temporal.ChronoUnit.DAYS.between(monday, day);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(headerH + laneH + (24 * 60 - startMin) / 60f * hourH + padBottom);
        setMeasuredDimension(w, h);
    }

    private float colW() {
        return (getWidth() - gutter) / 7f;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float cw = colW();
        float gridTop = headerH + laneH;
        float gridBottom = gridTop + (24 * 60 - startMin) / 60f * hourH;
        LocalDate today = LocalDate.now();

        // ── 表头：周一…周日 + 日期 ──
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        for (int i = 0; i < 7; i++) {
            LocalDate day = weekStart.plusDays(i);
            float cx = gutter + cw * (i + 0.5f);
            boolean isToday = day.equals(today);
            if (isToday) {
                p.setColor(withAlpha(cAccent, 40));
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(new RectF(gutter + cw * i + 2 * d, 4 * d,
                        gutter + cw * (i + 1) - 2 * d, headerH - 4 * d), 8 * d, 8 * d, p);
            }
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(10 * d);
            tp.setColor(isToday ? cAccent : cDim);
            cv.drawText(Fmt.WD[i], cx, 15 * d, tp);
            tp.setTextSize(13 * d);
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tp.setColor(isToday ? cAccent : cText);
            cv.drawText(String.valueOf(day.getDayOfMonth()), cx, 32 * d, tp);
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        // 今天整列淡底
        int todayIdx = dayIndex(today, weekStart);
        if (todayIdx >= 0 && todayIdx < 7) {
            p.setColor(withAlpha(cAccent, 16));
            p.setStyle(Paint.Style.FILL);
            cv.drawRect(gutter + cw * todayIdx, gridTop,
                    gutter + cw * (todayIdx + 1), gridBottom, p);
        }

        // ── 全天/不限时间的任务：表头下面一条窄带 ──
        for (int i = 0; i < 7; i++) {
            LocalDate day = weekStart.plusDays(i);
            List<Todo> noTime = new ArrayList<>();
            for (Todo t : items) {
                if (t.due > 0 && !t.hasTime && Todo.toLocal(t.due).toLocalDate().equals(day)) {
                    noTime.add(t);
                }
            }
            if (noTime.isEmpty()) {
                continue;
            }
            Collections.sort(noTime, (a, b) -> Urgency.compare(a, b, now));
            float x0 = gutter + cw * i + 3 * d;
            float x1 = gutter + cw * (i + 1) - 3 * d;
            int shown = Math.min(2, noTime.size());
            float by = headerH + 1 * d;
            for (int k = 0; k < shown; k++) {
                Todo t = noTime.get(k);
                int lv = t.done ? Urgency.NONE : Urgency.level(t, now);
                p.setColor(t.done ? withAlpha(levelColor[Urgency.NONE], 90) : levelColor[lv]);
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(new RectF(x0, by, x1, by + 6 * d), 3 * d, 3 * d, p);
                by += 8 * d;
            }
        }

        // ── 小时网格 ──
        for (int h = startMin / 60; h <= 24; h++) {
            float y = gridTop + (h - startMin / 60) * hourH;
            p.setColor(cDivider);
            p.setStrokeWidth(Math.max(1f, d * 0.8f));
            cv.drawLine(0, y, getWidth(), y, p);
            tp.setColor(cDim);
            tp.setTextSize(9 * d);
            tp.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(Fmt.hm(h * 60), gutter - 4 * d, y + 10 * d, tp);
        }
        // 竖分隔线
        for (int i = 0; i <= 7; i++) {
            float x = gutter + cw * i;
            p.setColor(cDivider);
            cv.drawLine(x, headerH, x, gridBottom, p);
        }

        // ── 任务块 ──
        hits.clear();
        hitTodos.clear();
        for (int i = 0; i < 7; i++) {
            LocalDate day = weekStart.plusDays(i);
            List<Todo> dayTasks = new ArrayList<>();
            for (Todo t : items) {
                if (t.due > 0 && t.hasTime && Todo.toLocal(t.due).toLocalDate().equals(day)) {
                    dayTasks.add(t);
                }
            }
            if (dayTasks.isEmpty()) {
                continue;
            }
            Collections.sort(dayTasks, (a, b) -> Long.compare(a.due, b.due));

            // 同一列内重叠的分成两栏，避免互相盖住
            boolean[] slotUsed = new boolean[2];
            long[] slotEnd = new long[2];
            for (Todo t : dayTasks) {
                long start = t.due;
                int slot = -1;
                for (int s = 0; s < 2; s++) {
                    if (!slotUsed[s] || slotEnd[s] <= start) {
                        slot = s;
                        break;
                    }
                }
                if (slot < 0) {
                    slot = 1;
                }
                slotUsed[slot] = true;
                slotEnd[slot] = t.due + Math.max(15, t.durationMin) * 60000L;

                LocalDateTime dt = Todo.toLocal(t.due);
                float startMinutes = dt.getHour() * 60 + dt.getMinute();
                float top = gridTop + (startMinutes - startMin) / 60f * hourH;
                float height = Math.max(18 * d, Math.max(15, t.durationMin) / 60f * hourH);
                float x0 = gutter + cw * i + 2 * d;
                float x1 = gutter + cw * (i + 1) - 2 * d;
                float half = (x1 - x0) / 2f;
                float left = x0 + (slot == 0 ? 0 : half + 1 * d);
                float right = slot == 0 ? x0 + half - 1 * d : x1;

                int lv = t.done ? Urgency.NONE : Urgency.level(t, now);
                int fill = t.done ? withAlpha(levelColor[Urgency.NONE], 95) : levelColor[lv];
                RectF r = new RectF(left, top + 1 * d, right, top + height);
                int idx = hits.size();
                hits.add(r);
                hitTodos.add(t);

                p.setStyle(Paint.Style.FILL);
                if (dragging && idx == dragIndex) {
                    // 原位虚线框 + 跟手的实心块
                    p.setStyle(Paint.Style.STROKE);
                    p.setColor(cDim);
                    p.setStrokeWidth(1.2f * d);
                    cv.drawRoundRect(r, 5 * d, 5 * d, p);
                    int save = cv.save();
                    cv.translate(dragDx, dragDy);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x33000000);
                    cv.drawRoundRect(new RectF(r.left + 2 * d, r.top + 3 * d,
                            r.right + 2 * d, r.bottom + 3 * d), 5 * d, 5 * d, p);
                    p.setColor(fill);
                    cv.drawRoundRect(r, 5 * d, 5 * d, p);
                    cv.restoreToCount(save);
                } else {
                    p.setColor(fill);
                    cv.drawRoundRect(r, 5 * d, 5 * d, p);
                }
            }
        }

        // ── 拖动时：高亮目标格子 + 时间气泡 ──
        if (dragging && dragIndex >= 0 && dragIndex < hitTodos.size()) {
            RectF r = hits.get(dragIndex);
            float cx = r.centerX() + dragDx;
            float top = r.top + dragDy;
            int col = Math.max(0, Math.min(6, (int) Math.floor((cx - gutter) / cw)));
            float minutes = startMin + (top - gridTop) / hourH * 60f;
            int minute = Math.max(0, Math.min(24 * 60 - 1,
                    Math.round(minutes / 15f) * 15));

            // 目标列整列淡高亮
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(cAccent, 30));
            cv.drawRect(gutter + cw * col, gridTop, gutter + cw * (col + 1), gridBottom, p);
            float ty = gridTop + (minute - startMin) / 60f * hourH;
            p.setColor(withAlpha(cAccent, 70));
            cv.drawRect(gutter + cw * col, ty - 1 * d, gutter + cw * (col + 1), ty + 1.5f * d, p);

            String label = Fmt.WD[col].length() > 0
                    ? ("周" + Fmt.WD[col] + " " + Fmt.hm(minute)) : Fmt.hm(minute);
            tp.setTextSize(11.5f * d);
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tp.setTextAlign(Paint.Align.LEFT);
            float tw = tp.measureText(label);
            float bx = Math.max(gutter + 2 * d,
                    Math.min(getWidth() - tw - 20 * d, gutter + cw * col));
            RectF bubble = new RectF(bx, ty - 26 * d, bx + tw + 18 * d, ty - 6 * d);
            p.setColor(cAccent);
            cv.drawRoundRect(bubble, 8 * d, 8 * d, p);
            tp.setColor(0xFFFFFFFF);
            Paint.FontMetrics bfm = tp.getFontMetrics();
            cv.drawText(label, bubble.left + 9 * d,
                    bubble.centerY() - (bfm.ascent + bfm.descent) / 2f, tp);
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        // ── 现在时刻线 ──
        LocalDateTime n = Todo.toLocal(now);
        float nowMinutes = n.getHour() * 60 + n.getMinute() + n.getSecond() / 60f;
        if (todayIdx >= 0 && todayIdx < 7 && nowMinutes >= startMin) {
            float y = gridTop + (nowMinutes - startMin) / 60f * hourH;
            if (y <= gridBottom) {
                p.setColor(cOverdue);
                p.setStrokeWidth(1.6f * d);
                cv.drawLine(gutter + cw * todayIdx, y, gutter + cw * (todayIdx + 1), y, p);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(gutter + cw * todayIdx, y, 3 * d, p);
            }
        }

        boolean any = false;
        for (Todo t : items) {
            if (t.due > 0) {
                LocalDate dd = Todo.toLocal(t.due).toLocalDate();
                if (!dd.isBefore(weekStart) && !dd.isAfter(weekStart.plusDays(6))) {
                    any = true;
                    break;
                }
            }
        }
        if (!any) {
            tp.setColor(cDim);
            tp.setTextSize(12.5f * d);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText("这一周还没有安排", getWidth() / 2f, gridTop + hourH * 3, tp);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dragIndex = -1;
    private boolean dragMoved;
    private boolean dragging;
    private float dragDownX;
    private float dragDownY;
    private float dragDx;
    private float dragDy;
    private float lastX;
    private float lastY;
    private final Runnable longPressEnter = new Runnable() {
        @Override
        public void run() {
            if (dragIndex < 0) {
                return;
            }
            dragging = true;
            dragDownX = lastX;
            dragDownY = lastY;
            dragDx = 0;
            dragDy = 0;
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            com.sudfry.todo.util.Sfx.click(getContext());
            invalidate();
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        lastX = x;
        lastY = y;
        // 轻点 = 打开；长按 0.38 秒进入拖动，可跨天改时间
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragIndex = -1;
                dragMoved = false;
                dragging = false;
                for (int i = hits.size() - 1; i >= 0; i--) {
                    // 手指容错：色块很窄，横向放宽一点
                    RectF r = new RectF(hits.get(i));
                    r.inset(-6 * d, -4 * d);
                    if (r.contains(x, y)) {
                        dragIndex = i;
                        dragDownX = x;
                        dragDownY = y;
                        dragDx = 0;
                        dragDy = 0;
                        removeCallbacks(longPressEnter);
                        postDelayed(longPressEnter, 380);
                        break;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragging) {
                    dragDx = x - dragDownX;
                    dragDy = y - dragDownY;
                    dragMoved = true;
                    invalidate();
                } else if (dragIndex >= 0
                        && (Math.abs(x - dragDownX) > 12 * d || Math.abs(y - dragDownY) > 12 * d)) {
                    removeCallbacks(longPressEnter);
                    dragIndex = -1;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressEnter);
                if (dragging && dragIndex >= 0 && dragIndex < hitTodos.size()) {
                    Todo t = hitTodos.get(dragIndex);
                    RectF r = hits.get(dragIndex);
                    float gridTop = headerH + laneH;
                    float cx = r.centerX() + dragDx;
                    float top = r.top + dragDy;
                    int col = (int) Math.floor((cx - gutter) / colW());
                    col = Math.max(0, Math.min(6, col));
                    float minutes = startMin + (top - gridTop) / hourH * 60f;
                    int minute = Math.round(minutes / 15f) * 15;   // 周视图吸附到 15 分钟
                    minute = Math.max(0, Math.min(24 * 60 - 1, minute));
                    dragIndex = -1;
                    dragging = false;
                    dragDx = 0;
                    dragDy = 0;
                    invalidate();
                    releaseParent();
                    if (listener != null) {
                        listener.onTaskMoved(t, weekStart.plusDays(col), minute);
                    }
                    performClick();
                    return true;
                }
                dragIndex = -1;
                dragging = false;
                dragDx = 0;
                dragDy = 0;
                invalidate();
                releaseParent();
                for (int i = hits.size() - 1; i >= 0; i--) {
                    if (hits.get(i).contains(x, y)) {
                        if (listener != null && i < hitTodos.size()) {
                            listener.onTaskClick(hitTodos.get(i));
                        }
                        performClick();
                        return true;
                    }
                }
                float gridTop = headerH + laneH;
                if (x > gutter && y > gridTop) {
                    int col = (int) ((x - gutter) / colW());
                    if (col >= 0 && col < 7) {
                        float minutes = startMin + (y - gridTop) / hourH * 60f;
                        int minute = Math.max(0, Math.min(24 * 60 - 1,
                                Math.round(minutes)));
                        if (listener != null) {
                            listener.onEmptyClick(weekStart.plusDays(col), minute);
                        }
                        performClick();
                        return true;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressEnter);
                dragIndex = -1;
                dragging = false;
                dragDx = 0;
                dragDy = 0;
                invalidate();
                releaseParent();
                return true;
            }
            default:
                return true;
        }
    }

    private void releaseParent() {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
