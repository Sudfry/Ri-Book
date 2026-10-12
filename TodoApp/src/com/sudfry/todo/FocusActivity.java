package com.sudfry.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.sudfry.todo.model.Todo;
import com.sudfry.todo.notify.FocusSession;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.AppPrefs;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

/**
 * 番茄钟：给一条任务开一段专注时间。
 *
 * 计时靠「结束时刻 - 现在」算，不靠累加 tick —— 退到后台再回来时间不会跑偏。
 * 到点由 FocusSession 的闹钟负责结算并弹通知，所以在应用关掉之后也记得住。
 */
public class FocusActivity extends Activity {

    private TodoStore store;
    private Todo todo;
    private AppPrefs prefs;

    private TextView titleView;
    private TextView clockView;
    private TextView hintView;
    private ProgressBar ring;
    private TextView startBtn;

    private long endAt;
    private long pausedLeftMs;
    private boolean paused;
    private boolean finished;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            if (!finished && !paused) {
                handler.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = TodoStore.get(this);
        prefs = AppPrefs.get(this);
        long id = getIntent().getLongExtra("id", 0);
        todo = id > 0 ? store.byId(id) : null;
        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
        begin();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!paused && !finished) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    private void tap(View v) {
        Sfx.tap(v);
    }

    // ───────────────────── 界面 ─────────────────────

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));

        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bar, this, 12, 10, 12, 10);
        TextView back = Ui.text(this, "‹ 返回", 15f, R.color.accent);
        back.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        back.setOnClickListener(v -> {
            // 直接返回不等于放弃：会话还在跑，到点照样提醒
            finish();
        });
        bar.addView(back);
        TextView t = Ui.text(this, "专注计时", 16f, R.color.text, true);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);
        bar.addView(Ui.text(this, "      ", 15f, R.color.accent));
        root.addView(bar);
        root.addView(Widgets.divider(this));

        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.padDp(col, this, 20, 28, 20, 20);
        col.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        String name = todo == null ? "自由专注"
                : (todo.title == null || todo.title.isEmpty() ? "（无标题）" : todo.title);
        titleView = Ui.text(this, name, 17f, R.color.text, true);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        col.addView(titleView);

        if (todo != null && todo.spentMin > 0) {
            TextView spent = Ui.text(this, "这条任务已经专注过 " + todo.spentMin + " 分钟",
                    12.5f, R.color.text_dim);
            spent.setGravity(Gravity.CENTER);
            spent.setPadding(0, Ui.dp(this, 8), 0, 0);
            col.addView(spent);
        }

        clockView = Ui.textColored(this, "25:00", 60f, Theme.accent(this), true);
        clockView.setGravity(Gravity.CENTER);
        clockView.setTypeface(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD);
        clockView.setPadding(0, Ui.dp(this, 26), 0, 0);
        col.addView(clockView);

        ring = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        ring.setMax(1000);
        ring.setProgressTintList(android.content.res.ColorStateList.valueOf(Theme.accent(this)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8));
        rlp.topMargin = Ui.dp(this, 18);
        ring.setLayoutParams(rlp);
        col.addView(ring);

        hintView = Ui.text(this, "", 12.5f, R.color.text_dim);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(0, Ui.dp(this, 12), 0, 0);
        col.addView(hintView);

        LinearLayout buttons = Ui.row(this);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 26);
        buttons.setLayoutParams(blp);

        startBtn = Ui.textColored(this, "暂停", 16f, Theme.onAccent(this), true);
        startBtn.setGravity(Gravity.CENTER);
        startBtn.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 14)));
        startBtn.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));
        startBtn.setOnClickListener(v -> {
            tap(v);
            if (paused) {
                resume();
            } else {
                pause();
            }
        });
        buttons.addView(startBtn);

        TextView stop = Ui.textColored(this, "结束并记录", 16f, Ui.color(this, R.color.overdue), true);
        stop.setGravity(Gravity.CENTER);
        stop.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), Ui.dp(this, 14),
                Ui.color(this, R.color.overdue), d()));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f);
        slp.leftMargin = Ui.dp(this, 10);
        stop.setLayoutParams(slp);
        stop.setOnClickListener(v -> {
            tap(v);
            confirmStop();
        });
        buttons.addView(stop);
        col.addView(buttons);

        // 时长选择
        LinearLayout lens = Ui.row(this);
        lens.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, 20);
        lens.setLayoutParams(llp);
        lens.addView(Ui.text(this, "每次专注  ", 12.5f, R.color.text_dim));
        final int[] options = {15, 25, 45, 60};
        for (final int m : options) {
            TextView chip = Widgets.chip(this, m + " 分", prefs.focusLength() == m,
                    Theme.accent(this));
            chip.setOnClickListener(v -> {
                tap(v);
                prefs.setFocusLength(m);
                finish();
                startActivity(getIntent().putExtra("min", m));
            });
            lens.addView(chip);
        }
        col.addView(lens);

        root.addView(col);
        return root;
    }

    // ───────────────────── 计时逻辑 ─────────────────────

    private void begin() {
        int minutes = getIntent().getIntExtra("min", prefs.focusLength());
        // 已经有一段在跑：接着显示它，而不是重新开始
        if (FocusSession.isActive(this) && FocusSession.todoId(this) == (todo == null ? 0 : todo.id)) {
            endAt = FocusSession.startedAt(this) + FocusSession.plannedMin(this) * 60000L;
        } else {
            FocusSession.start(this, todo == null ? 0 : todo.id, minutes);
            endAt = System.currentTimeMillis() + minutes * 60000L;
        }
        paused = false;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void pause() {
        paused = true;
        pausedLeftMs = Math.max(0, endAt - System.currentTimeMillis());
        handler.removeCallbacks(tick);
        startBtn.setText("继续");
        startBtn.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 14)));
        hintView.setText("已暂停 · 到点提醒已取消");
        // 暂停就把闹钟取消，避免暂停期间被通知打断
        FocusSession.cancel(this);
    }

    private void resume() {
        if (FocusSession.isActive(this)) {
            endAt = FocusSession.startedAt(this) + FocusSession.plannedMin(this) * 60000L;
        } else {
            int minutes = Math.max(1, (int) Math.round(pausedLeftMs / 60000.0));
            FocusSession.start(this, todo == null ? 0 : todo.id, minutes);
            endAt = System.currentTimeMillis() + (long) (pausedLeftMs);
        }
        paused = false;
        startBtn.setText("暂停");
        hintView.setText("");
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void updateClock() {
        long leftMs = Math.max(0, endAt - System.currentTimeMillis());
        long totalMs = Math.max(1, plannedMs());
        long sec = (leftMs + 999) / 1000;
        clockView.setText(String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60));
        int pct = (int) Math.min(1000, (totalMs - leftMs) * 1000 / totalMs);
        ring.setProgress(pct);
        if (!paused) {
            if (FocusSession.isActive(this)) {
                hintView.setText("到点会弹通知提醒你，退出应用也不会丢");
            }
        }
        if (leftMs <= 0 && !finished) {
            finished = true;
            handler.removeCallbacks(tick);
            clockView.setText("00:00");
            hintView.setText("完成了这一段专注，已记到任务上");
            // 闹钟负责结算，这里只把界面收个尾
            startBtn.setText("已完成");
        }
    }

    private long plannedMs() {
        int m = FocusSession.isActive(this) ? FocusSession.plannedMin(this) : prefs.focusLength();
        return Math.max(1, m) * 60000L;
    }

    private int elapsedMinutes() {
        long start = FocusSession.isActive(this) ? FocusSession.startedAt(this)
                : System.currentTimeMillis() - 1;
        long ms = System.currentTimeMillis() - start;
        return Math.max(1, (int) Math.round(ms / 60000.0));
    }

    private void confirmStop() {
        final int used = elapsedMinutes();
        com.sudfry.todo.ui.Sheet.confirm(this, "结束这段专注？",
                "会把已专注的 " + used + " 分钟记到"
                        + (todo == null ? "今天" : "这条任务") + "上。",
                null, "结束并记录", false, () -> {
                    FocusSession.commit(this, used);
                    Sfx.click(this);
                    finish();
                });
    }
}
