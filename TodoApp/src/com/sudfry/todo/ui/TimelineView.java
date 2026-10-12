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
 * 一天 24 小时的时间轴。任务按起止时间画成彩色块，颜色 = 紧急度，
 * 于是「上午几点必须干什么、哪块最红」一眼可见；当前时刻有一条红色刻度线。
 */
public class TimelineView extends View {

    public interface Listener {
        void onTaskClick(Todo t);

        void onEmptyClick(int minuteOfDay);

        /** 拖动某条任务改了时间（当天的分钟数）。 */
        void onTaskMoved(Todo t, int minuteOfDay);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float d;
    private float hourH;
    private float gutter;
    private float padTop;
    private float padBottom;
    private float radius;
    private float minBlock;

    private final int cDivider;
    private final int cDim;
    private final int cText;
    private final int cOverdue;
    private final int cAccent;
    private final int[] levelColor = new int[5];

    private List<Todo> items = new ArrayList<>();
    private LocalDate day = LocalDate.now();
    private long now = System.currentTimeMillis();
    /** 默认从早上 6 点开始画，避免凌晨的空白把屏幕占满；当天有更早的任务时自动提前。 */
    private int startMin = 6 * 60;
    private static final int END_MIN = 24 * 60;

    private final ArrayList<RectF> blocks = new ArrayList<>();
    private final ArrayList<Todo> blockTodos = new ArrayList<>();
    private final ArrayList<RectF> allDayHits = new ArrayList<>();
    private final ArrayList<Todo> allDayHitTodos = new ArrayList<>();
    private final ArrayList<Todo> allDayItems = new ArrayList<>();
    private float allDayH;

    private Listener listener;

    public TimelineView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        hourH = 62 * d;
        gutter = 52 * d;
        padTop = 10 * d;
        padBottom = 16 * d;
        radius = 10 * d;
        minBlock = 40 * d;
        allDayH = 0;
        cDivider = Ui.color(c, R.color.divider);
        cDim = Ui.color(c, R.color.text_dim);
        cText = Ui.color(c, R.color.text);
        cOverdue = Ui.color(c, R.color.overdue);
        cAccent = com.sudfry.todo.util.Theme.accent(c);
        for (int i = 0; i < 5; i++) {
            levelColor[i] = com.sudfry.todo.util.Theme.urgency(c, i);
        }
        setBackgroundColor(Ui.color(c, R.color.surface));
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setData(List<Todo> all, LocalDate day) {
        this.day = day;
        this.now = System.currentTimeMillis();
        items = new ArrayList<>();
        ArrayList<Todo> allDay = new ArrayList<>();
        for (Todo t : all) {
            if (t.due <= 0 || !Todo.toLocal(t.due).toLocalDate().equals(day)) {
                continue;
            }
            if (t.hasTime) {
                items.add(t);
            } else {
                allDay.add(t);
            }
        }
        Collections.sort(items, (a, b) -> Long.compare(a.due, b.due));
        Collections.sort(allDay, (a, b) -> Urgency.compare(a, b, now));
        startMin = 6 * 60;
        for (Todo t : items) {
            LocalDateTime dt = Todo.toLocal(t.due);
            int m = dt.getHour() * 60 + dt.getMinute();
            if (m < startMin) {
                startMin = Math.max(0, (m / 60) * 60);
            }
        }
        allDayItems.clear();
        allDayItems.addAll(allDay);
        allDayH = allDay.isEmpty() ? 0 : (26 * d + Math.min(allDay.size(), 3) * 24 * d);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(padTop + allDayH + (END_MIN - startMin) / 60f * hourH + padBottom);
        setMeasuredDimension(w, h);
    }

