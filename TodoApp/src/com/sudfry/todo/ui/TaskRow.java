package com.sudfry.todo.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sudfry.todo.R;
import com.sudfry.todo.model.Todo;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;
import com.sudfry.todo.util.Urgency;

/** 任务列表里的一行：色卡 + 勾选框 + 标题 + 紧迫程度徽章。 */
public final class TaskRow {

    public interface Actions {
        void onToggle(Todo t);

        void onOpen(Todo t);

        void onMenu(Todo t, View anchor);
    }

    private TaskRow() {
    }

    public static View build(Context c, Todo t, long now, boolean showDate, Actions a) {
        float d = c.getResources().getDisplayMetrics().density;
        int lv = t.done ? Urgency.NONE : Urgency.level(t, now);
        int levelColor = com.sudfry.todo.util.Theme.urgency(c, lv);
        int dim = Ui.color(c, R.color.text_dim);

        LinearLayout row = Ui.row(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int bgColor = Ui.color(c, R.color.surface);
        if (!t.done && lv == Urgency.OVERDUE) {
            row.setBackground(Ui.bgStroke(bgColor, Theme.rowRadius(c), levelColor, 1.6f * d));
        } else {
            row.setBackground(Ui.bgStroke(bgColor, Theme.rowRadius(c), Theme.cardStrokeColor(c), Theme.cardStroke(c)));
        }
        Ui.padDp(row, c, 8, 10, 12, 10);

        // 紧急度色卡
        View bar = new View(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                Math.round(4 * d), ViewGroup.LayoutParams.MATCH_PARENT);
        blp.rightMargin = Math.round(8 * d);
        bar.setLayoutParams(blp);
        bar.setBackground(Ui.bg(levelColor, 2 * d));
        row.addView(bar);

        CheckBox cb = new CheckBox(c);
        int cbSize = Math.round(42 * d);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cbSize, cbSize);
        cb.setLayoutParams(clp);
        cb.setChecked(t.done);
        try {
            cb.setButtonTintList(ColorStateList.valueOf(levelColor));
        } catch (Throwable ignored) {
        }
        cb.setOnClickListener(v -> {
            if (a != null) {
                a.onToggle(t);
            }
        });
        row.addView(cb);

        LinearLayout mid = Ui.column(c);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mid.setLayoutParams(mlp);

        TextView title = new TextView(c);
        title.setText(t.title == null || t.title.isEmpty() ? "（无标题）" : t.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(t.done ? Ui.color(c, R.color.done) : Ui.color(c, R.color.text));
        if (t.done) {
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        }
        title.setMaxLines(2);
        mid.addView(title);

        // 有子任务就画一条细进度条，一眼看出做到哪了
        if (t.subTotal() > 0) {
            LinearLayout track = new LinearLayout(c);
            track.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(4 * d));
            tlp.topMargin = Math.round(6 * d);
            track.setLayoutParams(tlp);
            track.setBackground(Ui.bg(Ui.color(c, R.color.surface_alt), 2 * d));
            int doneSub = t.subDone();
            int totalSub = t.subTotal();
            View fill = new View(c);
            fill.setBackground(Ui.bg(t.done ? Ui.color(c, R.color.done) : levelColor, 2 * d));
            track.addView(fill, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0, doneSub)));
            track.addView(new View(c), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0, totalSub - doneSub)));
            mid.addView(track);
        }

        LinearLayout sub = Ui.row(c);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Math.round(3 * d);
        sub.setLayoutParams(slp);

        StringBuilder sb = new StringBuilder();
        if (t.due > 0) {
            if (showDate) {
                sb.append(Fmt.dateShort(t.due)).append(" ");
            }
            sb.append(t.hasTime ? Fmt.time(t.due) : "全天");
            sb.append(" · ");
        }
        sb.append(t.done ? "已完成" : Urgency.remaining(t, now));
        TextView subText = Ui.text(c, sb.toString(), 12f, R.color.text_dim);
        if (!t.done && lv <= Urgency.TODAY) {
            subText.setTextColor(levelColor);
        }
        sub.addView(subText);

        if (t.tag != null && !t.tag.isEmpty()) {
            TextView tag = Ui.text(c, "· " + t.tag, 12f, R.color.text_dim);
            tag.setPadding(Math.round(4 * d), 0, 0, 0);
            sub.addView(tag);
        }
        if (t.subTotal() > 0) {
            TextView subs = Ui.text(c, "☑ " + t.subDone() + "/" + t.subTotal(), 12f,
                    R.color.text_dim);
            subs.setPadding(Math.round(4 * d), 0, 0, 0);
            sub.addView(subs);
        }
        if (t.spentMin > 0) {
            TextView spent = Ui.text(c, "· 专注 " + t.spentMin + " 分", 12f, R.color.text_dim);
            spent.setPadding(Math.round(4 * d), 0, 0, 0);
            sub.addView(spent);
        }
        if (t.isRepeating()) {
            TextView rep = Ui.text(c, "· " + Todo.repeatName(t.repeat), 12f, R.color.text_dim);
            rep.setPadding(Math.round(4 * d), 0, 0, 0);
            sub.addView(rep);
        }
        mid.addView(sub);
        row.addView(mid);

        LinearLayout right = Ui.column(c);
        right.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = Math.round(6 * d);
        right.setLayoutParams(rlp);

        if (!t.done && t.priority >= Todo.PRI_HIGH) {
            int pc = Ui.color(c, t.priority == Todo.PRI_URGENT ? R.color.overdue : R.color.soon);
            right.addView(pill(c, Todo.priorityName(t.priority), pc));
        }
        if (!t.done) {
            right.addView(pill(c, Urgency.shortRemaining(t, now), levelColor));
        }
        row.addView(right);

        Ui.press(row);
        row.setOnClickListener(v -> {
            if (a != null) {
                a.onOpen(t);
            }
        });
        row.setOnLongClickListener(v -> {
            if (a != null) {
                a.onMenu(t, v);
            }
            return true;
        });
        return row;
    }

    private static TextView pill(Context c, String text, int color) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        t.setTextColor(0xFFFFFFFF);
        t.setBackground(Ui.pill(color));
        t.setPadding(Math.round(7 * d), Math.round(3 * d), Math.round(7 * d), Math.round(3 * d));
        t.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(2 * d);
        lp.bottomMargin = Math.round(2 * d);
        t.setLayoutParams(lp);
        return t;
    }
}
