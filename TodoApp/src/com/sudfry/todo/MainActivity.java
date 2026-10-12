package com.sudfry.todo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sudfry.todo.model.Entry;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.reminder.Reminders;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.ui.EntryRow;
import com.sudfry.todo.ui.MonthView;
import com.sudfry.todo.ui.MoneyPanel;
import com.sudfry.todo.ui.Sheet;
import com.sudfry.todo.ui.TaskRow;
import com.sudfry.todo.ui.TimelineView;
import com.sudfry.todo.ui.WeekView;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.AppPrefs;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;
import com.sudfry.todo.util.Urgency;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 主界面：今天 / 日历 / 待办 / 账目 四个视图，横屏自动变成双栏。 */
public class MainActivity extends Activity implements TaskRow.Actions, MoneyPanel.Host {

    private static final String PREFS = "todo_prefs";
    private static final String KEY_ORIENT = "orientation";   // 0 自动 1 竖屏 2 横屏

    private static final String[] TAB_NAMES = {"今天", "日历", "待办", "账目"};
    private static final int TAB_COUNT = 4;

    private TodoStore store;
    private EntryStore entryStore;
    private AppPrefs prefs;

    private FrameLayout rootFrame;
    private LinearLayout contentHost;
    private TextView bigTitle;
    private TextView subTitle;
    private TextView orientBtn;
    private TextView[] segs = new TextView[TAB_COUNT];

    private int tab = 0;
    private int moneySub = 0;                 // 账目页的子页：0 记一笔 1 流水 2 结算 3 分析
    private int listFilter = 0;
    private String listQuery = "";
    private String tagFilter = "";
    private LocalDate calSelected = LocalDate.now();
    private MonthView monthView;
    private TimelineView timeline;
    private ScrollView tabScroll;
    private LinearLayout listGroups;

