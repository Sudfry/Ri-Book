package com.sudfry.todo;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sudfry.todo.model.Categories;
import com.sudfry.todo.model.Entry;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 「快速记一笔」——从通知栏点一下就能进来的极简记账页。
 *
 * 为什么自己画数字键盘，而不是用 EditText：
 *   1) 这个页面只有一个目的——三秒内记完一笔。系统键盘弹出来会占掉半屏，
 *      盖住分类按钮，还得先点一下输入框才能打字，多两步；
 *   2) 自绘键盘可以把「1-9 / 0 / . / ⌫」排成固定格子，盲按也不会按错，
 *      而且不需要处理输入法、光标、粘贴板这些跟记账无关的东西；
 *   3) 金额直接就是字符串，不需要 Money.parse 以外的任何校验逻辑。
 *
 * 注意：这个页面不写任何颜色常量，一律走 Ui.color(...) 或 Theme.*，
 * 这样系统深色模式（values-night）和应用内换主题都能自动生效。
 */
public class QuickEntryActivity extends Activity {

    /** 常用分类只放前 N 个（其余分类进完整编辑页慢慢选）。 */
    private static final int COMMON_CATEGORIES = 6;

    /** 数字键盘的按键布局：四行三列。 */
    private static final String[][] KEYS = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {"0", ".", "⌫"},
    };

    private EntryStore store;

    /** 金额输入缓冲。始终非空，空的时候回落到 "0"，避免显示成 "¥"。 */
    private String amount = "0";
    /** 当前收支类型，初值来自 Intent，默认支出。 */
    private int type = Entry.EXPENSE;
    /** 选中的大类。切换收支类型时会被重置为该类型的第一个分类。 */
    private String category = "";

    private TextView amountView;
    private TextView hintView;
    private TextView saveBtn;
    private TextView expBtn;
    private TextView incBtn;
    /** 分类区的容器：切换收支时整块重建（分类表跟着类型变）。 */
    private LinearLayout catBox;
    /** 分类区的按钮，下标与 catNames 对齐，用来做「全部重刷选中态」。 */
    private final ArrayList<TextView> catViews = new ArrayList<>();
    private final ArrayList<String> catNames = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = EntryStore.get(this);
        // 通知栏的「记支出 / 记收入」两个 action 靠这个 extra 预选类型
        type = getIntent().getIntExtra("type", Entry.EXPENSE);
        if (type != Entry.EXPENSE && type != Entry.INCOME) {
            type = Entry.EXPENSE;
        }
        category = firstCategory(type);

        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
        refresh();
    }

    private void tap(View v) {
        Sfx.tap(v);
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    // ───────────────────── 界面 ─────────────────────

    private View buildUi() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        root.setFitsSystemWindows(true);

        root.addView(titleBar());
        root.addView(amountCard());
        root.addView(categoryArea());
        root.addView(typeRow());
        // 中间留白：把数字键盘推到接近底部的位置，拇指够得着
        root.addView(new View(this), Ui.lpw(ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(keypad());
        root.addView(saveArea());
        return root;
    }

    private View titleBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bar, this, 12, 10, 12, 10);

        TextView back = Ui.text(this, "‹ 返回", 15f, R.color.accent);
        back.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        back.setOnClickListener(v -> {
            tap(v);
            finish();
        });
        bar.addView(back);

        TextView title = Ui.text(this, "快速记账", 16f, R.color.text, true);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(Ui.lpw(0, 1f));
        bar.addView(title);

        // 右侧占位，让标题真正居中（和左「返回」等宽）
        TextView pad = Ui.text(this, "     ", 15f, R.color.text_dim);
        pad.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        bar.addView(pad);
        return bar;
    }

    private View amountCard() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.bg(Ui.color(this, R.color.surface), Ui.dp(this, 18)));
        Ui.padDp(card, this, 18, 16, 18, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), 0);
        card.setLayoutParams(lp);

        amountView = Ui.textColored(this, "¥0", 38f, Theme.accent(this), true);
        amountView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        amountView.setGravity(Gravity.CENTER);
        amountView.setSingleLine(true);
        card.addView(amountView);

        hintView = Ui.text(this, "", 12.5f, R.color.text_dim);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(0, Ui.dp(this, 6), 0, 0);
        card.addView(hintView);
        return card;
    }

    /** 常用分类：6 个大类铺成 2 行 3 列，点选即高亮。 */
    private View categoryArea() {
        catBox = Ui.column(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), 0);
        catBox.setLayoutParams(lp);
        rebuildCategories();
        return catBox;
    }

    private void rebuildCategories() {
        catBox.removeAllViews();
        catViews.clear();
        catNames.clear();

        List<String> bigs = Categories.bigNames(type);
        int n = Math.min(COMMON_CATEGORIES, bigs.size());
        for (int i = 0; i < n; i += 3) {
            LinearLayout row = Ui.row(this);
            for (int j = 0; j < 3; j++) {
                int idx = i + j;
                if (idx >= n) {
                    // 补一个空占位，保证最后一行的按钮宽度和上面几行一致
                    View holder = new View(this);
                    holder.setLayoutParams(Ui.lpw(0, 1f));
                    row.addView(holder);
                    continue;
                }
                final String name = bigs.get(idx);
                TextView chip = Ui.text(this, name, 13f, R.color.text_dim, true);
                chip.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams clp = Ui.lpw(0, 1f);
                clp.height = Ui.dp(this, 42);
                // 每行的左右外边距，形成列间距
                clp.setMargins(j == 0 ? 0 : Ui.dp(this, 4), Ui.dp(this, 4),
                        j == 2 ? 0 : Ui.dp(this, 4), j == 2 ? Ui.dp(this, 4) : Ui.dp(this, 4));
                chip.setLayoutParams(clp);
                chip.setOnClickListener(v -> {
                    tap(v);
                    category = name;
                    refresh();
                });
                catViews.add(chip);
                catNames.add(name);
                row.addView(chip);
            }
            catBox.addView(row);
        }
    }

    private View typeRow() {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), 0);
        row.setLayoutParams(lp);

        expBtn = typeButton("支出", Entry.EXPENSE);
        incBtn = typeButton("收入", Entry.INCOME);
        row.addView(expBtn);
        row.addView(incBtn);
        return row;
    }

    private TextView typeButton(String text, final int t) {
        TextView v = Ui.text(this, text, 15f, R.color.text_dim, true);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = Ui.lpw(0, 1f);
        lp.height = Ui.dp(this, 46);
        lp.rightMargin = t == Entry.EXPENSE ? Ui.dp(this, 5) : 0;
        lp.leftMargin = t == Entry.EXPENSE ? 0 : Ui.dp(this, 5);
        v.setLayoutParams(lp);
        v.setOnClickListener(view -> {
            tap(view);
            if (type == t) {
                return;
            }
            type = t;
            // 分类表是按收支分开的（支出 8 个大类、收入 4 个），
            // 换类型必须重建分类区，否则会留下「收入 + 日常餐饮」这种无效组合
            category = firstCategory(type);
            rebuildCategories();
            refresh();
        });
        return v;
    }

    private View keypad() {
        LinearLayout pad = Ui.column(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), 0);
        pad.setLayoutParams(lp);
        for (String[] line : KEYS) {
            LinearLayout row = Ui.row(this);
            for (String k : line) {
                row.addView(key(k));
            }
            pad.addView(row);
        }
        return pad;
    }

    private TextView key(final String label) {
        final boolean del = "⌫".equals(label);
        TextView v = Ui.text(this, label, del ? 20f : 22f, R.color.text, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.bg(Ui.color(this, R.color.surface), Ui.dp(this, 14)));
        LinearLayout.LayoutParams lp = Ui.lpw(0, 1f);
        lp.height = Ui.dp(this, 52);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        v.setLayoutParams(lp);
        v.setOnClickListener(view -> {
            tap(view);
            press(label);
        });
        return v;
    }

    private View saveArea() {
        LinearLayout bottom = Ui.column(this);
        bottom.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bottom, this, 16, 10, 16, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);
        bottom.setLayoutParams(lp);

        saveBtn = Ui.textColored(this, "保存", 16f, Theme.onAccent(this), true);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));
        saveBtn.setOnClickListener(v -> {
            if (!canSave()) {
                // 金额为 0 时不给存：与其存一条 0 元记录再让用户去删，不如当场拦住
                Toast.makeText(this, "请先输入金额", Toast.LENGTH_SHORT).show();
                return;
            }
            tap(v);
            save();
        });
        bottom.addView(saveBtn);
        return bottom;
    }

    // ───────────────────── 逻辑 ─────────────────────

    private String firstCategory(int t) {
        List<String> bigs = Categories.bigNames(t);
        return bigs.isEmpty() ? "" : bigs.get(0);
    }

    /** 键盘按下：数字 / 小数点 / 退格。金额只在这里被改写。 */
    private void press(String key) {
        if ("⌫".equals(key)) {
            if (amount.length() <= 1) {
                amount = "0";
            } else {
                amount = amount.substring(0, amount.length() - 1);
            }
        } else if (".".equals(key)) {
            // 已经有点了就不再插第二个，否则 Double.parseDouble 会解析失败
            if (!amount.contains(".")) {
                amount = amount + ".";
            }
        } else {
            if ("0".equals(amount)) {
                // 前导 0 不累积，避免出现 "007"
                amount = key;
            } else if (!tooLong()) {
                amount = amount + key;
            }
        }
        refresh();
    }

    /** 最多 9 位有效数字，够用且不会把金额显示顶出屏幕。 */
    private boolean tooLong() {
        int digits = 0;
        for (int i = 0; i < amount.length(); i++) {
            if (amount.charAt(i) != '.') {
                digits++;
            }
        }
        return digits >= 9;
    }

    private double value() {
        double v = Money.parse(amount);
        // 用户可能只敲了一个 "12." —— Money.parse 能解析，但 "-1" 之类的脏值一律当 0
        return v <= 0 ? 0 : v;
    }

    private boolean canSave() {
        return value() > 0;
    }

    /** 局部刷新：只改颜色、文字和背景，不动视图树，点起来不会有跳动。 */
    private void refresh() {
        int tint = Ui.color(this, type == Entry.EXPENSE ? R.color.money_out : R.color.money_in);
        amountView.setText("¥" + amount);
        amountView.setTextColor(tint);
        hintView.setText(Categories.typeName(type)
                + (category.isEmpty() ? "" : " · " + category));

        styleType(expBtn, Entry.EXPENSE);
        styleType(incBtn, Entry.INCOME);

        for (int i = 0; i < catViews.size(); i++) {
            boolean on = catNames.get(i).equals(category);
            TextView chip = catViews.get(i);
            chip.setTextColor(on ? Ui.color(this, R.color.on_color)
                    : Ui.color(this, R.color.text_dim));
            chip.setBackground(on ? Ui.bg(Theme.accent(this), Ui.dp(this, 12))
                    : Ui.bgStroke(Ui.color(this, R.color.surface), Ui.dp(this, 12),
                    Ui.color(this, R.color.divider), d()));
        }

        boolean ok = canSave();
        saveBtn.setEnabled(ok);
        saveBtn.setAlpha(ok ? 1f : 0.45f);
        saveBtn.setBackground(Ui.bg(ok ? Theme.accent(this)
                : Ui.color(this, R.color.divider), Ui.dp(this, 14)));
    }

    private void styleType(TextView v, int t) {
        if (v == null) {
            return;
        }
        boolean on = type == t;
        int color = Ui.color(this, t == Entry.EXPENSE ? R.color.money_out : R.color.money_in);
        v.setTextColor(on ? Ui.color(this, R.color.on_color) : Ui.color(this, R.color.text_dim));
        v.setBackground(on ? Ui.bg(color, Ui.dp(this, 12))
                : Ui.bgStroke(Ui.color(this, R.color.surface), Ui.dp(this, 12),
                Ui.color(this, R.color.divider), d()));
    }

    private void save() {
        double v = value();
        if (v <= 0) {
            return;
        }
        // 小类交给该大类的第一个预设项：这个页面只让用户选到大类，
        // 想要精确到「早餐 / 午餐」可以去完整编辑页改
        String[] subs = Categories.subs(type, category);
        String sub = subs.length > 0 ? subs[0] : category;

        Entry e = new Entry(Entry.today(), type, v, category, sub);
        e.platform = com.sudfry.todo.util.AppPrefs.get(this).defaultPlatform();
        store.add(e);

        Toast.makeText(this, "已记录 " + Money.yuan(v) + " · " + category,
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
