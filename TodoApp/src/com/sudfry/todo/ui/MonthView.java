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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 月历网格。每一天的格子里用「紧急色条」表示当天有几件事、最急的是什么颜色，
 * 所以不用点进去也能看出这个月哪几天压力最大。
 */
public class MonthView extends View {

    public interface Listener {
        void onDayClick(LocalDate day);

        void onMonthChanged(YearMonth ym);
    }

    /** 日历格子上显示「这天有没有收支」。 */
    public interface MoneySource {
        /** 返回 {有支出, 有收入} */
        boolean[] flags(String date);
    }

    private MoneySource moneySource;

    public void setMoneySource(MoneySource s) {
        moneySource = s;
        invalidate();
    }

    private static final int COLS = 7;
    private static final int ROWS = 6;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);

    private YearMonth ym = YearMonth.now();
    private LocalDate selected = LocalDate.now();
    private List<Todo> items = new ArrayList<>();
    private final ArrayList<int[]> cellHits = new ArrayList<>();
    private final ArrayList<LocalDate> cellDays = new ArrayList<>();
    private Listener listener;

    private float headerH;
    private float cellH;
    private final int cText;
    private final int cDim;
    private final int cAccent;
    private final int cAccentSoft;
    private final int cSurface;
    private final int cDivider;
    private final int cOnColor;
    private final int cMoneyIn;
    private final int cMoneyOut;
    private final int[] levelColor = new int[5];

    public MonthView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        headerH = 26 * d;
        cellH = 64 * d;
        cText = Ui.color(c, R.color.text);
        cDim = Ui.color(c, R.color.text_dim);
        cAccent = com.sudfry.todo.util.Theme.accent(c);
        cAccentSoft = Ui.color(c, R.color.accent_soft);
        cSurface = Ui.color(c, R.color.surface);
        cDivider = Ui.color(c, R.color.divider);
        cOnColor = Ui.color(c, R.color.on_color);
        cMoneyIn = Ui.color(c, R.color.money_in);
        cMoneyOut = Ui.color(c, R.color.money_out);
        for (int i = 0; i < 5; i++) {
            levelColor[i] = com.sudfry.todo.util.Theme.urgency(c, i);
        }
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        setBackgroundColor(cSurface);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public YearMonth month() {
        return ym;
    }

    public void setItems(List<Todo> list) {
        items = list == null ? new ArrayList<>() : list;
        invalidate();
    }

    public void setSelected(LocalDate d) {
        selected = d;
        if (d != null && !YearMonth.from(d).equals(ym)) {
            ym = YearMonth.from(d);
        }
        invalidate();
    }

    public void shift(int months) {
        ym = ym.plusMonths(months);
        if (listener != null) {
            listener.onMonthChanged(ym);
        }
        invalidate();
    }

    public void goTo(YearMonth m) {
        ym = m;
        invalidate();
    }

    private float cellW() {
        return getWidth() / (float) COLS;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(headerH + ROWS * cellH);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float cw = cellW();
        float d = getResources().getDisplayMetrics().density;

        // 星期表头（周一为第一列）
        tp.setColor(cDim);
        tp.setTextSize(11.5f * d);
        tp.setTypeface(android.graphics.Typeface.DEFAULT);
        tp.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float base = headerH / 2f - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < COLS; i++) {
            String label = Fmt.WD[i];
            if (i >= 5) {
                tp.setColor(Ui.color(getContext(), R.color.soon));
            } else {
                tp.setColor(cDim);
            }
            cv.drawText(label, cw * (i + 0.5f), base, tp);
        }

        LocalDate today = LocalDate.now();
        LocalDate first = ym.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        LocalDate gridStart = first.minusDays(offset);

        cellHits.clear();
        cellDays.clear();

        for (int idx = 0; idx < COLS * ROWS; idx++) {
            int col = idx % COLS;
            int row = idx / COLS;
            float left = col * cw;
            float top = headerH + row * cellH;
            RectF r = new RectF(left, top, left + cw, top + cellH);
            LocalDate day = gridStart.plusDays(idx);
            cellHits.add(new int[]{(int) r.left, (int) r.top, (int) r.right, (int) r.bottom});
            cellDays.add(day);

            boolean inMonth = day.getMonthValue() == ym.getMonthValue() && day.getYear() == ym.getYear();
            boolean isToday = day.equals(today);
            boolean isSel = day.equals(selected);

            if (isSel) {
                p.setColor(cAccentSoft);
                p.setStyle(Paint.Style.FILL);
                cv.drawRoundRect(new RectF(r.left + 2 * d, r.top + 2 * d, r.right - 2 * d,
                        r.bottom - 2 * d), 12 * d, 12 * d, p);
            }

            // 日期数字
            tp.setTypeface(android.graphics.Typeface.DEFAULT);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(12.5f * d);
            float numCy = r.top + 17 * d;
            if (isToday) {
                p.setColor(cAccent);
                p.setStyle(Paint.Style.FILL);
                cv.drawCircle(r.centerX(), numCy, 12.5f * d, p);
                tp.setColor(cOnColor);
                tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else if (!inMonth) {
                tp.setColor(withAlpha(cDim, 90));
            } else if (isSel) {
                tp.setColor(cAccent);
                tp.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            } else {
                tp.setColor(cText);
            }
            Paint.FontMetrics nfm = tp.getFontMetrics();
            cv.drawText(String.valueOf(day.getDayOfMonth()), r.centerX(),
                    numCy - (nfm.ascent + nfm.descent) / 2f, tp);

            if (!inMonth) {
                continue;
            }

            // 当天的任务，按紧急度排序取前三条
            List<Todo> dayTasks = tasksOn(day);
            Collections.sort(dayTasks, (a, b) -> Urgency.compare(a, b, System.currentTimeMillis()));
            int shown = Math.min(3, dayTasks.size());
            float barH = 5 * d;
            float gap = 3 * d;
            float barW = cw - 14 * d;
            float by = r.bottom - 8 * d - shown * (barH + gap) + gap;
            for (int i = 0; i < shown; i++) {
                Todo t = dayTasks.get(i);
                int lv = t.done ? Urgency.NONE : Urgency.level(t, System.currentTimeMillis());
                int color = t.done ? withAlpha(levelColor[Urgency.NONE], 110) : levelColor[lv];
                p.setColor(color);
                p.setStyle(Paint.Style.FILL);
                float x0 = r.left + 7 * d;
                cv.drawRoundRect(new RectF(x0, by, x0 + barW, by + barH), barH / 2f, barH / 2f, p);
                by += barH + gap;
            }
            if (dayTasks.size() > 3) {
                tp.setColor(cDim);
                tp.setTypeface(android.graphics.Typeface.DEFAULT);
                tp.setTextSize(9 * d);
                tp.setTextAlign(Paint.Align.RIGHT);
                cv.drawText("+" + (dayTasks.size() - 3), r.right - 6 * d, r.bottom - 5 * d, tp);
            }

            // 收支小圆点（绿=有收入，红=有支出），放在日期数字两侧的空白处
            if (moneySource != null) {
                String key = String.format("%04d-%02d-%02d", day.getYear(),
                        day.getMonthValue(), day.getDayOfMonth());
                boolean[] f = moneySource.flags(key);
                if (f != null && (f[0] || f[1])) {
                    float cy = r.top + 17 * d;
                    float x = r.right - 9 * d;
                    if (f[1]) {
                        p.setColor(cMoneyIn);
                        p.setStyle(Paint.Style.FILL);
                        cv.drawCircle(x, cy, 3.6f * d, p);
                        x -= 10 * d;
                    }
                    if (f[0]) {
                        p.setColor(cMoneyOut);
                        p.setStyle(Paint.Style.FILL);
                        cv.drawCircle(x, cy, 3.6f * d, p);
                    }
                }
            }
        }

        // 底部分隔线
        p.setColor(cDivider);
        p.setStrokeWidth(Math.max(1f, d));
        cv.drawLine(0, headerH, getWidth(), headerH, p);
    }

    private List<Todo> tasksOn(LocalDate day) {
        ArrayList<Todo> out = new ArrayList<>();
        for (Todo t : items) {
            if (t.due > 0 && Todo.toLocal(t.due).toLocalDate().equals(day)) {
                out.add(t);
            }
        }
        return out;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float x = e.getX();
            float y = e.getY();
            if (y > headerH) {
                int row = (int) ((y - headerH) / cellH);
                int col = (int) (x / cellW());
                int idx = row * COLS + col;
                if (idx >= 0 && idx < cellDays.size()) {
                    LocalDate day = cellDays.get(idx);
                    selected = day;
                    invalidate();
                    if (listener != null) {
                        listener.onDayClick(day);
                    }
                    performClick();
                    return true;
                }
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
