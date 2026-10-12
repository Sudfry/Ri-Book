package com.sudfry.todo;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sudfry.todo.model.Todo;
import com.sudfry.todo.reminder.Reminders;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Nlp;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;
import com.sudfry.todo.ui.Widgets;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 新建 / 编辑一条任务。 */
public class EditActivity extends Activity {

    private TodoStore store;
    private Todo todo;
    private boolean isNew;

    private EditText titleEt;
    private TextView nlpHint;
    private EditText noteEt;
    private EditText tagEt;
    private TextView dateValue;
    private TextView timeValue;
    private TextView durationValue;
    private TextView remindValue;
    private TextView[] priorityChips = new TextView[4];
    private TextView[] repeatChips = new TextView[4];
    private TextView[] remindChips;
    private LinearLayout subListBox;
    private EditText subInput;
    private TextView subHeaderLabel;

    private LocalDate date;
    private boolean hasTime;
    private int hour = 9;
    private int minute = 0;
    private int priority = Todo.PRI_MID;
    private int repeat = Todo.REPEAT_NONE;
    private int remind = 10;
    private int duration = 60;

    private static final int[] REMIND_OPTIONS = {-1, 0, 5, 10, 30, 60, 1440};
    private static final int[][] PRIORITY_LIST = {
            {Todo.PRI_LOW, Todo.PRI_MID, Todo.PRI_HIGH, Todo.PRI_URGENT}
    };
    private static final int[] REPEAT_LIST = {
            Todo.REPEAT_NONE, Todo.REPEAT_DAILY, Todo.REPEAT_WEEKLY, Todo.REPEAT_MONTHLY
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = TodoStore.get(this);

        long id = getIntent().getLongExtra("id", 0);
        isNew = id <= 0;
        if (isNew) {
            todo = new Todo();
            long dayMs = getIntent().getLongExtra("day", 0);
            date = dayMs > 0 ? Todo.toLocal(dayMs).toLocalDate() : LocalDate.now();
            int preMinute = getIntent().getIntExtra("minute", -1);
            if (preMinute >= 0) {
                hasTime = true;
                hour = (preMinute / 60) % 24;
                minute = preMinute % 60;
            }
        } else {
            Todo src = store.byId(id);
            if (src == null) {
                finish();
                return;
            }
            todo = src.copy();
            priority = todo.priority;
            repeat = todo.repeat;
            remind = todo.remindBefore;
            duration = todo.durationMin;
            if (todo.due > 0) {
                LocalDateTime dt = Todo.toLocal(todo.due);
                date = dt.toLocalDate();
                hour = dt.getHour();
                minute = dt.getMinute();
                hasTime = todo.hasTime;
            } else {
                date = null;
            }
        }

        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
    }

