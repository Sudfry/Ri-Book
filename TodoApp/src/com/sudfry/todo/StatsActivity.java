package com.sudfry.todo;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sudfry.todo.model.Entry;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统计报表：待办完成情况 / 记账收支与支出分类 / 习惯连续记录。
 *
 * <p>全部界面用系统原生控件以代码搭建，不写 layout XML，不依赖 AndroidX。
 * 所有数字都在本类里从 {@link TodoStore} 与 {@link EntryStore} 现算，
 * 页面上没有任何写死的数值。</p>
 *
 * <p>统计口径（页面底部也写了一句给用户看）：
 * <ul>
 *   <li>已完成：{@code doneAt} 落在所选范围内；doneAt 缺失（脏数据）时按「今天」处理。</li>
 *   <li>待处理：未完成，且（截止时间落在范围内 或 没有截止时间）。</li>
 *   <li>已逾期：未完成、有截止时间且已过期，并且截止时间落在所选范围内 —— 是「待处理」的子集。</li>
 *   <li>完成率：已完成 ÷（已完成 + 待处理）。</li>
 *   <li>对比：与「上一周期的同期」比较（本周 vs 上周同期、本月 vs 上月同期、今年 vs 去年同期），
 *       避免月中拿半个月去比一整个月。</li>
 * </ul>
 */
public class StatsActivity extends Activity {

    // ───────────────── 范围 ─────────────────

    private static final int SCOPE_WEEK = 0;
    private static final int SCOPE_MONTH = 1;
    private static final int SCOPE_YEAR = 2;

    private static final String[] SCOPE_NAMES = {"本周", "本月", "今年"};
    private static final String[] SCOPE_PREV_NAMES = {"上周同期", "上月同期", "去年同期"};

    private TodoStore todoStore;
    private EntryStore entryStore;

    /** 当前范围 */
    private int scope = SCOPE_WEEK;

    /** 随范围切换整体重建的内容容器 */
    private LinearLayout content;

    private List<Todo> todos = new ArrayList<>();
    private List<Entry> entries = new ArrayList<>();

    /** 有「已完成任务」或「账目」的日期集合（yyyy-MM-dd），连续记录天数用它算 */
    private final HashSet<String> activeDays = new HashSet<>();

