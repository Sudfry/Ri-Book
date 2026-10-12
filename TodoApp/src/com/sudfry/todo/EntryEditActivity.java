package com.sudfry.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
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

import com.sudfry.todo.model.Categories;
import com.sudfry.todo.model.Entry;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

import java.time.LocalDate;
import java.util.ArrayList;

/**
 * 修改一笔账目。导入的账单分类猜得不一定准、金额也可能要订正，
 * 所以每一条都必须能完整改（而不是只能改类型或删掉）。
 * 所有点选都是原地刷新，页面不会重置、已输入的金额不会丢。
 */
public class EntryEditActivity extends Activity {

    private EntryStore store;
    private Entry entry;
    private boolean isNew;

    private int type;
    private String big;
    private String sub;
    private String customSub = "";
    private LocalDate date = LocalDate.now();
    private String platform = "银行卡";
    private boolean important;

    private EditText amountEt;
    private EditText noteEt;
    private EditText customEt;
    private android.widget.HorizontalScrollView bigRowBox;
    private LinearLayout bigRow;
    private android.widget.HorizontalScrollView subRowBox;
    private LinearLayout subRow;
    private LinearLayout customWrap;
    private TextView starView;
    private TextView expBtn;
    private TextView incBtn;
    private TextView dateChipView;

    private final ArrayList<TextView> bigChips = new ArrayList<>();
    private final ArrayList<String> bigNames = new ArrayList<>();
    private final ArrayList<TextView> platChips = new ArrayList<>();
    private final ArrayList<String> platNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = EntryStore.get(this);
        long id = getIntent().getLongExtra("id", 0);
        isNew = id <= 0;
        if (isNew) {
            entry = new Entry(Entry.today(), Entry.EXPENSE, 0, "日常餐饮", "午餐");
            type = Entry.EXPENSE;
            big = "日常餐饮";
            sub = "午餐";
        } else {
            Entry src = store.byId(id);
            if (src == null) {
                finish();
                return;
            }
            entry = src.copy();
            type = entry.type;
            big = entry.category == null || entry.category.isEmpty() ? "日常餐饮" : entry.category;
            sub = entry.sub == null || entry.sub.isEmpty() ? "" : entry.sub;
            platform = entry.platform == null || entry.platform.isEmpty() ? "银行卡" : entry.platform;
            important = entry.important;
            date = Entry.parseDay(entry.date);
        }
        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
        refreshAll();
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
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.text(this, isNew ? "新增账目" : "修改账目", 16f, R.color.text, true);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        TextView del = Ui.text(this, isNew ? "     " : "删除", 15f, R.color.overdue);
        del.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        del.setOnClickListener(v -> confirmDelete());
        bar.addView(del);
        root.addView(bar);
        root.addView(Widgets.divider(this));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout col = Ui.column(this);
        Ui.padDp(col, this, 14, 10, 14, 24);
        sv.addView(col);

        // 类型
        LinearLayout typeRow = Ui.row(this);
        expBtn = typeButton("支出", Entry.EXPENSE);
        incBtn = typeButton("收入", Entry.INCOME);
        typeRow.addView(expBtn);
        typeRow.addView(incBtn);
        col.addView(typeRow);

        // 金额
        LinearLayout amountCard = Widgets.card(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 12);
        amountCard.setLayoutParams(clp);
        LinearLayout amountRow = Ui.row(this);
        amountRow.addView(Ui.text(this, "金额", 13f, R.color.text_dim));
        amountEt = new EditText(this);
        amountEt.setHint("0.00");
        amountEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        amountEt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        amountEt.setGravity(Gravity.END);
        amountEt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (entry.amount > 0) {
            amountEt.setText(String.valueOf(entry.amount));
        }
        amountRow.addView(amountEt);
        amountRow.addView(Ui.text(this, " 元", 14f, R.color.text_dim));
        amountCard.addView(amountRow);
        col.addView(amountCard);

        // 分类
        col.addView(label("分类 · 大类"));
        bigRowBox = hScroll();
        bigRow = (LinearLayout) bigRowBox.getChildAt(0);
        for (final String name : Categories.bigNames(type)) {
            TextView chip = Widgets.chip(this, name, name.equals(big), Theme.accent(this));
            chip.setOnClickListener(v -> {
                tap(v);
                if (name.equals(big)) {
                    return;
                }
                big = name;
                String[] ss = Categories.subs(type, big);
                sub = ss.length > 0 ? ss[0] : "";
                refreshAll();
            });
            bigChips.add(chip);
            bigNames.add(name);
            bigRow.addView(chip);
        }
        col.addView(bigRowBox);