    /** 当前时刻对应的 y（用于外部自动滚动）。 */
    public float yForNow() {
        LocalDateTime n = Todo.toLocal(System.currentTimeMillis());
        float minutes = n.getHour() * 60 + n.getMinute();
        return padTop + allDayH + (minutes - startMin) / 60f * hourH;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float laneLeft = gutter;
        float laneRight = w - 8 * d;
        float top0 = padTop + allDayH;

        // ── 全天任务条 ─────────────────────────────
        allDayHits.clear();
        allDayHitTodos.clear();
        if (!allDayItems.isEmpty()) {
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
            tp.setTextSize(10.5f * d);
            tp.setColor(cDim);
            tp.setTextAlign(Paint.Align.LEFT);
            cv.drawText("全天", 6 * d, padTop + 14 * d, tp);
            float ay = padTop + 22 * d;
            int shown = Math.min(allDayItems.size(), 3);
            for (int i = 0; i < shown; i++) {
                drawAllDay(cv, allDayItems.get(i), laneLeft, laneRight, ay);
                ay += 24 * d;
            }
            if (allDayItems.size() > shown) {
                tp.setColor(cDim);
                tp.setTextSize(10 * d);
                tp.setTextAlign(Paint.Align.RIGHT);
                cv.drawText("还有 " + (allDayItems.size() - shown) + " 项", laneRight, ay + 10 * d, tp);
            }
        }

        // ── 小时网格 ──────────────────────────────
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        int startH = startMin / 60;
        for (int h = startH; h <= 24; h++) {
            float y = top0 + (h - startH) * hourH;
            p.setColor(cDivider);
            p.setStrokeWidth(Math.max(1f, d));
            cv.drawLine(laneLeft, y, laneRight, y, p);
            if (h < 24) {
                // 与「现在」线离得太近的整点标签就不画了，避免两条文字叠在一起
                boolean nearNow = day.equals(LocalDate.now())
                        && Math.abs(minutesNow() - h * 60) < 22;
                if (!nearNow) {
                    tp.setColor(cDim);
                    tp.setTextSize(10.5f * d);
                    tp.setTextAlign(Paint.Align.RIGHT);
                    Paint.FontMetrics fm = tp.getFontMetrics();
                    cv.drawText(Fmt.hm(h * 60), gutter - 8 * d,
                            y + 14 * d - (fm.ascent + fm.descent) / 2f, tp);
                }
            }
        }

        // ── 任务色块 ──────────────────────────────
        blocks.clear();
        blockTodos.clear();
        layoutBlocks(laneLeft, laneRight, top0);
        for (int i = 0; i < blocks.size(); i++) {
            if (dragging && i == dragIndex) {
                // 原位置留个虚线框，被拖的块跟着手指走
                RectF orig = blocks.get(i);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(cDim);
                p.setStrokeWidth(1.5f * d);
                cv.drawRoundRect(orig, radius, radius, p);
                p.setStyle(Paint.Style.FILL);
                int save = cv.save();
                cv.translate(0, dragDy);
                p.setColor(0x33000000);
                cv.drawRoundRect(new RectF(orig.left + 2 * d, orig.top + 4 * d,
                        orig.right + 2 * d, orig.bottom + 4 * d), radius, radius, p);
                drawBlock(cv, blockTodos.get(i), orig);
                cv.restoreToCount(save);
            } else {
                drawBlock(cv, blockTodos.get(i), blocks.get(i));
            }
        }

        // ── 拖动时：目标位置虚线与时间气泡 ──
        if (dragging && dragIndex >= 0 && dragIndex < blockTodos.size()) {
            Todo t = blockTodos.get(dragIndex);
            LocalDateTime dt = Todo.toLocal(t.due);
            int orig = dt.getHour() * 60 + dt.getMinute();
            int delta = Math.round(dragDy / hourH * 60f / 5f) * 5;
            int target = Math.max(0, Math.min(24 * 60 - 5, orig + delta));
            float ty = top0 + (target - startMin) / 60f * hourH;

            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(cAccent, 40));
            cv.drawRect(laneLeft, ty - 1 * d, laneRight, ty + 1.5f * d, p);

            String label = Fmt.hm(target);
            tp.setTextSize(12f * d);
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tp.setTextAlign(Paint.Align.LEFT);
            float tw = tp.measureText(label);
            RectF bubble = new RectF(gutter + 6 * d, ty - 26 * d,
                    gutter + 22 * d + tw, ty - 6 * d);
            p.setColor(cAccent);
            cv.drawRoundRect(bubble, 8 * d, 8 * d, p);
            tp.setColor(0xFFFFFFFF);
            Paint.FontMetrics bfm = tp.getFontMetrics();
            cv.drawText(label, bubble.left + 8 * d,
                    bubble.centerY() - (bfm.ascent + bfm.descent) / 2f, tp);
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        // ── 当前时刻红线 ──────────────────────────
        if (day.equals(LocalDate.now())) {
            LocalDateTime n = Todo.toLocal(System.currentTimeMillis());
            float minutes = n.getHour() * 60 + n.getMinute() + n.getSecond() / 60f;
            float y = top0 + (minutes - startMin) / 60f * hourH;
            p.setColor(cOverdue);
            p.setStrokeWidth(2 * d);
            cv.drawLine(laneLeft - 4 * d, y, laneRight, y, p);
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(laneLeft - 4 * d, y, 4.5f * d, p);
            tp.setColor(cOverdue);
            tp.setTextSize(10 * d);
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tp.setTextAlign(Paint.Align.RIGHT);
            cv.drawText("现在", gutter - 8 * d, y - 6 * d, tp);
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        if (items.isEmpty() && allDayItems.isEmpty()) {
            tp.setColor(cDim);
            tp.setTextSize(13 * d);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText("这一天还没有安排", w / 2f, top0 + hourH * 2.2f, tp);
        }
    }

    private int minutesNow() {
        LocalDateTime n = Todo.toLocal(System.currentTimeMillis());
        return n.getHour() * 60 + n.getMinute();
    }

    private void drawAllDay(Canvas cv, Todo t, float left, float right, float y) {
        int lv = t.done ? Urgency.NONE : Urgency.level(t, now);
        RectF r = new RectF(left, y, right, y + 20 * d);
        allDayHits.add(r);
        allDayHitTodos.add(t);
        p.setColor(t.done ? withAlpha(levelColor[Urgency.NONE], 90) : levelColor[lv]);
        p.setStyle(Paint.Style.FILL);
        cv.drawRoundRect(r, 7 * d, 7 * d, p);
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(11.5f * d);
        tp.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = tp.getFontMetrics();
        cv.save();
        cv.clipRect(r.left + 4 * d, r.top, r.right - 4 * d, r.bottom);
        String label = t.title + (t.done ? "（已完成）" : "");
        cv.drawText(label, r.left + 8 * d, r.centerY() - (fm.ascent + fm.descent) / 2f, tp);
        cv.restore();
    }

    private void layoutBlocks(float laneLeft, float laneRight, float top0) {
        int n = items.size();
        float avail = laneRight - laneLeft;
        int i = 0;
        while (i < n) {
            long clusterEnd = items.get(i).due + Math.max(1, items.get(i).durationMin) * 60000L;
            int j = i + 1;
            while (j < n && items.get(j).due < clusterEnd) {
                long e = items.get(j).due + Math.max(1, items.get(j).durationMin) * 60000L;
                if (e > clusterEnd) {
                    clusterEnd = e;
                }
                j++;
            }
            int count = j - i;
            ArrayList<Long> colEnd = new ArrayList<>();
            int[] colOf = new int[count];
            for (int k = 0; k < count; k++) {
                Todo t = items.get(i + k);
                long start = t.due;
                int slot = -1;
                for (int c = 0; c < colEnd.size(); c++) {
                    if (colEnd.get(c) <= start) {
                        slot = c;
                        break;
                    }
                }
                if (slot < 0) {
                    colEnd.add(0L);
                    slot = colEnd.size() - 1;
                }
                colEnd.set(slot, t.due + Math.max(1, t.durationMin) * 60000L);
                colOf[k] = slot;
            }
            int cols = Math.max(1, colEnd.size());
            float colW = avail / cols;
            for (int k = 0; k < count; k++) {
                Todo t = items.get(i + k);
                LocalDateTime dt = Todo.toLocal(t.due);
                float sMin = dt.getHour() * 60 + dt.getMinute();
                float height = Math.max(minBlock, Math.max(1, t.durationMin) / 60f * hourH);
                float top = top0 + (sMin - startMin) / 60f * hourH;
                float left = laneLeft + colOf[k] * colW;
                float right = left + colW - 4 * d;
                blocks.add(new RectF(left, top + 1 * d, right, top + height - 1 * d));
                blockTodos.add(t);
            }
            i = j;
        }
    }

    private void drawBlock(Canvas cv, Todo t, RectF r) {
        int lv = t.done ? Urgency.NONE : Urgency.level(t, now);
        int fill = t.done ? withAlpha(levelColor[Urgency.NONE], 95) : levelColor[lv];
        p.setStyle(Paint.Style.FILL);
        p.setColor(fill);
        cv.drawRoundRect(r, radius, radius, p);

        // 左侧高亮，强化「越急越亮」
        if (!t.done && lv <= Urgency.SOON) {
            p.setColor(0x40FFFFFF);
            cv.drawRoundRect(new RectF(r.left + 2 * d, r.top + 2 * d, r.left + 5 * d, r.bottom - 2 * d),
                    2 * d, 2 * d, p);
        }

        cv.save();
        cv.clipRect(r.left + 4 * d, r.top, r.right - 4 * d, r.bottom);
        tp.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm;
        float x = r.left + 9 * d;
        boolean tall = r.height() >= 42 * d;
        if (tall) {
            tp.setColor(0xD9FFFFFF);
            tp.setTextSize(10 * d);
            fm = tp.getFontMetrics();
            cv.drawText(Fmt.time(t.due) + " · " + Urgency.shortRemaining(t, now)
                    + (t.priority >= Todo.PRI_HIGH ? " · " + Todo.priorityName(t.priority) : ""),
                    x, r.top + 15 * d - (fm.ascent + fm.descent) / 2f, tp);
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(13 * d);
            tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            fm = tp.getFontMetrics();
            float ty = r.top + 34 * d - (fm.ascent + fm.descent) / 2f;
            if (ty + fm.descent > r.bottom - 4 * d) {
                ty = r.bottom - 6 * d - fm.descent;
            }
            cv.drawText(displayTitle(t), x, ty, tp);
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
        } else {
            tp.setColor(0xFFFFFFFF);
            tp.setTextSize(11.5f * d);
            fm = tp.getFontMetrics();
            cv.drawText(Fmt.time(t.due) + " " + displayTitle(t), x,
                    r.centerY() - (fm.ascent + fm.descent) / 2f, tp);
        }
        cv.restore();
    }

    private String displayTitle(Todo t) {
        String s = t.title == null ? "" : t.title;
        if (t.done) {
            s = "✓ " + s;
        } else if (t.priority == Todo.PRI_URGENT) {
            s = "‼ " + s;
        } else if (t.priority == Todo.PRI_HIGH) {
            s = "! " + s;
        }
        return s;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dragIndex = -1;
    private boolean dragMoved;
    private boolean dragging;
    private float dragDownY;
    private float dragDy;
    private float lastY;
    private final Runnable longPressEnter = new Runnable() {
        @Override
        public void run() {
            if (dragIndex < 0) {
                return;
            }
            // 长按够久了：真正进入拖动。这时才拦下滚动手势，并给一次震动反馈
            dragging = true;
            dragDownY = lastY;
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
        lastY = y;
        // 交互：轻点 = 打开编辑；长按 0.38 秒后进入拖动，再上下拖 = 改时间
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                dragIndex = -1;
                dragMoved = false;
                dragging = false;
                for (int i = blocks.size() - 1; i >= 0; i--) {
                    if (blocks.get(i).contains(x, y)) {
                        dragIndex = i;
                        dragDownY = y;
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
                    dragDy = y - dragDownY;
                    dragMoved = true;
                    invalidate();
                } else if (dragIndex >= 0 && Math.abs(y - dragDownY) > 12 * d) {
                    // 还没长按就滑动了 → 这是滚动，取消拖动意图
                    removeCallbacks(longPressEnter);
                    dragIndex = -1;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressEnter);
                if (dragging && dragIndex >= 0 && dragIndex < blockTodos.size()) {
                    Todo t = blockTodos.get(dragIndex);
                    LocalDateTime dt = Todo.toLocal(t.due);
                    int orig = dt.getHour() * 60 + dt.getMinute();
                    int delta = Math.round(dragDy / hourH * 60f / 5f) * 5;   // 吸附到 5 分钟
                    int target = Math.max(0, Math.min(24 * 60 - 5, orig + delta));
                    dragIndex = -1;
                    dragging = false;
                    dragDy = 0;
                    invalidate();
                    releaseParent();
                    if (target != orig && listener != null) {
                        listener.onTaskMoved(t, target);
                    }
                    performClick();
                    return true;
                }
                boolean wasCandidate = dragIndex >= 0;
                dragIndex = -1;
                dragging = false;
                dragDy = 0;
                invalidate();
                releaseParent();
                if (!wasCandidate) {
                    for (int i = allDayHits.size() - 1; i >= 0; i--) {
                        if (allDayHits.get(i).contains(x, y)) {
                            if (listener != null && i < allDayHitTodos.size()) {
                                listener.onTaskClick(allDayHitTodos.get(i));
                            }
                            performClick();
                            return true;
                        }
                    }
                }
                for (int i = blocks.size() - 1; i >= 0; i--) {
                    if (blocks.get(i).contains(x, y)) {
                        if (listener != null && i < blockTodos.size()) {
                            listener.onTaskClick(blockTodos.get(i));
                        }
                        performClick();
                        return true;
                    }
                }
                if (y > padTop + allDayH && x > gutter) {
                    float minutes = startMin + (y - padTop - allDayH) / hourH * 60f;
                    if (listener != null) {
                        listener.onEmptyClick(Math.max(0, Math.min(24 * 60 - 1,
                                Math.round(minutes))));
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressEnter);
                dragIndex = -1;
                dragging = false;
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