    private View buildUi() {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));

        // 顶栏
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bar, this, 12, 10, 12, 10);
        TextView back = Ui.text(this, "‹ 返回", 15f, R.color.accent);
        back.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.text(this, isNew ? "新建任务" : "编辑任务", 16f, R.color.text, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tlp);
        title.setGravity(Gravity.CENTER);
        bar.addView(title);
        TextView del = Ui.text(this, isNew ? "" : "删除", 15f, R.color.overdue);
        del.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        del.setOnClickListener(v -> confirmDelete());
        bar.addView(del);
        root.addView(bar);
        root.addView(Widgets.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(slp);
        LinearLayout col = Ui.column(this);
        Ui.padDp(col, this, 16, 14, 16, 24);
        sv.addView(col);

        // 标题
        titleEt = new EditText(this);
        titleEt.setHint("要做什么？");
        titleEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        titleEt.setTypeface(Typeface.DEFAULT_BOLD);
        titleEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleEt.setText(todo.title);
        col.addView(titleEt);

        // 自然语言识别提示：标题里写了「明天下午3点」就实时告诉你认成了什么
        nlpHint = Ui.text(this, "", 12.5f, R.color.accent);
        nlpHint.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), 0, 0);
        nlpHint.setVisibility(View.GONE);
        col.addView(nlpHint);
        titleEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                updateNlpHint(e == null ? "" : e.toString());
            }
        });
        updateNlpHint(todo.title);

        // 备注
        noteEt = new EditText(this);
        noteEt.setHint("备注（可留空）");
        noteEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        noteEt.setGravity(Gravity.TOP | Gravity.START);
        noteEt.setMinLines(2);
        noteEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        noteEt.setText(todo.note);
        col.addView(noteEt);

        // ── 时间卡片 ─────────────────────────────
        col.addView(sectionLabel("截止时间"));
        LinearLayout timeCard = Widgets.card(this);

        dateValue = Ui.text(this, "", 15f, R.color.text, true);
        timeCard.addView(fieldRow("日期", dateValue, v -> pickDate()));
        LinearLayout quick = Ui.row(this);
        quick.setPadding(Ui.dp(this, 64), 0, 0, Ui.dp(this, 6));
        quick.addView(quickChip("今天", () -> setDate(LocalDate.now())));
        quick.addView(quickChip("明天", () -> setDate(LocalDate.now().plusDays(1))));
        quick.addView(quickChip("后天", () -> setDate(LocalDate.now().plusDays(2))));
        quick.addView(quickChip("无期限", () -> setDate(null)));
        timeCard.addView(quick);

        timeValue = Ui.text(this, "", 15f, R.color.text, true);
        LinearLayout timeRow = fieldRow("时间", timeValue, v -> pickTime());
        TextView allDay = Ui.text(this, "设为全天", 12.5f, R.color.accent);
        allDay.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        allDay.setOnClickListener(v -> {
            hasTime = false;
            refreshValues();
        });
        timeRow.addView(allDay);
        timeCard.addView(timeRow);

        durationValue = Ui.text(this, "", 15f, R.color.text, true);
        LinearLayout durRow = fieldRow("时长", durationValue, null);
        TextView minus = Ui.text(this, "  −  ", 16f, R.color.accent);
        minus.setOnClickListener(v -> {
            duration = Math.max(15, duration - 15);
            refreshValues();
        });
        durRow.addView(minus);
        TextView plus = Ui.text(this, "  ＋  ", 16f, R.color.accent);
        plus.setOnClickListener(v -> {
            duration = Math.min(600, duration + 15);
            refreshValues();
        });
        durRow.addView(plus);
        timeCard.addView(durRow);
        col.addView(timeCard);

        // ── 优先级 ───────────────────────────────
        col.addView(sectionLabel("优先级"));
        LinearLayout priCard = Widgets.card(this);
        LinearLayout priRow = Ui.row(this);
        for (int i = 0; i < 4; i++) {
            final int p = PRIORITY_LIST[0][i];
            TextView chip = Widgets.chip(this, Todo.priorityName(p), false, priorityColor(p));
            chip.setOnClickListener(v -> {
                priority = p;
                refreshChips();
            });
            priorityChips[i] = chip;
            priRow.addView(chip);
        }
        priCard.addView(priRow);
        col.addView(priCard);

        // ── 重复 ─────────────────────────────────
        col.addView(sectionLabel("重复"));
        LinearLayout repCard = Widgets.card(this);
        LinearLayout repRow = Ui.row(this);
        for (int i = 0; i < 4; i++) {
            final int r = REPEAT_LIST[i];
            TextView chip = Widgets.chip(this, Todo.repeatName(r), false, Ui.color(this, R.color.accent));
            chip.setOnClickListener(v -> {
                repeat = r;
                refreshChips();
            });
            repeatChips[i] = chip;
            repRow.addView(chip);
        }
        repCard.addView(repRow);
        col.addView(repCard);

        // ── 提醒 ─────────────────────────────────
        col.addView(sectionLabel("提醒"));
        LinearLayout remCard = Widgets.card(this);
        remindValue = Ui.text(this, "", 15f, R.color.text, true);
        remCard.addView(fieldRow("提醒", remindValue, null));
        LinearLayout remRow = Ui.row(this);
        remRow.setPadding(Ui.dp(this, 64), Ui.dp(this, 4), 0, 0);
        remindChips = new TextView[REMIND_OPTIONS.length];
        for (int i = 0; i < REMIND_OPTIONS.length; i++) {
            final int m = REMIND_OPTIONS[i];
            TextView chip = Widgets.chip(this, Todo.remindName(m), false, Ui.color(this, R.color.accent));
            chip.setOnClickListener(v -> {
                remind = m;
                refreshChips();
            });
            remindChips[i] = chip;
            remRow.addView(chip);
        }
        remCard.addView(remRow);
        col.addView(remCard);

        // ── 分类 ─────────────────────────────────
        col.addView(sectionLabel("分类"));
        LinearLayout tagCard = Widgets.card(this);
        tagEt = new EditText(this);
        tagEt.setHint("工作 / 学习 / 生活…");
        tagEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tagEt.setText(todo.tag);
        tagCard.addView(tagEt);
        col.addView(tagCard);

        // ── 子任务 ─────────────────────────────
        subHeaderLabel = (TextView) sectionLabel(subHeaderText());
        col.addView(subHeaderLabel);
        LinearLayout subCard = Widgets.card(this);
        subListBox = Ui.column(this);
        subCard.addView(subListBox);
        LinearLayout addRow = Ui.row(this);
        addRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        subInput = new EditText(this);
        subInput.setHint("加一步，例如：查资料");
        subInput.setSingleLine(true);
        subInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        subInput.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(subInput);
        TextView addBtn = Ui.textColored(this, "  添加  ", 14f, Theme.accent(this), true);
        addBtn.setOnClickListener(v -> {
            String s = subInput.getText().toString().trim();
            if (s.isEmpty()) {
                Toast.makeText(this, "先写一步内容", Toast.LENGTH_SHORT).show();
                return;
            }
            todo.addSub(s);
            subInput.setText("");
            renderSubtasks();
            // 标题栏里的「3/5」也要跟着更新
            recreateSubHeader();
        });
        addRow.addView(addBtn);
        subCard.addView(addRow);
        col.addView(subCard);
        renderSubtasks();

        // 保存 / 删除固定在底部，不用滚动去找
        LinearLayout bottom = Ui.column(this);
        bottom.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bottom, this, 16, 10, 16, 12);
        TextView save = Ui.text(this, isNew ? "添加任务" : "保存修改", 16f, R.color.on_color, true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(Ui.bg(Ui.color(this, R.color.accent), Ui.dp(this, 14)));
        save.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));
        save.setOnClickListener(v -> save());
        bottom.addView(save);
        if (!isNew) {
            TextView delBtn = Ui.text(this, "删除这条任务", 14f, R.color.overdue);
            delBtn.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40));
            dlp.topMargin = Ui.dp(this, 4);
            delBtn.setLayoutParams(dlp);
            delBtn.setOnClickListener(v -> confirmDelete());
            bottom.addView(delBtn);
        }

        root.addView(sv);
        root.addView(Widgets.divider(this));
        root.addView(bottom);
        refreshValues();
        return root;
    }

    // ───────────────── 子任务 ─────────────────

    private boolean hasSubs() {
        return todo.subTotal() > 0;
    }

    private String subHeaderText() {
        return hasSubs()
                ? "子任务（" + todo.subDone() + "/" + todo.subTotal() + "）"
                : "子任务（可留空）";
    }

    private void recreateSubHeader() {
        if (subHeaderLabel != null) {
            subHeaderLabel.setText(subHeaderText());
        }
    }

    /** 重画子任务列表（就地，不重建整页）。 */
    private void renderSubtasks() {
        if (subListBox == null) {
            return;
        }
        subListBox.removeAllViews();
        if (todo.subtasks.isEmpty()) {
            TextView empty = Ui.text(this, "还没有步骤。把一件事拆成几步，做起来更清楚。",
                    12f, R.color.text_dim);
            empty.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            subListBox.addView(empty);
            return;
        }
        for (final Todo.Subtask s : todo.subtasks) {
            LinearLayout row = Ui.row(this);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(this, 6);
            row.setLayoutParams(rlp);

            TextView box = Ui.text(this, s.done ? "☑" : "☐", 17f,
                    s.done ? R.color.text_dim : R.color.accent, false);
            box.setPadding(0, 0, Ui.dp(this, 10), 0);
            box.setOnClickListener(v -> {
                todo.toggleSub(s.id, !s.done);
                renderSubtasks();
                recreateSubHeader();
            });
            row.addView(box);

            TextView t = Ui.text(this, s.title, 14f,
                    s.done ? R.color.done : R.color.text, false);
            t.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (s.done) {
                t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            row.addView(t);

            TextView del = Ui.text(this, "✕", 15f, R.color.text_dim, false);
            del.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
            del.setOnClickListener(v -> {
                todo.removeSub(s.id);
                renderSubtasks();
                recreateSubHeader();
            });
            row.addView(del);
            subListBox.addView(row);
        }
    }

    // ───────────────── 自然语言 ─────────────────

    /** 标题里认出的时间/优先级，实时显示。 */
    private void updateNlpHint(String text) {
        if (nlpHint == null) {
            return;
        }
        Nlp.Result r = Nlp.parse(text);
        if (r.isEmpty()) {
            nlpHint.setVisibility(View.GONE);
            return;
        }
        nlpHint.setVisibility(View.VISIBLE);
        nlpHint.setText("识别到：" + r.human + "　· 保存时自动应用，标题里会去掉这些词");
    }

    /** 保存时把标题里的时间词变成真正的日期/优先级。 */
    private void applyNlp() {
        Nlp.Result r = Nlp.parse(titleEt.getText().toString());
        if (r.isEmpty()) {
            return;
        }
        if (r.date != null || r.hasTime) {
            LocalDate day = r.date != null ? r.date : Todo.toLocal(todo.due).toLocalDate();
            int hour = r.hasTime ? r.hour : (todo.hasTime ? Todo.toLocal(todo.due).getHour() : 9);
            int min = r.hasTime ? r.minute : (todo.hasTime ? Todo.toLocal(todo.due).getMinute() : 0);
            todo.due = Todo.toMillis(day.atTime(hour, min));
            todo.hasTime = r.hasTime || todo.hasTime;
        }
        if (r.priority > 0 && todo.priority == 0) {
            todo.priority = r.priority;
        }
        if (r.title != null && !r.title.isEmpty()) {
            todo.title = r.title;
        }
    }

    private int priorityColor(int p) {        switch (p) {
            case Todo.PRI_URGENT:
                return Ui.color(this, R.color.overdue);
            case Todo.PRI_HIGH:
                return Ui.color(this, R.color.today);
            case Todo.PRI_MID:
                return Ui.color(this, R.color.accent);
            default:
                return Ui.color(this, R.color.none);
        }
    }

    private View sectionLabel(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim, true);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return t;
    }

    private LinearLayout fieldRow(String label, TextView value, View.OnClickListener onClick) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        TextView l = Ui.text(this, label, 14f, R.color.text_dim);
        l.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 64),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(l);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        value.setLayoutParams(vlp);
        row.addView(value);
        if (onClick != null) {
            row.setOnClickListener(onClick);
        }
        return row;
    }

    private TextView quickChip(String text, Runnable action) {
        TextView chip = Widgets.chip(this, text, false, Ui.color(this, R.color.accent));
        chip.setOnClickListener(v -> action.run());
        return chip;
    }

    private void setDate(LocalDate d) {
        date = d;
        refreshValues();
    }

    private void pickDate() {
        LocalDate base = date != null ? date : LocalDate.now();
        new DatePickerDialog(this, (view, y, m, dd) -> {
            date = LocalDate.of(y, m + 1, dd);
            refreshValues();
        }, base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth()).show();
    }

    private void pickTime() {
        new TimePickerDialog(this, (view, h, mi) -> {
            hour = h;
            minute = mi;
            hasTime = true;
            refreshValues();
        }, hour, minute, true).show();
    }

    private void refreshValues() {
        if (dateValue != null) {
            dateValue.setText(date == null ? "无期限" : (Fmt.date(date) + " " + Fmt.wd(date)));
        }
        if (timeValue != null) {
            timeValue.setText(hasTime ? Fmt.hm(hour * 60 + minute) : "全天");
        }
        if (durationValue != null) {
            durationValue.setText(duration >= 60
                    ? (duration / 60) + " 小时" + (duration % 60 == 0 ? "" : " " + (duration % 60) + " 分")
                    : duration + " 分钟");
        }
        if (remindValue != null) {
            remindValue.setText(Todo.remindName(remind));
        }
        refreshChips();
    }

    private void refreshChips() {
        for (int i = 0; i < priorityChips.length; i++) {
            if (priorityChips[i] == null) {
                continue;
            }
            int p = PRIORITY_LIST[0][i];
            styleChip(priorityChips[i], Todo.priorityName(p), priority == p, priorityColor(p));
        }
        for (int i = 0; i < repeatChips.length; i++) {
            if (repeatChips[i] == null) {
                continue;
            }
            int r = REPEAT_LIST[i];
            styleChip(repeatChips[i], Todo.repeatName(r), repeat == r, Ui.color(this, R.color.accent));
        }
        if (remindChips != null) {
            for (int i = 0; i < remindChips.length; i++) {
                if (remindChips[i] == null) {
                    continue;
                }
                int m = REMIND_OPTIONS[i];
                styleChip(remindChips[i], Todo.remindName(m), remind == m,
                        Ui.color(this, R.color.accent));
            }
        }
    }

    private void styleChip(TextView t, String text, boolean active, int color) {
        float dd = getResources().getDisplayMetrics().density;
        t.setText(text);
        if (active) {
            t.setTextColor(0xFFFFFFFF);
            t.setBackground(Ui.pill(color));
            t.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            t.setTextColor(Ui.color(this, R.color.text_dim));
            t.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 999f,
                    Ui.color(this, R.color.divider), 1 * dd));
            t.setTypeface(Typeface.DEFAULT);
        }
    }

    private void save() {
        String title = titleEt.getText().toString().trim();
        if (title.isEmpty()) {
            Toast.makeText(this, "请先写上任务内容", Toast.LENGTH_SHORT).show();
            return;
        }
        todo.title = title;
        todo.note = noteEt.getText().toString().trim();
        todo.tag = tagEt.getText().toString().trim();
        todo.priority = priority;
        todo.repeat = repeat;
        todo.remindBefore = remind;
        todo.durationMin = duration;
        if (date == null) {
            todo.due = 0;
            todo.hasTime = false;
        } else {
            LocalTime tm = hasTime ? LocalTime.of(hour, minute) : LocalTime.of(9, 0);
            todo.due = Todo.toMillis(LocalDateTime.of(date, tm));
            todo.hasTime = hasTime;
        }
        // 界面选择读完了，再让标题里的时间/优先级盖上去（认不出就原样不动）
        applyNlp();
        if (todo.title == null || todo.title.trim().isEmpty()) {
            todo.title = titleEt.getText().toString().trim();
        }
        if (isNew) {
            store.add(todo);
        } else {
            store.update(todo);
        }
        if (todo.done) {
            Reminders.cancel(this, todo);
        } else {
            Reminders.schedule(this, todo);
        }
        Toast.makeText(this, isNew ? "已添加" : "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除任务")
                .setMessage("「" + (todo.title == null ? "" : todo.title) + "」将被删除，无法恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dlg, w) -> {
                    Reminders.cancel(this, todo);
                    store.delete(todo.id);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }
}