    private View undoBar;
    private Runnable hideUndo;
    /**
     * 每个标签、每个分栏各自的滚动位置。
     * 横屏下「今天」和「日历」都是左右两个独立滚动区，只记一个是不够的 ——
     * 之前拖动完回到顶部，就是因为只记了其中一个而另一个被重建了。
     */
    private final int[][] paneScroll = new int[TAB_COUNT][3];
    /** 切标签时给新内容一个淡入+上移的入场，数据刷新时不动。 */
    private boolean animateNext;
    private int lastSegTab = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // 只有「今天」页需要跟着时间走。其他页面重建会打断用户操作
            // （比如记一笔时刚输入的金额会被清掉），所以不动。
            if (tab == 0) {
                refresh(false);
            }
            handler.postDelayed(this, 30000);
        }
    };

    // ─────────────────────────── 生命周期 ───────────────────────────

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = AppPrefs.get(this);
        Theme.applyNightMode(this);
        applyOrientation();
        store = TodoStore.get(this);
        entryStore = EntryStore.get(this);
        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
        restoreState(b);
        // 自动备份：到点了就悄悄存一份（数据很小，同步做就行）
        try {
            com.sudfry.todo.util.Backup.maybeAuto(this);
        } catch (Throwable ignored) {
        }
        Reminders.ensureChannel(this);
        Reminders.syncAll(this);
        // 通知栏快捷记账：开了就挂着常驻通知，关了立刻撤掉
        try {
            if (prefs.quickNotify()) {
                com.sudfry.todo.notify.QuickNotifier.show(this);
            } else {
                com.sudfry.todo.notify.QuickNotifier.hide(this);
            }
        } catch (Throwable ignored) {
        }
        askNotificationPermission();
        handler.postDelayed(() -> {
            if (tab == 0 && !restored) {
                refresh(true);
            }
        }, 400);
        openFromNotification(getIntent());
    }

    private boolean restored;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // 系统重建（切深浅色、旋转屏幕）后要回到原来看的那一页和位置
        capturePaneScroll();
        out.putInt("tab", tab);
        out.putInt("moneySub", moneySub);
        int[] flat = new int[TAB_COUNT * 3];
        for (int i = 0; i < TAB_COUNT; i++) {
            for (int j = 0; j < 3; j++) {
                flat[i * 3 + j] = paneScroll[i][j];
            }
        }
        out.putIntArray("paneScroll", flat);
    }

    private void restoreState(Bundle b) {
        if (b == null) {
            return;
        }
        restored = true;
        tab = b.getInt("tab", 0);
        moneySub = b.getInt("moneySub", 0);
        int[] flat = b.getIntArray("paneScroll");
        if (flat != null) {
            for (int i = 0; i < TAB_COUNT && i * 3 + 2 < flat.length; i++) {
                for (int j = 0; j < 3; j++) {
                    paneScroll[i][j] = flat[i * 3 + j];
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromNotification(intent);
    }

    private void openFromNotification(Intent intent) {
        if (intent == null) {
            return;
        }
        long id = intent.getLongExtra("todo_id", 0);
        if (id > 0) {
            intent.removeExtra("todo_id");
            handler.postDelayed(() -> openEditor(id, -1), 300);
        }
    }

    private String lastStyle;
    private LinearLayout historyRow;
    /** 撤销条专用浮层。 */
    private FrameLayout overlayHost;

    @Override
    protected void onResume() {
        super.onResume();
        // 皮肤风格是在设置页改的，回到主界面要按新圆角/描边重画一遍
        String s = prefs.style();
        if (lastStyle != null && !lastStyle.equals(s)) {
            lastStyle = s;
            refresh(false);
        } else {
            lastStyle = s;
        }
        refresh(false);
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 30000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private int orientMode() {
        return prefs.orientation();
    }

    private void applyOrientation() {
        int mode = orientMode();
        if (mode == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if (mode == 2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void cycleOrientation() {
        int mode = (orientMode() + 1) % 3;
        prefs.setOrientation(mode);
        applyOrientation();
        String[] names = {"自动（跟随手机）", "锁定竖屏", "锁定横屏"};
        Toast.makeText(this, "屏幕方向：" + names[mode], Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> {
            if (orientBtn != null) {
                orientBtn.setText(orientLabel());
            }
        }, 200);
    }

    private String orientLabel() {
        switch (orientMode()) {
            case 1:
                return "竖";
            case 2:
                return "横";
            default:
                return "自";
        }
    }

    // ─────────────────────────── 外壳 ───────────────────────────

    private View buildUi() {
        float d = getResources().getDisplayMetrics().density;

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.color(this, R.color.bg));

        LinearLayout col = Ui.column(this);
        col.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = Ui.column(this);
        header.setBackgroundColor(Ui.color(this, R.color.bg));
        Ui.padDp(header, this, 16, 12, 12, 6);

        LinearLayout titleRow = Ui.row(this);
        LinearLayout titleCol = Ui.column(this);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bigTitle = Ui.text(this, "今天", 23f, R.color.text, true);
        titleCol.addView(bigTitle);
        subTitle = Ui.text(this, "", 12.5f, R.color.text_dim);
        subTitle.setPadding(0, Ui.dp(this, 3), 0, 0);
        titleCol.addView(subTitle);
        titleRow.addView(titleCol);

        TextView settingsBtn = headerButton("⚙", "设置");
        settingsBtn.setOnClickListener(v -> {
            Sfx.tap(v);
            startActivity(new Intent(this, SettingsActivity.class));
        });
        titleRow.addView(settingsBtn);

        TextView statsBtn = headerButton("▦", "统计报表");
        statsBtn.setOnClickListener(v -> {
            Sfx.tap(v);
            startActivity(new Intent(this, StatsActivity.class));
        });
        titleRow.addView(statsBtn);

        TextView syncBtn = headerButton("⇄", "数据与同步");
        syncBtn.setOnClickListener(v -> {
            Sfx.tap(v);
            startActivity(new Intent(this, SyncActivity.class));
        });
        titleRow.addView(syncBtn);

        orientBtn = headerButton(orientLabel(), "屏幕方向：自动 / 竖屏 / 横屏");
        orientBtn.setOnClickListener(v -> {
            Sfx.tap(v);
            cycleOrientation();
        });
        titleRow.addView(orientBtn);
        header.addView(titleRow);
        col.addView(header);

        LinearLayout segBox = Ui.row(this);
        segBox.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 14 * d,
                Ui.color(this, R.color.divider), 1 * d));
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.leftMargin = Ui.dp(this, 14);
        sblp.rightMargin = Ui.dp(this, 14);
        sblp.topMargin = Ui.dp(this, 4);
        segBox.setLayoutParams(sblp);
        Ui.padDp(segBox, this, 4, 4, 4, 4);
        String[] names = TAB_NAMES;
        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            TextView t = Ui.text(this, names[i], 13f, R.color.text_dim, false);
            t.setGravity(Gravity.CENTER);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f));
            t.setOnClickListener(v -> {
                Sfx.tap(v);
                tab = idx;
                animateNext = true;
                refresh(false);
            });
            segs[i] = t;
            segBox.addView(t);
        }
        col.addView(segBox);

        contentHost = Ui.column(this);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(contentHost);
        rootFrame.addView(col);

        TextView fab = Ui.text(this, "＋", 26f, R.color.on_color, true);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(Ui.pill(Theme.accent(this)));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                Ui.dp(this, 56), Ui.dp(this, 56));
        flp.gravity = Gravity.END | Gravity.BOTTOM;
        flp.rightMargin = Ui.dp(this, 18);
        flp.bottomMargin = Ui.dp(this, 22);
        fab.setLayoutParams(flp);
        fab.setElevation(Ui.dp(this, 6));
        Ui.press(fab);
        fab.setOnClickListener(v -> openEditor(0, -1));
        rootFrame.addView(fab);

        return rootFrame;
    }

    private TextView headerButton(String label, String desc) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = Ui.text(this, label, 15f, R.color.accent, true);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(desc);
        t.setBackground(Ui.pill(Ui.color(this, R.color.accent_soft)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38));
        lp.leftMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        Ui.press(t);
        return t;
    }

    private void openEditor(long id, int minute) {
        Intent i = new Intent(this, EditActivity.class);
        if (id > 0) {
            i.putExtra("id", id);
        } else {
            LocalDate day = tab == 1 ? calSelected : LocalDate.now();
            if (day != null) {
                i.putExtra("day", Todo.toMillis(day.atStartOfDay()));
            }
            if (minute >= 0) {
                i.putExtra("minute", minute);
            }
        }
        startActivity(i);
    }

    /** 竖屏一根滚动条，横屏并排两栏。 */
    private ScrollView scrollPane(View content, float weight) {
        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, weight);
        sv.setLayoutParams(lp);
        sv.addView(content);
        return sv;
    }

    // ─────────────────────────── 刷新 ───────────────────────────

    private void refresh(boolean scrollToNow) {
        long now = System.currentTimeMillis();
        List<Todo> all = store.all();
        Collections.sort(all, (a, b) -> Urgency.compare(a, b, now));

        for (int i = 0; i < TAB_COUNT; i++) {
            boolean on = i == tab;
            // 选中态做一次淡入，切换时不会「啪」地跳过去
            android.graphics.drawable.Drawable target = on ? Ui.pill(Theme.accent(this))
                    : Ui.pill(0x00000000);
            android.graphics.drawable.Drawable cur = segs[i].getBackground();
            if (cur != null && lastSegTab != tab) {
                android.graphics.drawable.TransitionDrawable td =
                        new android.graphics.drawable.TransitionDrawable(
                                new android.graphics.drawable.Drawable[]{cur, target});
                td.setCrossFadeEnabled(true);
                segs[i].setBackground(td);
                td.startTransition(200);
            } else {
                segs[i].setBackground(target);
            }
            segs[i].setTextColor(on ? Theme.onAccent(this) : Ui.color(this, R.color.text_dim));
            segs[i].setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
        lastSegTab = tab;

        // 记住当前标签里所有分栏的滚动位置，重建后逐栏恢复
        capturePaneScroll();

        contentHost.removeAllViews();
        timeline = null;
        tabScroll = null;
        if (tab == 0) {
            buildToday(all, now);
        } else if (tab == 1) {
            buildCalendar(all, now);
        } else if (tab == 2) {
            buildList(all, now);
        } else {
            buildMoney();
        }

        if (!scrollToNow) {
            restorePaneScroll();
        }

        // 切标签时给新内容一个入场动画（数据刷新时不动，免得每勾一下都在动）
        if (animateNext) {
            animateNext = false;
            java.util.ArrayList<ScrollView> svs = new java.util.ArrayList<>();
            collectScrolls(contentHost, svs);
            if (svs.isEmpty() && contentHost.getChildCount() > 0) {
                Ui.enter(contentHost.getChildAt(0));
            } else {
                for (int i = 0; i < svs.size(); i++) {
                    Ui.enter(svs.get(i), i * 40);
                }
            }
        }

        if (scrollToNow && tabScroll != null && timeline != null) {            final ScrollView sv = tabScroll;
            sv.post(() -> {
                View child = sv.getChildCount() > 0 ? sv.getChildAt(0) : null;
                int top = child == null ? 0 : offsetIn(timeline, child);
                int nowY = top + (int) timeline.yForNow();
                int sh = sv.getHeight();
                if (nowY > sh - Ui.dp(this, 150)) {
                    sv.scrollTo(0, Math.max(0, nowY - (int) (sh * 0.55f)));
                }
            });
        }
        Ui.pressAll(contentHost);
        syncWidget();
    }

    /** 收集当前标签里所有的 ScrollView（横屏最多两个分栏）。 */
    private void collectScrolls(View v, java.util.List<ScrollView> out) {
        if (v instanceof ScrollView) {
            out.add((ScrollView) v);
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectScrolls(g.getChildAt(i), out);
            }
        }
    }

    private void capturePaneScroll() {
        java.util.ArrayList<ScrollView> svs = new java.util.ArrayList<>();
        collectScrolls(contentHost, svs);
        for (int i = 0; i < Math.min(paneScroll[tab].length, svs.size()); i++) {
            paneScroll[tab][i] = svs.get(i).getScrollY();
        }
    }

    private void restorePaneScroll() {
        java.util.ArrayList<ScrollView> svs = new java.util.ArrayList<>();
        collectScrolls(contentHost, svs);
        for (int i = 0; i < Math.min(paneScroll[tab].length, svs.size()); i++) {
            final int y = paneScroll[tab][i];
            if (y > 0) {
                final ScrollView sv = svs.get(i);
                sv.post(() -> sv.scrollTo(0, y));
            }
        }
    }

    private int offsetIn(View v, View ancestor) {        int y = 0;
        View cur = v;
        int guard = 0;
        while (cur != null && cur != ancestor && guard++ < 40) {
            y += cur.getTop();
            Object parent = cur.getParent();
            if (!(parent instanceof View)) {
                break;
            }
            cur = (View) parent;
        }
        return y;
    }

    private int pendingCount(List<Todo> all) {
        int n = 0;
        for (Todo t : all) {
            if (!t.done) {
                n++;
            }
        }
        return n;
    }

    // ─────────────── 视图一：今天 + 时间轴 ───────────────

    private void buildToday(List<Todo> all, long now) {
        final LocalDate today = LocalDate.now();

        int overdue = 0, todayCount = 0, soon = 0, todayDone = 0;
        for (Todo t : all) {
            int lv = Urgency.level(t, now);
            if (t.done) {
                if (t.due > 0 && Todo.toLocal(t.due).toLocalDate().equals(today)) {
                    todayDone++;
                }
                continue;
            }
            if (lv == Urgency.OVERDUE) {
                overdue++;
            } else if (lv == Urgency.TODAY) {
                todayCount++;
            } else if (lv == Urgency.SOON) {
                soon++;
            }
        }

        bigTitle.setText("今天");
        StringBuilder sb = new StringBuilder();
        sb.append(Fmt.date(today)).append(" ").append(Fmt.wd(today)).append(" · ");
        if (overdue > 0) {
            sb.append("有 ").append(overdue).append(" 项已逾期");
        } else if (todayCount > 0) {
            sb.append(todayCount).append(" 项待完成");
        } else {
            sb.append("今天没有压着的任务");
        }
        if (isLandscape()) {
            sb.append(" · 横屏双栏");
        }
        subTitle.setText(sb.toString());
        subTitle.setTextColor(Ui.color(this, overdue > 0 ? R.color.overdue : R.color.text_dim));

        // 左栏：概览 + 逾期 + 不限时间
        LinearLayout left = Ui.column(this);
        Ui.padDp(left, this, 14, 8, 8, 90);

        LinearLayout card = Widgets.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "今天要处理 " + (overdue + todayCount) + " 项", 15f,
                R.color.text, true));
        head.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int totalToday = overdue + todayCount + todayDone;
        head.addView(Ui.text(this, todayDone + " / " + totalToday + " 已完成", 12.5f,
                R.color.text_dim));
        card.addView(head);

        LinearLayout badges = Ui.row(this);
        badges.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        if (overdue > 0) {
            badges.addView(badge("已逾期 " + overdue, R.color.overdue));
        }
        if (todayCount > 0) {
            badges.addView(badge("今天 " + todayCount, R.color.today));
        }
        if (soon > 0) {
            badges.addView(badge("3 天内 " + soon, R.color.soon));
        }
        if (badges.getChildCount() == 0) {
            badges.addView(badge("暂无紧迫任务", R.color.later));
        }
        card.addView(badges);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(Math.max(1, totalToday));
        pb.setProgress(todayDone);
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.accent(this)));
        card.addView(pb);

        int[] week = store.weeklyStats(now);
        TextView wk = Ui.text(this, "本周已完成 " + week[0] + " 项", 12f, R.color.text_dim);
        wk.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(wk);
        left.addView(card);

        // 今日预算卡（来自日簿的每日预算）
        left.addView(budgetCard());

        List<Todo> urgent = new ArrayList<>();
        for (Todo t : all) {
            if (!t.done && Urgency.level(t, now) == Urgency.OVERDUE) {
                urgent.add(t);
            }
        }
        if (!urgent.isEmpty()) {
            left.addView(Widgets.sectionHeader(this, "需要马上处理", urgent.size(), Theme.urgency(this, Urgency.OVERDUE)));
            for (Todo t : urgent) {
                left.addView(rowFor(t, now, true));
            }
        }

        List<Todo> noTime = new ArrayList<>();
        for (Todo t : all) {
            if (!t.done && t.due > 0 && !t.hasTime
                    && Todo.toLocal(t.due).toLocalDate().equals(today)) {
                noTime.add(t);
            }
        }
        if (!noTime.isEmpty()) {
            left.addView(Widgets.sectionHeader(this, "今天 · 不限时间", noTime.size(), Theme.urgency(this, Urgency.TODAY)));
            for (Todo t : noTime) {
                left.addView(rowFor(t, now, false));
            }
        }

        // 右栏（横屏）或下栏（竖屏）：时间轴
        LinearLayout right = Ui.column(this);
        Ui.padDp(right, this, isLandscape() ? 8 : 14, 8, isLandscape() ? 14 : 14, 90);
        right.addView(Widgets.sectionHeader(this, "一天时间轴", countTimed(all, today), Theme.accent(this)));

        timeline = new TimelineView(this);
        timeline.setListener(new TimelineView.Listener() {
            @Override
            public void onTaskClick(Todo t) {
                openEditor(t.id, -1);
            }

            @Override
            public void onEmptyClick(int minute) {
                openEditor(0, minute);
            }

            @Override
            public void onTaskMoved(Todo t, int minuteOfDay) {
                moveTask(t, null, minuteOfDay);
            }
        });
        timeline.setData(all, today);        LinearLayout tlCard = Ui.column(this);
        tlCard.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 16 * d(),
                Ui.color(this, R.color.divider), d()));
        tlCard.setClipToOutline(true);
        tlCard.addView(timeline);
        right.addView(tlCard);
        maybeShowDragHint();

        // 今天的收支流水
        right.addView(dayMoneySection(today, false));

        if (isLandscape()) {
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.TOP);
            ScrollView l = scrollPane(left, 1f);
            ScrollView r = scrollPane(right, 1f);
            tabScroll = r;
            row.addView(l);
            row.addView(r);
            contentHost.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout col = Ui.column(this);
            col.addView(left);
            col.addView(right);
            ScrollView sv = new ScrollView(this);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sv.addView(col);
            tabScroll = sv;
            contentHost.addView(sv);
        }
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    private int countTimed(List<Todo> all, LocalDate day) {
        int n = 0;
        for (Todo t : all) {
            if (t.due > 0 && Todo.toLocal(t.due).toLocalDate().equals(day)) {
                n++;
            }
        }
        return n;
    }

    // ─────────────── 日历的两种形态：月 / 周 ───────────────

    private LocalDate weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY);

    private interface Pick {
        void on(int index);
    }

    /** 小分段控件（月/周、列表/四象限）。 */
    private View viewToggle(String[] names, int active, Pick cb) {
        LinearLayout box = Ui.row(this);
        box.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 12 * d(),
                Ui.color(this, R.color.divider), d()));
        Ui.padDp(box, this, 3, 3, 3, 3);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        blp.bottomMargin = Ui.dp(this, 6);
        box.setLayoutParams(blp);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on = i == active;
            TextView t = Ui.textColored(this, names[i], 12.5f,
                    on ? Theme.onAccent(this) : Ui.color(this, R.color.text_dim), on);
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
            if (on) {
                t.setBackground(Ui.pill(Theme.accent(this)));
            }
            t.setOnClickListener(v -> {
                Sfx.tap(v);
                if (idx != active) {
                    cb.on(idx);
                }
            });
            box.addView(t);
        }
        return box;
    }

    private void buildMonthPane(LinearLayout left, List<Todo> all,
                                YearMonth startMonth, long now) {
        final TextView monthLabel = Ui.text(this, Fmt.monthTitle(startMonth), 16f,
                R.color.text, true);
        LinearLayout bar = Ui.row(this);
        TextView prev = Ui.text(this, "‹", 22f, R.color.accent);
        prev.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        monthLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        monthLabel.setGravity(Gravity.CENTER);
        TextView next = Ui.text(this, "›", 22f, R.color.accent);
        next.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        TextView todayBtn = Ui.text(this, "今天", 13f, R.color.accent);
        todayBtn.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);
        bar.addView(prev);
        bar.addView(monthLabel);
        bar.addView(next);
        bar.addView(todayBtn);
        left.addView(bar);

        monthView = new MonthView(this);
        monthView.setItems(all);
        monthView.setMoneySource(date -> entryStore.dayFlags(date));
        monthView.setSelected(calSelected);
        monthView.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 16 * d(),
                Ui.color(this, R.color.divider), d()));
        monthView.setClipToOutline(true);
        monthView.setListener(new MonthView.Listener() {
            @Override
            public void onDayClick(LocalDate day) {
                calSelected = day;
                refresh(false);
            }

            @Override
            public void onMonthChanged(YearMonth ym) {
                monthLabel.setText(Fmt.monthTitle(ym));
            }
        });
        left.addView(monthView);
        prev.setOnClickListener(v -> monthView.shift(-1));
        next.setOnClickListener(v -> monthView.shift(1));
        todayBtn.setOnClickListener(v -> {
            calSelected = LocalDate.now();
            monthView.setSelected(calSelected);
            monthView.goTo(YearMonth.now());
            monthLabel.setText(Fmt.monthTitle(YearMonth.now()));
            refresh(false);
        });
    }

    private void buildWeekPane(LinearLayout left, List<Todo> all) {
        LinearLayout bar = Ui.row(this);
        TextView prev = Ui.text(this, "‹", 22f, R.color.accent);
        prev.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        final TextView label = Ui.text(this, weekRangeText(), 14.5f, R.color.text, true);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        label.setGravity(Gravity.CENTER);
        TextView next = Ui.text(this, "›", 22f, R.color.accent);
        next.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        TextView todayBtn = Ui.text(this, "本周", 13f, R.color.accent);
        todayBtn.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);
        bar.addView(prev);
        bar.addView(label);
        bar.addView(next);
        bar.addView(todayBtn);
        left.addView(bar);

        WeekView wv = new WeekView(this);
        wv.setWeek(weekStart);
        wv.setData(all);
        wv.setListener(new WeekView.Listener() {
            @Override
            public void onTaskClick(Todo t) {
                openEditor(t.id, -1);
            }

            @Override
            public void onEmptyClick(LocalDate day, int minute) {
                calSelected = day;
                openEditorOn(day, minute);
            }

            @Override
            public void onTaskMoved(Todo t, LocalDate day, int minute) {
                moveTask(t, day, minute);
            }
        });
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 16 * d(),
                Ui.color(this, R.color.divider), d()));
        card.setClipToOutline(true);
        card.addView(wv);
        left.addView(card);

        prev.setOnClickListener(v -> {
            Sfx.tap(v);
            weekStart = weekStart.minusWeeks(1);
            refresh(false);
        });
        next.setOnClickListener(v -> {
            Sfx.tap(v);
            weekStart = weekStart.plusWeeks(1);
            refresh(false);
        });
        todayBtn.setOnClickListener(v -> {
            Sfx.tap(v);
            weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
            refresh(false);
        });
    }

    private String weekRangeText() {
        LocalDate end = weekStart.plusDays(6);
        return Fmt.date(weekStart) + " – " + Fmt.date(end);
    }

    // ─────────────── 四象限「重要 - 紧急」矩阵 ───────────────

    private void buildMatrix(LinearLayout col, List<Todo> all, long now) {
        ArrayList<ArrayList<Todo>> q = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            q.add(new ArrayList<>());
        }
        int done = 0;
        for (Todo t : all) {
            if (t.done) {
                done++;
                continue;
            }
            q.get(Urgency.quadrant(t, now)).add(t);
        }

        if (isLandscape()) {
            LinearLayout row1 = Ui.row(this);
            row1.setGravity(Gravity.TOP);
            row1.addView(matrixCell(0, q.get(0), now, 1f));
            row1.addView(matrixCell(1, q.get(1), now, 1f));
            col.addView(row1);
            LinearLayout row2 = Ui.row(this);
            row2.setGravity(Gravity.TOP);
            row2.addView(matrixCell(2, q.get(2), now, 1f));
            row2.addView(matrixCell(3, q.get(3), now, 1f));
            col.addView(row2);
        } else {
            for (int i = 0; i < 4; i++) {
                col.addView(matrixCell(i, q.get(i), now, 1f));
            }
        }
        if (done > 0) {
            col.addView(Widgets.empty(this, "另有 " + done + " 条已完成（切到「列表」可以看到）"));
        }
    }

    /** 一个象限。横屏时两两并排，所以左右各有间距。 */
    private View matrixCell(int index, List<Todo> list, long now, float weight) {
        LinearLayout box = Ui.column(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.topMargin = Ui.dp(this, 8);
        if (isLandscape()) {
            lp.rightMargin = index % 2 == 0 ? Ui.dp(this, 6) : 0;
            lp.leftMargin = index % 2 == 1 ? Ui.dp(this, 6) : 0;
        }
        box.setLayoutParams(lp);
        box.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 14 * d(),
                Ui.color(this, R.color.divider), 1 * d()));
        Ui.padDp(box, this, 12, 10, 12, 12);

        int color = quadrantColor(index);
        LinearLayout head = Ui.row(this);
        View bar = new View(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Ui.dp(this, 4), Ui.dp(this, 15));
        blp.rightMargin = Ui.dp(this, 7);
        bar.setLayoutParams(blp);
        bar.setBackground(Ui.bg(color, 2 * d()));
        head.addView(bar);
        head.addView(Ui.text(this, Urgency.QUADRANT_NAME[index], 13.5f, R.color.text, true));
        head.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cnt = Ui.textColored(this, String.valueOf(list.size()), 12.5f, color, true);
        head.addView(cnt);
        box.addView(head);

        TextView hint = Ui.text(this, Urgency.QUADRANT_HINT[index], 11f, R.color.text_dim);
        hint.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(hint);

        if (list.isEmpty()) {
            TextView empty = Ui.text(this, "空", 12f, R.color.text_dim);
            empty.setPadding(0, Ui.dp(this, 8), 0, 0);
            box.addView(empty);
            return box;
        }

        int shown = 0;
        for (Todo t : list) {
            if (shown >= 8) {
                box.addView(Ui.text(this, "还有 " + (list.size() - shown) + " 条…",
                        11.5f, R.color.text_dim));
                break;
            }
            box.addView(matrixRow(t, now));
            shown++;
        }
        return box;
    }

    private int quadrantColor(int index) {
        switch (index) {
            case 0:
                return Theme.urgency(this, Urgency.OVERDUE);
            case 1:
                return Theme.accent(this);
            case 2:
                return Theme.urgency(this, Urgency.TODAY);
            default:
                return Ui.color(this, R.color.text_dim);
        }
    }

    /** 矩阵里的一行：色点 + 标题 + 短倒计时。 */
    private View matrixRow(Todo t, long now) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 6);
        row.setLayoutParams(lp);

        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 8), Ui.dp(this, 8));
        dlp.rightMargin = Ui.dp(this, 8);
        dot.setLayoutParams(dlp);
        dot.setBackground(Ui.pill(Theme.urgency(this, Urgency.level(t, now))));
        row.addView(dot);

        TextView title = Ui.text(this, t.title == null || t.title.isEmpty() ? "（无标题）" : t.title,
                13f, R.color.text, false);
        title.setMaxLines(1);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(title);

        TextView right = Ui.text(this, Urgency.shortRemaining(t, now), 11f,
                R.color.text_dim);
        right.setPadding(Ui.dp(this, 6), 0, 0, 0);
        row.addView(right);

        row.setOnClickListener(v -> openEditor(t.id, -1));
        row.setOnLongClickListener(v -> {
            onMenu(t, v);
            return true;
        });
        return row;
    }

    // ───────────────── 搜索历史 ─────────────────

    /** 空搜索时显示最近搜过的词。 */
    private void renderSearchHistory(final EditText search) {
        if (historyRow == null) {
            return;
        }
        historyRow.removeAllViews();
        java.util.List<String> h = prefs.searchHistory();
        boolean empty = search.getText().toString().trim().isEmpty();
        if (!empty || h.isEmpty()) {
            historyRow.setVisibility(View.GONE);
            return;
        }
        historyRow.setVisibility(View.VISIBLE);
        historyRow.addView(Ui.text(this, "最近：", 12f, R.color.text_dim));
        for (final String w : h) {
            TextView chip = Widgets.chip(this, w, false, Theme.accent(this));
            chip.setOnClickListener(v -> {
                Sfx.tap(v);
                search.setText(w);
                search.setSelection(w.length());
                listQuery = w;
                renderGroups();
            });
            historyRow.addView(chip);
        }
        TextView clear = Ui.text(this, "清空", 12f, R.color.text_dim);
        clear.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        clear.setOnClickListener(v -> {
            Sfx.tap(v);
            prefs.clearSearchHistory();
            renderSearchHistory(search);
        });
        historyRow.addView(clear);
    }

    private void rememberSearch(String q) {
        String s = q == null ? "" : q.trim();
        if (s.length() < 2) {
            return;
        }
        prefs.addSearchHistory(s);
    }

    /** 账目里也搜一遍，结果放单独一组。 */
    private java.util.List<Entry> searchEntries(String q) {
        java.util.ArrayList<Entry> out = new java.util.ArrayList<>();
        if (q == null || q.trim().isEmpty()) {
            return out;
        }
        String w = q.trim().toLowerCase();
        for (Entry e : EntryStore.get(this).all()) {
            String hay = (e.category + " " + e.sub + " " + e.note + " " + e.platform + " "
                    + e.amount + " " + (e.isIncome() ? "收入" : "支出")).toLowerCase();
            if (hay.contains(w)) {
                out.add(e);
            }
        }
        java.util.Collections.sort(out, (x, y) -> y.date.compareTo(x.date));
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    /** 拖拽是长按触发的，第一次用给一句提示，之后不再打扰。 */
    private void maybeShowDragHint() {
        if (prefs.dragHintShown()) {
            return;
        }
        prefs.setDragHintShown(true);
        handler.postDelayed(() -> toast("长按时间轴上的色块可以拖动改时间；点一下是打开编辑"),
                700);
    }

    /** 拖拽排期：把任务挪到某天某时刻。day 为 null 表示只改时间。 */
    private void moveTask(Todo t, LocalDate day, int minuteOfDay) {
        Todo copy = t.copy();
        LocalDate targetDay = day != null ? day : Todo.toLocal(t.due).toLocalDate();
        int h = Math.max(0, Math.min(23, minuteOfDay / 60));
        int m = Math.max(0, minuteOfDay % 60);
        copy.due = Todo.toMillis(targetDay.atTime(h, m));
        copy.hasTime = true;
        store.update(copy);
        Reminders.schedule(this, copy);
        Sfx.click(this);
        toast("已挪到 " + Fmt.date(copy.due) + " " + Fmt.time(copy.due));
        refresh(false);
    }

    private void openEditorOn(LocalDate day, int minute) {        Intent i = new Intent(this, EditActivity.class);
        if (day != null) {
            i.putExtra("day", Todo.toMillis(day.atStartOfDay()));
        }
        if (minute >= 0) {
            i.putExtra("minute", minute);
        }
        startActivity(i);
    }

    private TextView badge(String text, int colorRes) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        t.setTextColor(0xFFFFFFFF);
        t.setBackground(Ui.pill(Ui.color(this, colorRes)));
        t.setPadding(Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 7);
        t.setLayoutParams(lp);
        return t;
    }

    // ─────────────── 视图二：日历 ───────────────

    private void buildCalendar(List<Todo> all, long now) {
        final boolean weekly = "week".equals(prefs.calendarView());
        bigTitle.setText("日历");
        subTitle.setText(weekly
                ? "按周看：七列 × 小时网格，色块 = 任务紧急度"
                : "有任务的日子用紧急色条标出，红条 = 已逾期"
                + (isLandscape() ? " · 左月历 / 右清单" : ""));

        final YearMonth startMonth = YearMonth.from(calSelected);

        // 左：月/周切换 + 日历本体
        LinearLayout left = Ui.column(this);
        Ui.padDp(left, this, 14, 8, isLandscape() ? 8 : 14, 90);

        left.addView(viewToggle(new String[]{"月", "周"}, weekly ? 1 : 0, i -> {
            prefs.setCalendarView(i == 1 ? "week" : "month");
            paneScroll[tab] = new int[3];
            refresh(false);
        }));

        if (weekly) {
            buildWeekPane(left, all);
        } else {
            buildMonthPane(left, all, startMonth, now);
        }

        // 右：选中日期的清单
        LinearLayout right = Ui.column(this);
        Ui.padDp(right, this, isLandscape() ? 8 : 14, 8, 14, 90);

        List<Todo> dayTasks = new ArrayList<>();
        for (Todo t : all) {
            if (t.due > 0 && Todo.toLocal(t.due).toLocalDate().equals(calSelected)) {
                dayTasks.add(t);
            }
        }
        LocalDate today = LocalDate.now();
        String title = Fmt.dayLabel(calSelected, today) + " · " + Fmt.date(calSelected) + " "
                + Fmt.wd(calSelected);
        right.addView(Widgets.sectionHeader(this, title, dayTasks.size(), Theme.accent(this)));
        if (dayTasks.isEmpty()) {
            right.addView(Widgets.empty(this, "这一天没有安排，点右下角 ＋ 添加"));
        } else {
            boolean showDate = !calSelected.equals(today);
            for (Todo t : dayTasks) {
                right.addView(rowFor(t, now, showDate));
            }
        }

        TextView addForDay = Ui.textColored(this, "为这一天添加任务", 14f, Theme.accent(this), true);
        addForDay.setGravity(Gravity.CENTER);
        addForDay.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 14 * d(), Theme.accent(this), d()));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        alp.topMargin = Ui.dp(this, 12);
        addForDay.setLayoutParams(alp);
        addForDay.setOnClickListener(v -> openEditor(0, -1));
        right.addView(addForDay);

        // 这一天的收支
        right.addView(dayMoneySection(calSelected, true));

        if (isLandscape()) {
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.TOP);
            row.addView(scrollPane(left, 1.15f));
            row.addView(scrollPane(right, 1f));
            contentHost.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout col = Ui.column(this);
            col.addView(left);
            col.addView(right);
            ScrollView sv = new ScrollView(this);
            sv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            sv.addView(col);
            tabScroll = sv;
            contentHost.addView(sv);
        }
    }

    // ─────────────── 视图三：全部任务 ───────────────

    private void buildList(List<Todo> all, long now) {
        final boolean matrix = "matrix".equals(prefs.todoView());
        bigTitle.setText(matrix ? "四象限" : "全部任务");
        int pending = pendingCount(all);
        subTitle.setText(matrix
                ? "按「重要 × 紧急」分成四格 · 未完成 " + pending + " 项"
                : "共 " + all.size() + " 项 · 未完成 " + pending + " 项 · 按紧急程度排序");

        LinearLayout col = Ui.column(this);
        Ui.padDp(col, this, 14, 8, 14, 90);

        col.addView(viewToggle(new String[]{"列表", "四象限"}, matrix ? 1 : 0, i -> {
            prefs.setTodoView(i == 1 ? "matrix" : "list");
            paneScroll[tab] = new int[3];
            refresh(false);
        }));

        if (matrix) {
            buildMatrix(col, all, now);
            ScrollView msv = new ScrollView(this);
            msv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            msv.addView(col);
            tabScroll = msv;
            contentHost.addView(msv);
            return;
        }

        // 搜索
        final EditText search = new EditText(this);
        search.setHint("搜索任务、备注、分类…");
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        search.setSingleLine(true);
        search.setText(listQuery);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                listQuery = s.toString().trim();
                renderSearchHistory(search);
                renderGroups();
            }
        });
        col.addView(search);

        // 最近搜过什么：一点就能再搜一次（大众化的做法，不做花哨的搜索语法）
        historyRow = Ui.row(this);
        historyRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        col.addView(historyRow);
        renderSearchHistory(search);

        search.setOnEditorActionListener((v, actionId, ev) -> {
            rememberSearch(search.getText().toString());
            return false;
        });
        search.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                rememberSearch(search.getText().toString());
                renderSearchHistory(search);
            }
        });

        // 状态筛选
        LinearLayout filters = Ui.row(this);
        filters.setPadding(0, Ui.dp(this, 8), 0, 0);
        String[] names = {"全部", "未完成", "已完成"};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            TextView chip = Widgets.chip(this, names[i], listFilter == i,
                    Ui.color(this, R.color.accent));
            chip.setOnClickListener(v -> {
                listFilter = f;
                refresh(false);
            });
            filters.addView(chip);
        }
        col.addView(filters);

        // 标签筛选
        List<String> tags = store.allTags();
        if (!tags.isEmpty()) {
            LinearLayout tagRow = Ui.row(this);
            tagRow.setPadding(0, Ui.dp(this, 4), 0, 0);
            TextView allChip = Widgets.chip(this, "所有分类", tagFilter.isEmpty(),
                    Ui.color(this, R.color.today));
            allChip.setOnClickListener(v -> {
                tagFilter = "";
                refresh(false);
            });
            tagRow.addView(allChip);
            for (final String tag : tags) {
                TextView chip = Widgets.chip(this, tag, tag.equals(tagFilter),
                        Ui.color(this, R.color.today));
                chip.setOnClickListener(v -> {
                    tagFilter = tag.equals(tagFilter) ? "" : tag;
                    refresh(false);
                });
                tagRow.addView(chip);
            }
            col.addView(tagRow);
        }

        // 清除已完成
        final int doneCount = all.size() - pending;
        if (doneCount > 0) {
            TextView clear = Ui.text(this, "清除已完成的 " + doneCount + " 项", 12.5f,
                    R.color.overdue);
            clear.setPadding(0, Ui.dp(this, 10), 0, 0);
            clear.setOnClickListener(v -> confirmClearCompleted(doneCount));
            col.addView(clear);
        }

        listGroups = Ui.column(this);
        col.addView(listGroups);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.addView(col);
        tabScroll = sv;
        contentHost.addView(sv);

        renderGroups();
    }

    private void renderGroups() {
        if (listGroups == null) {
            return;
        }
        listGroups.removeAllViews();
        long now = System.currentTimeMillis();
        List<Todo> all = store.sorted(now);
        int shown = 0;
        for (int bk = 0; bk <= Urgency.B_DONE; bk++) {
            List<Todo> group = new ArrayList<>();
            for (Todo t : all) {
                if (Urgency.bucket(t, now) != bk) {
                    continue;
                }
                if (listFilter == 1 && t.done) {
                    continue;
                }
                if (listFilter == 2 && !t.done) {
                    continue;
                }
                if (!tagFilter.isEmpty()
                        && (t.tag == null || !tagFilter.equals(t.tag.trim()))) {
                    continue;
                }
                if (!listQuery.isEmpty() && !matches(t, listQuery)) {
                    continue;
                }
                group.add(t);
            }
            if (group.isEmpty()) {
                continue;
            }
            shown += group.size();
            listGroups.addView(Widgets.sectionHeader(this, Urgency.BUCKET_NAME[bk], group.size(),
                    bucketColorValue(bk)));
            for (Todo t : group) {
                listGroups.addView(rowFor(t, now, bk != Urgency.B_TODAY));
            }
        }
        if (shown == 0) {
            listGroups.addView(Widgets.empty(this, listQuery.isEmpty() && tagFilter.isEmpty()
                    ? (listFilter == 2 ? "还没有已完成的任务" : "还没有任务，点右下角 ＋ 添加")
                    : "没有符合条件的任务"));
        }

        // 搜索时顺带把账目也搜一遍（只在与账目或备注相关时才有内容）
        if (!listQuery.isEmpty()) {
            java.util.List<Entry> hits = searchEntries(listQuery);
            if (!hits.isEmpty()) {
                listGroups.addView(Widgets.sectionHeader(this, "账目", hits.size(),
                        Ui.color(this, R.color.money_out)));
                for (final Entry e : hits) {
                    listGroups.addView(EntryRow.build(this, e, new EntryRow.Actions() {
                        @Override
                        public void onOpen(Entry x) {
                            Intent i = new Intent(MainActivity.this, EntryEditActivity.class);
                            i.putExtra("id", x.id);
                            startActivity(i);
                        }

                        @Override
                        public void onDelete(Entry x) {
                            // 搜索结果的删除走账目页那套确认框，避免误删
                            Sheet.confirm(MainActivity.this, "删除这条记录？",
                                    "删除后不可恢复。",
                                    x.date + "   " + Money.yuan(x.amount) + "   " + x.label(),
                                    "删除", true, () -> {
                                        EntryStore.get(MainActivity.this).delete(x.id);
                                        toast("已删除");
                                        renderGroups();
                                    });
                        }
                    }));
                }
            }
        }
    }

    private boolean matches(Todo t, String q) {
        String low = q.toLowerCase();
        return contains(t.title, low) || contains(t.note, low) || contains(t.tag, low);
    }

    private boolean contains(String s, String low) {
        return s != null && s.toLowerCase().contains(low);
    }

    /** 分组标题的颜色：走当前紧急度配色方案。 */
    private int bucketColorValue(int bk) {
        switch (bk) {
            case Urgency.B_OVERDUE:
                return Theme.urgency(this, Urgency.OVERDUE);
            case Urgency.B_TODAY:
                return Theme.urgency(this, Urgency.TODAY);
            case Urgency.B_TOMORROW:
            case Urgency.B_WEEK:
                return Theme.urgency(this, Urgency.SOON);
            case Urgency.B_LATER:
                return Theme.urgency(this, Urgency.LATER);
            case Urgency.B_DONE:
                return Ui.color(this, R.color.done);
            default:
                return Theme.urgency(this, Urgency.NONE);
        }
    }

    private View rowFor(Todo t, long now, boolean showDate) {
        View v = TaskRow.build(this, t, now, showDate, this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        v.setLayoutParams(lp);
        return v;
    }

    // ─────────────── 视图四：账目（记账 / 流水 / 结算 / 分析）───────────────

    private void buildMoney() {
        bigTitle.setText("账目");
        double[] t = entryStore.rangeTotals(EntryStore.monthPrefix(YearMonth.now()));
        subTitle.setText("本月支出 " + Money.yuan(t[Entry.EXPENSE])
                + " · 收入 " + Money.yuan(t[Entry.INCOME])
                + " · 共 " + entryStore.count() + " 笔");

        MoneyPanel panel = new MoneyPanel(this, this, sub -> {
            moneySub = sub;
            refresh(false);
        }, moneySub);
        View v = panel.build();
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentHost.addView(v);
    }

    @Override
    public void onMoneyChanged() {        // 账目变化后，其他标签页在切换时会重新构建，这里不用额外处理
    }

    @Override
    public void showUndo(String msg, Runnable restore) {
        showUndoBar(msg, restore);
    }

    /** 今日预算卡：支出 / 预算 / 还能花。 */
    private View budgetCard() {
        double budget = prefs.dailyBudget();
        double[] t = entryStore.dayTotals(Entry.today());
        double out = t[Entry.EXPENSE];
        double in = t[Entry.INCOME];

        LinearLayout card = Widgets.card(this);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "今日收支", 14f, R.color.text, true));
        head.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(this, "收入 " + Money.yuan(in), 12.5f, R.color.money_in));
        card.addView(head);

        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        row.addView(moneyStat("支出", Money.yuan(out), R.color.money_out));
        if (budget > 0) {
            row.addView(moneyStat("每日预算", Money.brief(budget), R.color.text_dim));
            double left = budget - out;
            row.addView(moneyStat(left >= 0 ? "还能花" : "已超支",
                    Money.yuan(Math.abs(left)), left >= 0 ? R.color.money_in : R.color.overdue));
        } else {
            double left = in - out;
            row.addView(moneyStat("结余", Money.yuan(left),
                    left >= 0 ? R.color.money_in : R.color.money_out));
        }
        card.addView(row);

        if (budget > 0) {
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(1000);
            pb.setProgress((int) Math.min(1000, out / budget * 1000));
            boolean over = out > budget;
            pb.setProgressTintList(android.content.res.ColorStateList.valueOf(
                    over ? Ui.color(this, R.color.overdue) : Theme.accent(this)));
            card.addView(pb);
            TextView hint = Ui.text(this, over
                            ? "今天已经超支 " + Money.yuan(out - budget)
                            : "今天还可以花 " + Money.yuan(budget - out),
                    11.5f, over ? R.color.overdue : R.color.text_dim);
            hint.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(hint);
        } else {
            TextView hint = Ui.text(this, "还没设置每日预算 —— 到「账目 → 记一笔」里设置",
                    11.5f, R.color.text_dim);
            hint.setPadding(0, Ui.dp(this, 4), 0, 0);
            card.addView(hint);
        }

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 10);
        card.setLayoutParams(clp);

        TextView add = Ui.textColored(this, "＋ 记一笔", 13f, Theme.accent(this), true);
        add.setPadding(0, Ui.dp(this, 10), 0, 0);
        add.setOnClickListener(v -> {
            Sfx.tap(v);
            tab = 3;
            moneySub = 0;
            refresh(false);
        });
        card.addView(add);
        return card;
    }

    private View moneyStat(String name, String value, int colorRes) {
        LinearLayout box = Ui.column(this);
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = Ui.text(this, value, 14.5f, colorRes, true);
        v.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        box.addView(v);
        TextView n = Ui.text(this, name, 11.5f, R.color.text_dim);
        n.setPadding(0, Ui.dp(this, 3), 0, 0);
        box.addView(n);
        return box;
    }

    /** 某一天的收支明细区块，今天页与日历页共用。 */
    private View dayMoneySection(final LocalDate day, boolean withAdd) {
        LinearLayout box = Ui.column(this);
        final String key = Entry.dayOf(day);
        List<Entry> list = entryStore.onDay(key);
        double[] t = entryStore.dayTotals(key);

        box.addView(Widgets.sectionHeader(this, "收支明细", list.size(),
                Ui.color(this, R.color.money_out)));

        if (!list.isEmpty()) {
            LinearLayout sum = Ui.row(this);
            sum.setPadding(0, 0, 0, Ui.dp(this, 8));
            sum.addView(Ui.text(this, "收入 " + Money.yuan(t[Entry.INCOME]), 12.5f,
                    R.color.money_in));
            sum.addView(new View(this), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            sum.addView(Ui.text(this, "支出 " + Money.yuan(t[Entry.EXPENSE]), 12.5f,
                    R.color.money_out));
            box.addView(sum);
        }

        for (final Entry e : list) {
            View row = EntryRow.build(this, e, new EntryRow.Actions() {
                @Override
                public void onOpen(Entry en) {
                    Intent i = new Intent(MainActivity.this, EntryEditActivity.class);
                    i.putExtra("id", en.id);
                    startActivity(i);
                }

                @Override
                public void onDelete(final Entry en) {
                    // 和长按任务删除统一走自绘确认框，并且都能撤销
                    Sheet.confirm(MainActivity.this, "删除这条记录？",
                            "删除后底部会出现「撤销」，8 秒内可以恢复。",
                            en.date + "   " + (en.isIncome() ? "+" : "-")
                                    + Money.yuan(en.amount) + "   " + en.label(),
                            "删除", true, () -> {
                                final Entry removed = entryStore.delete(en.id);
                                refresh(false);
                                if (removed != null) {
                                    showUndoBar("已删除「" + removed.label() + "」", () -> {
                                        entryStore.restore(removed);
                                        refresh(false);
                                        toast("已恢复");
                                    });
                                }
                            });
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 6);
            row.setLayoutParams(lp);
            box.addView(row);
        }

        if (list.isEmpty()) {
            box.addView(Widgets.empty(this, "这一天还没有记账"));
        }
        if (withAdd) {
            TextView add = Ui.textColored(this, "为这一天记一笔", 14f, Theme.accent(this), true);
            add.setGravity(Gravity.CENTER);
            add.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 14 * d(),
                    Theme.accent(this), d()));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
            alp.topMargin = Ui.dp(this, 12);
            add.setLayoutParams(alp);
            add.setOnClickListener(v -> {
                Sfx.tap(v);
                tab = 3;
                moneySub = 0;
                refresh(false);
            });
            box.addView(add);
        }
        return box;
    }

    // ─────────────── 任务操作 ───────────────

    @Override
    public void onToggle(Todo t) {
        Todo next = store.setDone(t.id, !t.done);
        if (next != null) {
            Reminders.schedule(this, next);
            Toast.makeText(this, "已完成，下一次：" + Fmt.date(next.due), Toast.LENGTH_SHORT).show();
        } else {
            Reminders.cancel(this, t);
        }
        refresh(false);
    }

    @Override
    public void onOpen(Todo t) {
        openEditor(t.id, -1);
    }

    @Override
    public void onMenu(Todo t, View anchor) {
        long now = System.currentTimeMillis();
        String sub = (t.due > 0 ? Fmt.date(t.due) + " "
                + (t.hasTime ? Fmt.time(t.due) : "全天") + " · " : "")
                + Urgency.remaining(t, now);
        java.util.ArrayList<Sheet.Item> items = new java.util.ArrayList<>();
        items.add(Sheet.item("✎", "编辑", "改标题、时间、优先级", false,
                () -> openEditor(t.id, -1)));
        items.add(Sheet.item(t.done ? "↺" : "✓",
                t.done ? "标记为未完成" : "标记完成",
                t.isRepeating() ? "完成后自动生成下一期" : null, false,
                () -> onToggle(t)));
        if (!t.done) {
            items.add(Sheet.item("▶", "开始专注", "番茄钟，到点提醒", false, () -> {
                Intent fi = new Intent(this, FocusActivity.class);
                fi.putExtra("id", t.id);
                startActivity(fi);
            }));
            items.add(Sheet.item("↷", "推迟到明天", "整条往后挪一天", false,
                    () -> postpone(t, true)));
            items.add(Sheet.item("⏱", "推迟 1 小时", null, false,
                    () -> postpone(t, false)));
        }
        items.add(Sheet.item("⧉", "复制一条", "生成一个副本", false,
                () -> duplicate(t)));
        items.add(Sheet.item("✕", "删除", "删了还能撤销", true,
                () -> confirmDelete(t)));

        Sheet.showMenu(this, t.title == null || t.title.isEmpty() ? "（无标题）" : t.title,
                sub, items);
    }

    private void duplicate(Todo t) {
        Todo copy = t.copy();
        copy.id = 0;
        copy.uid = "";
        copy.done = false;
        copy.doneAt = 0;
        copy.createdAt = 0;
        copy.title = t.title + "（副本）";
        store.add(copy);
        Reminders.schedule(this, copy);
        toast("已复制一条");
        refresh(false);
    }

    private void postpone(Todo t, boolean toTomorrow) {
        Todo copy = t.copy();
        if (copy.due <= 0) {
            LocalDate target = LocalDate.now().plusDays(toTomorrow ? 1 : 0);
            int mins = java.time.LocalTime.now().getHour() * 60
                    + java.time.LocalTime.now().getMinute();
            int add = toTomorrow ? 0 : 60;
            copy.due = Todo.toMillis(target.atStartOfDay().plusMinutes(mins + add));
            copy.hasTime = true;
        } else if (toTomorrow) {
            copy.due = copy.due + 24 * 3600 * 1000L;
        } else {
            copy.due = copy.due + 3600 * 1000L;
        }
        store.update(copy);
        Reminders.schedule(this, copy);
        toast("已推迟到 " + Fmt.date(copy.due) + " "
                + (copy.hasTime ? Fmt.time(copy.due) : "全天"));
        refresh(false);
    }

    private void confirmDelete(final Todo t) {
        String preview = (t.due > 0 ? Fmt.date(t.due) + " " + (t.hasTime ? Fmt.time(t.due) : "全天")
                : "无期限") + "   " + Urgency.remaining(t, System.currentTimeMillis());
        Sheet.confirm(this, "删除这条任务？",
                "删除后底部会出现「撤销」，6 秒内可以恢复。",
                preview, "删除", true, () -> {
                    Reminders.cancel(this, t);
                    Todo removed = store.delete(t.id);
                    refresh(false);
                    if (removed != null) {
                        showUndoBar("已删除「" + shortTitle(removed.title) + "」", () -> {
                            store.restore(removed);
                            Reminders.schedule(this, removed);
                            refresh(false);
                            toast("已恢复");
                        });
                    }
                });
    }

    private void confirmClearCompleted(int n) {
        Sheet.confirm(this, "清除已完成的任务",
                "将删除 " + n + " 条已完成的任务，这个操作不能撤销。",
                null, "清除", true, () -> {
                    int c = store.clearCompleted();
                    refresh(false);
                    toast("已清除 " + c + " 项");
                });
    }

    private String shortTitle(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 12 ? s.substring(0, 12) + "…" : s;
    }

    @Override
    public void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 底部「撤销」条。 */
    private void showUndoBar(String msg, final Runnable action) {
        // 交给 Sheet 统一处理：它把撤销条放在 android.R.id.content 上，
        // 这是唯一能确定「铺满整屏」的容器，贴底定位才可靠
        Sheet.showUndo(this, msg, action);
    }

    private void hideUndoBar() {
        Sheet.dismissUndo();
    }

    /** 数据一变就让桌面小组件跟着刷新。 */
    private void syncWidget() {
        try {
            com.sudfry.todo.widget.TodayWidget.refresh(this);
            com.sudfry.todo.widget.DashWidget.refresh(this);
        } catch (Throwable ignored) {
        }
    }
}