        col.addView(label("分类 · 小类"));
        subRowBox = hScroll();
        subRow = (LinearLayout) subRowBox.getChildAt(0);
        col.addView(subRowBox);

        customWrap = Ui.column(this);
        customEt = new EditText(this);
        customEt.setHint("自定义分类名称");
        customEt.setSingleLine(true);
        customEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        customWrap.addView(customEt);
        col.addView(customWrap);

        // 日期
        col.addView(label("日期"));
        android.widget.HorizontalScrollView dateBox = hScroll();
        LinearLayout dateRow = (LinearLayout) dateBox.getChildAt(0);
        dateRow.addView(dateQuick("今天", LocalDate.now()));
        dateRow.addView(dateQuick("昨天", LocalDate.now().minusDays(1)));
        dateRow.addView(dateQuick("前天", LocalDate.now().minusDays(2)));
        dateChipView = Widgets.chip(this, "", false, Theme.accent(this));
        dateChipView.setOnClickListener(v -> {
            tap(v);
            new DatePickerDialog(this, (dp, y, m, dd) -> {
                date = LocalDate.of(y, m + 1, dd);
                refreshAll();
            }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show();
        });
        dateRow.addView(dateChipView);
        col.addView(dateBox);

        // 支付方式
        col.addView(label("支付方式"));
        android.widget.HorizontalScrollView platBox = hScroll();
        LinearLayout platRow = (LinearLayout) platBox.getChildAt(0);
        for (final String p : Categories.PLATFORMS) {
            TextView chip = Widgets.chip(this, p, p.equals(platform), Theme.accent(this));
            chip.setOnClickListener(v -> {
                tap(v);
                platform = p;
                refreshAll();
            });
            platChips.add(chip);
            platNames.add(p);
            platRow.addView(chip);
        }
        col.addView(platBox);

        // 备注 + 重点
        col.addView(label("备注"));
        LinearLayout noteCard = Widgets.card(this);
        noteEt = new EditText(this);
        noteEt.setHint("选填");
        noteEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        noteEt.setText(entry.note == null ? "" : entry.note);
        noteCard.addView(noteEt);
        starView = Ui.text(this, "", 14f, R.color.text_dim, false);
        starView.setPadding(0, Ui.dp(this, 10), 0, 0);
        starView.setOnClickListener(v -> {
            tap(v);
            important = !important;
            refreshAll();
        });
        noteCard.addView(starView);
        col.addView(noteCard);

        // 底部固定按钮
        LinearLayout bottom = Ui.column(this);
        bottom.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bottom, this, 16, 10, 16, 12);
        TextView save = Ui.textColored(this, isNew ? "添加" : "保存修改", 16f,
                Theme.onAccent(this), true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 14)));
        save.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));
        save.setOnClickListener(v -> save());
        bottom.addView(save);

        root.addView(sv);
        root.addView(Widgets.divider(this));
        root.addView(bottom);
        return root;
    }

    private View label(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim, true);
        t.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        return t;
    }

    private android.widget.HorizontalScrollView hScroll() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(this);
        row.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hs.addView(row);
        return hs;
    }

    private TextView typeButton(String text, final int t) {
        int color = Ui.color(this, t == Entry.EXPENSE ? R.color.money_out : R.color.money_in);
        TextView v = Ui.text(this, text, 15f, R.color.text_dim, true);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
        lp.rightMargin = t == Entry.EXPENSE ? Ui.dp(this, 8) : 0;
        v.setLayoutParams(lp);
        v.setOnClickListener(view -> {
            tap(view);
            if (type == t) {
                return;
            }
            type = t;
            ArrayList<String> bigs = new ArrayList<>(Categories.bigNames(type));
            big = bigs.isEmpty() ? "" : bigs.get(0);
            String[] ss = Categories.subs(type, big);
            sub = ss.length > 0 ? ss[0] : "";
            customSub = "";
            refreshAll();
        });
        return v;
    }

    private TextView dateQuick(String text, final LocalDate day) {
        final TextView chip = Widgets.chip(this, text, false, Theme.accent(this));
        chip.setOnClickListener(v -> {
            tap(v);
            date = day;
            refreshAll();
        });
        dateQuickChips.add(chip);
        dateQuickDays.add(day);
        return chip;
    }

    private final ArrayList<TextView> dateQuickChips = new ArrayList<>();
    private final ArrayList<LocalDate> dateQuickDays = new ArrayList<>();

    // ───────────────────── 刷新（全部原地） ─────────────────────

    private void refreshAll() {
        // 类型按钮
        styleType(expBtn, Entry.EXPENSE);
        styleType(incBtn, Entry.INCOME);

        // 大类
        for (int i = 0; i < bigChips.size(); i++) {
            styleChip(bigChips.get(i), bigNames.get(i).equals(big));
        }
        // 小类
        subRow.removeAllViews();
        boolean custom = "自定义".equals(big);
        customWrap.setVisibility(custom ? View.VISIBLE : View.GONE);
        if (custom) {
            customEt.setText(customSub);
        }
        for (final String s : Categories.subs(type, big)) {
            if (custom && "自定义".equals(s)) {
                continue;
            }
            TextView chip = Widgets.chip(this, s, s.equals(sub), Theme.accent(this));
            chip.setOnClickListener(v -> {
                tap(v);
                sub = s;
                refreshAll();
            });
            subRow.addView(chip);
        }
        // 支付方式
        for (int i = 0; i < platChips.size(); i++) {
            styleChip(platChips.get(i), platNames.get(i).equals(platform));
        }
        // 日期
        for (int i = 0; i < dateQuickChips.size(); i++) {
            styleChip(dateQuickChips.get(i), dateQuickDays.get(i).equals(date));
        }
        dateChipView.setText(Fmt.date(date) + " " + Fmt.wd(date));
        boolean quick = date.equals(LocalDate.now()) || date.equals(LocalDate.now().minusDays(1))
                || date.equals(LocalDate.now().minusDays(2));
        styleChip(dateChipView, !quick);
        // 重点
        starView.setText(important ? "★ 已标记为重点" : "☆ 标记为重点");
        starView.setTextColor(Ui.color(this, important ? R.color.star : R.color.text_dim));
        starView.setTypeface(null, important ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void styleType(TextView v, int t) {
        if (v == null) {
            return;
        }
        boolean on = type == t;
        int color = Ui.color(this, t == Entry.EXPENSE ? R.color.money_out : R.color.money_in);
        v.setTextColor(on ? 0xFFFFFFFF : Ui.color(this, R.color.text_dim));
        v.setBackground(on ? Ui.bg(color, Ui.dp(this, 12))
                : Ui.bgStroke(Ui.color(this, R.color.surface), Ui.dp(this, 12),
                Ui.color(this, R.color.divider), d()));
    }

    private void styleChip(TextView t, boolean on) {
        t.setTextColor(on ? 0xFFFFFFFF : Ui.color(this, R.color.text_dim));
        t.setBackground(on ? Ui.pill(Theme.accent(this))
                : Ui.bgStroke(Ui.color(this, R.color.surface), 999f,
                Ui.color(this, R.color.divider), d()));
        t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ───────────────────── 保存 / 删除 ─────────────────────

    private void save() {
        double amount = Money.parse(amountEt.getText().toString());
        if (amount <= 0) {
            Toast.makeText(this, "请先填金额", Toast.LENGTH_SHORT).show();
            return;
        }
        String useSub = "自定义".equals(big) ? customEt.getText().toString().trim() : sub;
        if (useSub.isEmpty()) {
            useSub = "自定义".equals(big) ? "自定义" : sub;
        }
        entry.type = type;
        entry.amount = amount;
        entry.category = big;
        entry.sub = useSub;
        entry.date = Entry.dayOf(date);
        entry.platform = platform;
        entry.important = important;
        entry.note = noteEt.getText().toString().trim();
        if (isNew) {
            store.add(entry);
        } else {
            store.update(entry);
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        if (isNew) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录？")
                .setMessage(entry.label() + "  " + Money.yuan(entry.amount))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dlg, w) -> {
                    store.delete(entry.id);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }
}
