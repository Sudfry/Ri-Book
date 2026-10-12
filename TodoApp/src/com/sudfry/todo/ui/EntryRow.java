package com.sudfry.todo.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sudfry.todo.R;
import com.sudfry.todo.model.Entry;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

/** 流水里的一行账目。 */
public final class EntryRow {

    public interface Actions {
        void onOpen(Entry e);

        void onDelete(Entry e);
    }

    private EntryRow() {
    }

    public static View build(Context c, Entry e, Actions a) {
        float d = c.getResources().getDisplayMetrics().density;
        int color = Ui.color(c, e.isIncome() ? R.color.money_in : R.color.money_out);

        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.bgStroke(Ui.color(c, R.color.surface), Theme.rowRadius(c),
                Ui.color(c, R.color.divider), 1 * d));
        Ui.padDp(row, c, 12, 11, 12, 11);

        View dot = new View(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Math.round(9 * d), Math.round(9 * d));
        dlp.rightMargin = Math.round(10 * d);
        dot.setLayoutParams(dlp);
        dot.setBackground(Ui.pill(color));
        row.addView(dot);

        LinearLayout mid = Ui.column(c);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = Ui.text(c, e.label(), 14.5f, R.color.text, true);
        if (e.important) {
            title.setText("★ " + title.getText());
            title.setTextColor(Ui.color(c, R.color.star));
        }
        mid.addView(title);

        StringBuilder sb = new StringBuilder();
        sb.append(e.category == null || e.category.isEmpty() ? "未分类" : e.category);
        if (e.platform != null && !e.platform.isEmpty()) {
            sb.append(" · ").append(e.platform);
        }
        if (e.note != null && !e.note.isEmpty()) {
            sb.append(" · ").append(e.note);
        }
        TextView sub = Ui.text(c, sb.toString(), 12f, R.color.text_dim);
        sub.setPadding(0, Math.round(3 * d), 0, 0);
        sub.setMaxLines(1);
        mid.addView(sub);
        row.addView(mid);

        TextView amount = Ui.text(c, (e.isIncome() ? "+" : "−") + Money.yuan(e.amount).substring(1),
                15f, R.color.text, true);
        amount.setTextColor(color);
        amount.setGravity(Gravity.END);
        amount.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(amount);

        Ui.press(row);
        row.setOnClickListener(v -> {
            if (a != null) {
                a.onOpen(e);
            }
        });
        row.setOnLongClickListener(v -> {
            if (a != null) {
                a.onDelete(e);
            }
            return true;
        });
        return row;
    }
}
