package com.sudfry.todo.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sudfry.todo.R;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

/** 界面小零件：分组标题、卡片、胶囊按钮、分隔线。 */
public final class Widgets {

    private Widgets() {
    }

    /** 分组标题：左侧色条 + 名称 + 右侧计数胶囊。 */
    public static View sectionHeader(Context c, String title, int count, int accentColor) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout row = Ui.row(c);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Math.round(18 * d);
        rlp.bottomMargin = Math.round(9 * d);
        row.setLayoutParams(rlp);

        View dot = new View(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Math.round(4 * d), Math.round(16 * d));
        dlp.rightMargin = Math.round(8 * d);
        dot.setLayoutParams(dlp);
        dot.setBackground(Ui.bg(accentColor, 2 * d));
        row.addView(dot);

        TextView tv = Ui.text(c, title, 14f, R.color.text, true);
        tv.setLetterSpacing(0.01f);
        row.addView(tv);

        row.addView(new View(c), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (count > 0) {
            TextView cnt = Ui.textColored(c, String.valueOf(count), 11.5f, accentColor, true);
            cnt.setGravity(Gravity.CENTER);
            cnt.setBackground(Ui.pill((accentColor & 0x00FFFFFF) | 0x1F000000));
            cnt.setPadding(Math.round(9 * d), Math.round(2 * d),
                    Math.round(9 * d), Math.round(2 * d));
            row.addView(cnt);
        }
        return row;
    }

    public static LinearLayout card(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout l = Ui.column(c);
        l.setBackground(Ui.bgStroke(Ui.color(c, R.color.surface), Theme.cardRadius(c),
                Theme.cardStrokeColor(c), Theme.cardStroke(c)));
        l.setElevation(Theme.cardElevation(c));
        Ui.padDp(l, c, 15, 13, 15, 13);
        return l;
    }

    /** 可点的小胶囊按钮（筛选、快捷日期等）。 */
    public static TextView chip(Context c, String text, boolean active, int accentColor) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setGravity(Gravity.CENTER);
        float r = Theme.chipRadius(c);
        if (active) {
            t.setTextColor(0xFFFFFFFF);
            t.setBackground(r >= 999f ? Ui.pill(accentColor) : Ui.bg(accentColor, r));
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
        } else {
            t.setTextColor(Ui.color(c, R.color.text_dim));
            t.setBackground(Ui.bgStroke(Ui.color(c, R.color.surface), r,
                    Theme.cardStrokeColor(c), Theme.cardStroke(c)));
        }
        t.setPadding(Math.round(12 * d), Math.round(6 * d), Math.round(12 * d), Math.round(6 * d));
        Ui.press(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(8 * d);
        t.setLayoutParams(lp);
        return t;
    }

    public static View divider(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        View v = new View(c);
        v.setBackgroundColor(Ui.color(c, R.color.divider));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, Math.round(d))));
        return v;
    }

    /** 空状态：一个淡色图标 + 一句说明，比光秃秃一行字好看也好懂。 */
    public static View empty(Context c, String text) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout box = Ui.column(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, Math.round(26 * d), 0, Math.round(26 * d));

        TextView glyph = new TextView(c);
        glyph.setText("☰");
        glyph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        glyph.setTextColor(Ui.color(c, R.color.divider));
        glyph.setGravity(Gravity.CENTER);
        box.addView(glyph);

        TextView t = Ui.text(c, text, 13f, R.color.text_dim);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Math.round(10 * d), 0, 0);
        box.addView(t);
        return box;
    }
}