    // ───────────────── 生命周期 ─────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 跟随「设置」里的明暗模式（深色下所有颜色都从资源色取，不会看不清）
        Theme.applyNightMode(this);
        todoStore = TodoStore.get(this);
        entryStore = EntryStore.get(this);
        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
        refresh();
    }

    // ───────────────── 骨架 ─────────────────

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));

        // 自绘标题栏：左「‹ 返回」，中间标题
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bar, this, 12, 10, 12, 10);

        TextView back = Ui.text(this, "‹ 返回", 15f, R.color.accent);
        back.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        back.setOnClickListener(v -> {
            Sfx.tap(v);
            finish();
        });
        bar.addView(back);

        TextView title = Ui.text(this, "统计报表", 16f, R.color.text, true);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);

        // 右侧占位：让标题真正居中（宽度和「‹ 返回」差不多）
        bar.addView(Ui.text(this, "      ", 15f, R.color.accent));
        root.addView(bar);
        root.addView(Widgets.divider(this));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content = Ui.column(this);
        Ui.padDp(content, this, 14, 8, 14, 40);
        sv.addView(content);
        root.addView(sv);
        return root;
    }

    /** 重新读数据 + 整页重建（范围切换、首次进入都走这里） */
    private void refresh() {
        todos = todoStore.all();
        entries = entryStore.all();

        // 哪些天有记录：任务的完成时间 + 账目的日期
        activeDays.clear();
        for (Entry e : entries) {
            if (e.date != null && !e.date.isEmpty()) {
                activeDays.add(e.date);
            }
        }
        for (Todo t : todos) {
            LocalDate day = doneDay(t);
            if (day != null) {
                activeDays.add(Entry.dayOf(day));
            }
        }

        content.removeAllViews();
        content.addView(buildRangeRow());
        buildTodoSection();
        buildMoneySection();
        buildHabitSection();
    }

    /** 本周 / 本月 / 今年 三个胶囊按钮 */
    private View buildRangeRow() {
        LinearLayout row = Ui.row(this);
        int accent = Theme.accent(this);
        for (int i = 0; i < SCOPE_NAMES.length; i++) {
            final int target = i;
            TextView chip = Widgets.chip(this, SCOPE_NAMES[i], scope == i, accent);
            chip.setOnClickListener(v -> {
                if (scope == target) {
                    return;
                }
                Sfx.tap(v);
                scope = target;
                refresh();
            });
            row.addView(chip);
        }
        return row;
    }

    // ───────────────── 范围计算 ─────────────────

    /** 范围起点（含）：本周从周一算，本月从 1 号，今年从 1 月 1 日 */
    private LocalDate rangeStart() {
        LocalDate today = LocalDate.now();
        switch (scope) {
            case SCOPE_WEEK:
                return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case SCOPE_MONTH:
                return today.withDayOfMonth(1);
            default:
                return today.withDayOfYear(1);
        }
    }

    /** 范围终点（含）：周日 / 月末 / 年末 */
    private LocalDate rangeEnd() {
        LocalDate today = LocalDate.now();
        switch (scope) {
            case SCOPE_WEEK:
                return rangeStart().plusDays(6);
            case SCOPE_MONTH:
                return today.with(TemporalAdjusters.lastDayOfMonth());
            default:
                return today.with(TemporalAdjusters.lastDayOfYear());
        }
    }

    /** 往前挪一个周期（Java 会自动把 3 月 31 日减一个月夹到 2 月末） */
    private LocalDate shiftBackOnePeriod(LocalDate day) {
        switch (scope) {
            case SCOPE_WEEK:
                return day.minusWeeks(1);
            case SCOPE_MONTH:
                return day.minusMonths(1);
            default:
                return day.minusYears(1);
        }
    }

    /** 上一周期的同期区间（含头含尾）。用「已过天数」对齐，避免拿半个月比一整月。 */
    private LocalDate[] prevRange() {
        LocalDate start = rangeStart();
        LocalDate today = LocalDate.now();
        long elapsed = ChronoUnit.DAYS.between(start, today);
        LocalDate prevStart = shiftBackOnePeriod(start);
        LocalDate prevEnd = prevStart.plusDays(elapsed);
        LocalDate cap = start.minusDays(1);          // 不能越过上一周期的末尾
        if (prevEnd.isAfter(cap)) {
            prevEnd = cap;
        }
        return new LocalDate[]{prevStart, prevEnd};
    }

    private static boolean inRange(LocalDate day, LocalDate a, LocalDate b) {
        return day != null && !day.isBefore(a) && !day.isAfter(b);
    }

    /** 任务完成所在的日期；doneAt 缺失（脏数据）时按今天算，保证不会凭空丢数 */
    private LocalDate doneDay(Todo t) {
        if (t == null || !t.done) {
            return null;
        }
        if (t.doneAt <= 0) {
            return LocalDate.now();
        }
        return Todo.toLocal(t.doneAt).toLocalDate();
    }

    private LocalDate dueDay(Todo t) {
        if (t == null || t.due <= 0) {
            return null;
        }
        return Todo.toLocal(t.due).toLocalDate();
    }

    private int countDone(LocalDate a, LocalDate b) {
        int n = 0;
        for (Todo t : todos) {
            if (inRange(doneDay(t), a, b)) {
                n++;
            }
        }
        return n;
    }

    /** 待处理 = 未完成 且（截止日落在范围内 或 没有期限） */
    private int countPending(LocalDate a, LocalDate b) {
        int n = 0;
        for (Todo t : todos) {
            if (t.done) {
                continue;
            }
            LocalDate due = dueDay(t);
            if (due == null || inRange(due, a, b)) {
                n++;
            }
        }
        return n;
    }

    /** 已逾期 = 待处理里已经过了截止时间的那些 */
    private int countOverdue(LocalDate a, LocalDate b, long now) {
        int n = 0;
        for (Todo t : todos) {
            if (t.done || t.due <= 0 || t.due >= now) {
                continue;
            }
            if (inRange(dueDay(t), a, b)) {
                n++;
            }
        }
        return n;
    }

    // ───────────────── 第一块：待办 ─────────────────

    private void buildTodoSection() {
        final LocalDate a = rangeStart();
        final LocalDate b = rangeEnd();
        final long now = System.currentTimeMillis();

        int done = countDone(a, b);
        int pending = countPending(a, b);
        int overdue = countOverdue(a, b, now);
        int denom = done + pending;
        String rate = denom == 0 ? "—"
                : String.format(Locale.CHINA, "%.1f%%", done * 100.0 / denom);

        content.addView(Widgets.sectionHeader(this, "待办", todos.size(), Theme.accent(this)));

        if (todos.isEmpty()) {
            content.addView(Widgets.empty(this, "还没有任务数据\n先去「待办」页添加一条吧"));
            return;
        }

        // 四个数字卡，两列两行
        addSpaced(content, twoCols(
                numCard("已完成", String.valueOf(done), R.color.done),
                numCard("待处理", String.valueOf(pending), R.color.text)), 0);
        addSpaced(content, twoCols(
                numCardColored("已逾期", String.valueOf(overdue), Theme.urgency(this, 0)),
                numCardColored("完成率", rate, Theme.accent(this))), 5);

        // 口径说明，省得用户对不上数
        TextView hint = Ui.text(this, "口径：已完成按完成时间统计；待处理含无期限任务；已逾期是其中已过期的部分。",
                11f, R.color.text_dim);
        hint.setLineSpacing(0f, 1.25f);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(hint);

        addSpaced(content, buildTodoChart(a, b), 10);
        addSpaced(content, summaryBox(todoSentence(done, pending, overdue)), 10);
    }

    /** 完成数量柱状图：本周 7 根（每天），本月 12 根（每周），今年 12 根（每月） */
    private View buildTodoChart(LocalDate a, LocalDate b) {
        int[] values = barValues();
        String[] labels = barLabels();

        LinearLayout card = Widgets.card(this);
        card.addView(Ui.text(this, chartTitle(), 12f, R.color.text_dim, true));

        int max = 0;
        for (int v : values) {
            max = Math.max(max, v);
        }
        if (max <= 0) {
            card.addView(Widgets.empty(this, "这段时间还没有完成记录"));
            return card;
        }

        // 柱区高度留够：柱顶数字(~12dp) + 柱子 + 横轴标签(~16dp) 必须装进 chartHeight，
        // 否则 gravity=bottom 会把柱顶数字顶出边界被裁掉。
        final int chartHeight = Ui.dp(this, 118);
        final int barArea = Ui.dp(this, 84);
        int accent = Theme.accent(this);
        int emptyColor = Ui.color(this, R.color.divider);
        boolean narrow = values.length > 7;

        LinearLayout chart = Ui.row(this);
        for (int i = 0; i < values.length; i++) {
            LinearLayout col = Ui.column(this);
            col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);

            // 柱顶数字（0 不显示，避免糊成一片）
            if (values[i] > 0) {
                col.addView(Ui.text(this, String.valueOf(values[i]), 10f, R.color.text_dim, true));
            }

            // 柱子：按「占最大值的比例」算高度，纯 View 拼出来，不用自定义 View
            View bar = new View(this);
            int h = values[i] <= 0 ? Math.max(1, Ui.dp(this, 2))
                    : Math.max(Ui.dp(this, 4), Math.round(barArea * (values[i] / (float) max)));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h);
            blp.topMargin = Ui.dp(this, 3);
            blp.leftMargin = Ui.dp(this, narrow ? 1 : 3);
            blp.rightMargin = Ui.dp(this, narrow ? 1 : 3);
            bar.setLayoutParams(blp);
            bar.setBackground(Ui.bg(values[i] > 0 ? accent : emptyColor, Ui.dp(this, 3)));
            col.addView(bar);

            // 横轴标签
            TextView x = Ui.text(this, labels[i], narrow ? 8.5f : 10f, R.color.text_dim);
            x.setGravity(Gravity.CENTER);
            x.setMaxLines(1);
            x.setPadding(0, Ui.dp(this, 4), 0, 0);
            col.addView(x);

            chart.addView(col, new LinearLayout.LayoutParams(0, chartHeight, 1f));
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, chartHeight);
        clp.topMargin = Ui.dp(this, 6);
        card.addView(chart, clp);

        // 同一套数据顺便给出日均，方便看「这个月到底忙不忙」
        long days = ChronoUnit.DAYS.between(a, LocalDate.now()) + 1;
        int done = countDone(a, b);
        double avg = days <= 0 ? 0 : done / (double) days;
        TextView foot = Ui.text(this, String.format(Locale.CHINA,
                "范围内完成 %d 项 · 已过 %d 天 · 日均 %.1f 项", done, days, avg),
                11f, R.color.text_dim);
        foot.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(foot);
        return card;
    }

    private String chartTitle() {
        switch (scope) {
            case SCOPE_WEEK:
                return "本周每天完成数（周一 → 周日）";
            case SCOPE_MONTH:
                return "最近 12 周 · 每周完成数";
            default:
                return "最近 12 个月 · 每月完成数";
        }
    }

    /** 横轴标签：周几 / 周一的月日 / 月份 */
    private String[] barLabels() {
        LocalDate today = LocalDate.now();
        if (scope == SCOPE_WEEK) {
            // 周一开头，正好对应 Fmt.WD 的「一二三四五六日」
            String[] out = new String[7];
            for (int i = 0; i < 7; i++) {
                out[i] = "周" + Fmt.WD[i];
            }
            return out;
        }
        if (scope == SCOPE_MONTH) {
            String[] out = new String[12];
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            for (int i = 0; i < 12; i++) {
                LocalDate week = monday.minusWeeks(11 - i);
                out[i] = week.getMonthValue() + "/" + week.getDayOfMonth();
            }
            return out;
        }
        String[] out = new String[12];
        YearMonth cur = YearMonth.from(today);
        for (int i = 0; i < 12; i++) {
            out[i] = String.valueOf(cur.minusMonths(11 - i).getMonthValue());
        }
        return out;
    }

    /** 柱子高度用的真实数值：每天 / 每周 / 每月的完成数 */
    private int[] barValues() {
        LocalDate today = LocalDate.now();
        if (scope == SCOPE_WEEK) {
            int[] out = new int[7];
            LocalDate monday = rangeStart();
            for (Todo t : todos) {
                LocalDate day = doneDay(t);
                if (day == null) {
                    continue;
                }
                long k = ChronoUnit.DAYS.between(monday, day);
                if (k >= 0 && k < 7) {
                    out[(int) k]++;
                }
            }
            return out;
        }
        if (scope == SCOPE_MONTH) {
            int[] out = new int[12];
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            for (Todo t : todos) {
                LocalDate day = doneDay(t);
                if (day == null) {
                    continue;
                }
                // 归一化到所在周的周一，再和本周一算差了几周
                LocalDate w = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                long k = ChronoUnit.WEEKS.between(monday, w);
                if (k <= 0 && k > -12) {
                    out[11 + (int) k]++;
                }
            }
            return out;
        }
        int[] out = new int[12];
        YearMonth cur = YearMonth.from(today);
        for (Todo t : todos) {
            LocalDate day = doneDay(t);
            if (day == null) {
                continue;
            }
            long k = ChronoUnit.MONTHS.between(YearMonth.from(day), cur);
            if (k >= 0 && k < 12) {
                out[11 - (int) k]++;
            }
        }
        return out;
    }

    /** 一句人话总结：真的拿「上一周期同期」算出来的 */
    private String todoSentence(int done, int pending, int overdue) {
        LocalDate[] prev = prevRange();
        int before = countDone(prev[0], prev[1]);

        String cur = SCOPE_NAMES[scope];
        String last = SCOPE_PREV_NAMES[scope];
        StringBuilder sb = new StringBuilder();
        sb.append(cur).append("完成 ").append(done).append(" 项");

        int diff = done - before;
        if (diff > 0) {
            sb.append("，比").append(last).append("多 ").append(diff).append(" 项");
        } else if (diff < 0) {
            sb.append("，比").append(last).append("少 ").append(-diff).append(" 项");
        } else {
            sb.append("，与").append(last).append("持平");
        }
        if (before == 0) {
            sb.append("（").append(last).append("为 0）");
        }
        if (overdue > 0) {
            sb.append("；另有 ").append(overdue).append(" 项已逾期未完成");
        } else if (pending > 0) {
            sb.append("；还剩 ").append(pending).append(" 项待处理");
        }
        sb.append("。");
        return sb.toString();
    }

    // ───────────────── 第二块：记账 ─────────────────

    private void buildMoneySection() {
        LocalDate a = rangeStart();
        LocalDate b = rangeEnd();

        double income = incomeIn(a, b);
        double expense = expenseIn(a, b);
        double balance = income - expense;

        content.addView(Widgets.sectionHeader(this, "记账", entries.size(), Theme.accent(this)));

        if (entries.isEmpty()) {
            content.addView(Widgets.empty(this, "还没有账目数据\n先去「账目」页记一笔吧"));
            return;
        }

        addSpaced(content, twoCols(
                numCard("收入", Money.yuan(income), R.color.money_in),
                numCard("支出", Money.yuan(expense), R.color.money_out)), 0);
        addSpaced(content, numCardColored("结余",
                Money.yuan(balance), Ui.color(this, balance >= 0 ? R.color.money_in : R.color.money_out)), 5);

        addSpaced(content, buildTop5Card(expense), 10);
        addSpaced(content, summaryBox(moneySentence(income, expense)), 10);
    }

    private double incomeIn(LocalDate a, LocalDate b) {
        double sum = 0;
        for (Entry e : entries) {
            if (e.type == Entry.INCOME && inRange(Entry.parseDay(e.date), a, b)) {
                sum += e.amount;
            }
        }
        return sum;
    }

    private double expenseIn(LocalDate a, LocalDate b) {
        double sum = 0;
        for (Entry e : entries) {
            if (e.type == Entry.EXPENSE && inRange(Entry.parseDay(e.date), a, b)) {
                sum += e.amount;
            }
        }
        return sum;
    }

    /** 支出 Top 5 大类（含条形图 + 金额 + 占比） */
    private View buildTop5Card(double expenseTotal) {
        LinearLayout card = Widgets.card(this);
        card.addView(Ui.text(this, "支出 Top 5 分类", 12f, R.color.text_dim, true));

        LinkedHashMap<String, Double> totals = bigExpenseTotals();
        if (totals.isEmpty()) {
            card.addView(Widgets.empty(this, "这段时间没有支出记录"));
            return card;
        }
        ArrayList<Map.Entry<String, Double>> list = new ArrayList<>(totals.entrySet());
        Collections.sort(list, (x, y) -> Double.compare(y.getValue(), x.getValue()));

        int n = Math.min(5, list.size());
        int accent = Theme.accent(this);
        int outColor = Ui.color(this, R.color.money_out);

        for (int i = 0; i < n; i++) {
            Map.Entry<String, Double> en = list.get(i);
            double amount = en.getValue();
            double pct = expenseTotal > 0 ? amount * 100.0 / expenseTotal : 0;

            // 第一行：大类名 + 金额
            LinearLayout head = Ui.row(this);
            TextView name = Ui.text(this, en.getKey(), 13f, R.color.text);
            name.setMaxLines(1);
            head.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(Ui.textColored(this, Money.yuan(amount), 13f, outColor, true));
            addSpaced(card, head, i == 0 ? 8 : 11);

            // 第二行：横向条 + 占比
            LinearLayout barRow = Ui.row(this);
            LinearLayout track = Ui.row(this);
            track.setBackground(Ui.pill(Ui.color(this, R.color.surface_alt)));

            // 用权重拼出「已填 + 未填」，两端都是圆角
            float fillWeight = (float) Math.max(0.001, Math.min(100.0, pct));
            View fill = new View(this);
            fill.setBackground(Ui.bg(accent, Ui.dp(this, 4)));
            track.addView(fill, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, fillWeight));
            track.addView(new View(this), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.001f, 100f - fillWeight)));
            barRow.addView(track, new LinearLayout.LayoutParams(0, Ui.dp(this, 7), 1f));

            TextView pv = Ui.text(this, String.format(Locale.CHINA, "%.1f%%", pct), 11f, R.color.text_dim);
            pv.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = Ui.dp(this, 8);
            barRow.addView(pv, plp);
            addSpaced(card, barRow, 6);
        }
        return card;
    }

    /**
     * 各大类的支出合计。
     * 本月 / 今年可以直接用 {@link EntryStore#byCategory(String, int)}（它按前缀过滤）；
     * 本周可能跨月，前缀过滤不够用，所以按日期自己聚合一遍，口径与 byCategory 保持一致。
     */
    private LinkedHashMap<String, Double> bigExpenseTotals() {
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        if (scope == SCOPE_WEEK) {
            LocalDate a = rangeStart();
            LocalDate b = rangeEnd();
            for (Entry e : entries) {
                if (e.type != Entry.EXPENSE || !inRange(Entry.parseDay(e.date), a, b)) {
                    continue;
                }
                String big = e.category == null || e.category.isEmpty() ? "其他" : e.category;
                Double cur = out.get(big);
                out.put(big, (cur == null ? 0 : cur) + e.amount);
            }
            return out;
        }
        String prefix = scope == SCOPE_MONTH
                ? EntryStore.monthPrefix(YearMonth.now())
                : LocalDate.now().getYear() + "-";
        LinkedHashMap<String, LinkedHashMap<String, Double>> by =
                entryStore.byCategory(prefix, Entry.EXPENSE);
        for (Map.Entry<String, LinkedHashMap<String, Double>> en : by.entrySet()) {
            double sum = 0;
            for (Double v : en.getValue().values()) {
                sum += v;
            }
            if (sum > 0) {
                out.put(en.getKey(), sum);
            }
        }
        return out;
    }

    /** 和上一周期同期比一句话 —— 数字全部现算 */
    private String moneySentence(double income, double expense) {
        LocalDate[] prev = prevRange();
        double before = expenseIn(prev[0], prev[1]);
        String cur = SCOPE_NAMES[scope];
        String last = SCOPE_PREV_NAMES[scope];

        if (expense <= 0 && before <= 0) {
            return cur + "还没有支出记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(cur).append("支出 ").append(Money.yuan(expense));
        if (before <= 0) {
            sb.append("，").append(last).append("没有支出记录");
        } else {
            double diff = expense - before;
            if (Math.abs(diff) < 0.005) {
                sb.append("，与").append(last).append("基本持平");
            } else {
                sb.append("，比").append(last).append(diff > 0 ? "多 " : "少 ")
                        .append(Money.yuan(Math.abs(diff)))
                        .append(String.format(Locale.CHINA, "（%+.1f%%）", diff / before * 100));
            }
        }
        if (income > 0) {
            sb.append("；").append(cur).append("收入 ").append(Money.yuan(income));
            double balance = income - expense;
            sb.append(balance >= 0 ? "，结余为正。" : "，支出多于收入。");
        } else {
            sb.append("。");
        }
        return sb.toString();
    }

    // ───────────────── 第三块：习惯 ─────────────────

    private void buildHabitSection() {
        content.addView(Widgets.sectionHeader(this, "习惯", 0, Theme.accent(this)));

        LocalDate today = LocalDate.now();
        boolean todayMarked = hasRecord(today);

        // 从今天往前数：今天有记录就从今天数，没有就从昨天数（并如实说明）
        int streak = 0;
        LocalDate cur = todayMarked ? today : today.minusDays(1);
        while (hasRecord(cur) && streak < 3650) {
            streak++;
            cur = cur.minusDays(1);
        }

        LinearLayout card = Widgets.card(this);
        LinearLayout head = Ui.row(this);
        TextView big = Ui.textColored(this, String.valueOf(streak), 30f, Theme.accent(this), true);
        head.addView(big);
        TextView unit = Ui.text(this, "  天", 13f, R.color.text_dim);
        head.addView(unit);
        head.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(this, "连续记录天数", 12f, R.color.text_dim));
        card.addView(head);

        TextView hint = Ui.text(this, todayMarked
                        ? "今天已经有记录了，继续保持。"
                        : (streak > 0 ? "今天还没记录，连续天数算到昨天为止。" : "今天还没有记录，从今天开始吧。"),
                12f, R.color.text_dim);
        hint.setLineSpacing(0f, 1.25f);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(hint);

        // 最近 7 天的打点：有记录的亮起
        LinearLayout dots = Ui.row(this);
        for (int i = 6; i >= 0; i--) {
            LocalDate day = today.minusDays(i);
            boolean on = hasRecord(day);

            LinearLayout col = Ui.column(this);
            col.setGravity(Gravity.CENTER_HORIZONTAL);

            int size = Ui.dp(this, 10);
            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
            dlp.bottomMargin = Ui.dp(this, 5);
            dot.setLayoutParams(dlp);
            dot.setBackground(Ui.pill(on ? Theme.accent(this) : Ui.color(this, R.color.divider)));
            col.addView(dot);

            TextView wd = Ui.text(this, Fmt.WD[day.getDayOfWeek().getValue() - 1],
                    9.5f, on ? R.color.text : R.color.text_dim);
            col.addView(wd);
            dots.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        addSpaced(card, dots, 12);

        // 本范围内有几天有记录（跟着范围切换变）
        int active = 0;
        LocalDate a = rangeStart();
        for (LocalDate day = a; !day.isAfter(today); day = day.plusDays(1)) {
            if (hasRecord(day)) {
                active++;
            }
        }
        TextView scopeLine = Ui.text(this,
                SCOPE_NAMES[scope] + "有记录 " + active + " 天（任务完成或记了账都算）",
                11f, R.color.text_dim);
        scopeLine.setPadding(0, Ui.dp(this, 10), 0, 0);
        card.addView(scopeLine);

        addSpaced(content, card, 0);
    }

    /** 某天是否有「完成的任务」或「账目」 */
    private boolean hasRecord(LocalDate day) {
        return day != null && activeDays.contains(Entry.dayOf(day));
    }

    // ───────────────── 小零件 ─────────────────

    /** 数字卡：小标签在上，大数字在下 */
    private LinearLayout numCard(String label, String value, int valueColorRes) {
        return numCard(label, Ui.text(this, value, 20f, valueColorRes, true));
    }

    private LinearLayout numCardColored(String label, String value, int color) {
        return numCard(label, Ui.textColored(this, value, 20f, color, true));
    }

    private LinearLayout numCard(String label, TextView value) {
        LinearLayout card = Widgets.card(this);
        card.addView(Ui.text(this, label, 11.5f, R.color.text_dim));
        value.setPadding(0, Ui.dp(this, 3), 0, 0);
        value.setMaxLines(1);
        card.addView(value);
        return card;
    }

    /** 两列等宽 */
    private LinearLayout twoCols(View left, View right) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l1.rightMargin = Ui.dp(this, 5);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.leftMargin = Ui.dp(this, 5);
        row.addView(left, l1);
        row.addView(right, l2);
        return row;
    }

    /** 一句话总结用的浅底强调色盒子（深色模式会自动跟着变） */
    private View summaryBox(String text) {
        TextView t = Ui.text(this, text, 13f, R.color.text);
        t.setLineSpacing(0f, 1.3f);
        t.setBackground(Ui.bg(Theme.accentSoft(this), Ui.dp(this, 12)));
        Ui.padDp(t, this, 12, 10, 12, 10);
        return t;
    }

    private void addSpaced(LinearLayout host, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        host.addView(v, lp);
    }
}
