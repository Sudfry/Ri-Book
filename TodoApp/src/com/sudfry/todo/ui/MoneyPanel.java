package com.sudfry.todo.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sudfry.todo.R;
import com.sudfry.todo.model.Categories;
import com.sudfry.todo.model.Entry;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.util.AppPrefs;
import com.sudfry.todo.ui.Sheet;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「账目」页：记一笔 / 流水 / 月度结算 / 分类分析。
 * 功能对齐 ribook.html（日簿·收支管理）。
 */
public final class MoneyPanel {

    public interface Host {
        /** 账目变化后，通知外层刷新「今天」与「日历」。 */
        void onMoneyChanged();

        void toast(String s);

        /** 删除后给一条可撤销的提示（和外层待办删除用同一套）。 */
        void showUndo(String msg, Runnable restore);
    }

    public interface SubNav {
        void go(int sub);
    }

    public static final String[] SUB_NAMES = {"记一笔", "流水", "月度结算", "分类分析"};

    private final Activity act;
    private final Host host;
    private final SubNav nav;
    private final EntryStore store;
    private final AppPrefs prefs;
    private final int sub;

    // 页面状态用 static 保存：切标签、改主题、翻月份都会重建这一页，
    // 重建后必须记得用户选到哪儿（否则选分类时刚输入的金额会被清空）。
    private static int type = -1;
    private static String big;
    private static String subCat;
    private static String customSub = "";
    private static LocalDate date = LocalDate.now();
    private static String platform;
    private static boolean important;
    private static YearMonth month = YearMonth.now();
    private static YearMonth settleMonth = YearMonth.now();
    private static String analysisRange;
    private static int analysisType = Entry.EXPENSE;
    private static boolean sortByAmount = true;
    private static boolean sortDesc = true;

    public MoneyPanel(Activity a, Host h, SubNav n, int sub) {
        this.act = a;
        this.host = h;
        this.nav = n;
        this.sub = sub;
        this.store = EntryStore.get(a);
        this.prefs = AppPrefs.get(a);
        if (type < 0) {
            type = prefs.defaultType();
        }
        if (platform == null) {
            platform = prefs.defaultPlatform();
        }
        if (analysisRange == null) {
            analysisRange = prefs.analysisRange();
        }
        if (big == null) {
            List<String> bigs = Categories.bigNames(type);
            big = bigs.isEmpty() ? "" : bigs.get(0);
        }
        if (subCat == null) {
            String[] subs = Categories.subs(type, big);
            subCat = subs.length > 0 ? subs[0] : "";
        }
    }

    private float d() {
        return act.getResources().getDisplayMetrics().density;
    }

    private int accent() {
        return Theme.accent(act);
    }

    private void tap(View v) {
        Sfx.tap(v);
    }

    // ─────────────────────── 入口 ───────────────────────

    public View build() {
        LinearLayout col = Ui.column(act);
        Ui.padDp(col, act, 14, 8, 14, 90);

        // 来自支付通知的待确认记录，放在最上面提醒一下
        int pending = com.sudfry.todo.billing.PendingPays.count(act);
        if (pending > 0) {
            LinearLayout banner = Widgets.card(act);
            LinearLayout row = Ui.row(act);
            row.addView(Ui.text(act, "有 " + pending + " 条支付通知待确认", 13.5f,
                    R.color.text, true));
            row.addView(new View(act), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = Ui.textColored(act, "去确认 ›", 13f, Theme.accent(act), true);
            go.setOnClickListener(v -> {
                tap(v);
                act.startActivity(new android.content.Intent(act,
                        com.sudfry.todo.BillImportActivity.class));
            });
            row.addView(go);
            banner.addView(row);
            col.addView(banner);
        }

        // 子页切换
        LinearLayout segBox = Ui.row(act);
        segBox.setBackground(Ui.bgStroke(Ui.color(act, R.color.surface), 14 * d(),
                Ui.color(act, R.color.divider), d()));
        Ui.padDp(segBox, act, 4, 4, 4, 4);
        for (int i = 0; i < SUB_NAMES.length; i++) {
            final int idx = i;
            boolean on = idx == sub;
            TextView t = Ui.text(act, SUB_NAMES[i], 12.5f,
                    on ? R.color.text : R.color.text_dim, on);
            t.setGravity(Gravity.CENTER);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(act, 32), 1f));
            if (on) {
                t.setBackground(Ui.pill(accent()));
                t.setTextColor(Theme.onAccent(act));
            }
            t.setOnClickListener(v -> {
                tap(v);
                nav.go(idx);
            });
            segBox.addView(t);
        }
        col.addView(segBox);

        switch (sub) {
            case 0:
                buildCompose(col);
                break;
            case 1:
                buildFlow(col);
                break;
            case 2:
                buildSettle(col);
                break;
            default:
                buildAnalysis(col);
                break;
        }

        ScrollView sv = new ScrollView(act);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.addView(col);
        return sv;
    }

    // ─────────────────────── 记一笔 ───────────────────────

    private EditText amountEt;
    private EditText noteEt;
    private EditText customEt;
    private LinearLayout subRow;
    private View subRowBox;
    private LinearLayout customWrap;
    private TextView starView;
    private TextView datePickChip;
    private TextView expBtn;
    private TextView incBtn;
    private final ArrayList<TextView> bigChips = new ArrayList<>();
    private final ArrayList<String> bigChipNames = new ArrayList<>();
    private final ArrayList<TextView> platChips = new ArrayList<>();
    private final ArrayList<String> platChipNames = new ArrayList<>();
    private final ArrayList<TextView> dateChips = new ArrayList<>();
    private final ArrayList<LocalDate> dateChipDays = new ArrayList<>();

    private void buildCompose(LinearLayout col) {
        bigChips.clear();
        bigChipNames.clear();
        platChips.clear();
        platChipNames.clear();
        dateChips.clear();
        dateChipDays.clear();

        // 类型
        LinearLayout typeRow = Ui.row(act);
        typeRow.setPadding(0, Ui.dp(act, 12), 0, 0);
        expBtn = typeButton("支出", Entry.EXPENSE);
        incBtn = typeButton("收入", Entry.INCOME);
        typeRow.addView(expBtn);
        typeRow.addView(incBtn);
        col.addView(typeRow);

        // 金额
        LinearLayout amountCard = Widgets.card(act);
        LinearLayout amountRow = Ui.row(act);
        amountRow.addView(Ui.text(act, "金额", 13f, R.color.text_dim));
        amountEt = new EditText(act);
        amountEt.setHint("0.00");
        amountEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        amountEt.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        amountEt.setGravity(Gravity.END);
        amountEt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        amountRow.addView(amountEt);
        amountRow.addView(Ui.text(act, " 元", 14f, R.color.text_dim));
        amountCard.addView(amountRow);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(act, 10);
        amountCard.setLayoutParams(clp);
        col.addView(amountCard);

        // 分类（点选就地刷新，不重建页面，已输入的金额不会丢）
        col.addView(label("分类"));
        View bigBox = hScroll();
        LinearLayout bigRow = hRow(bigBox);
        for (final String name : Categories.bigNames(type)) {
            TextView chip = Widgets.chip(act, name, name.equals(big), accent());
            chip.setOnClickListener(v -> {
                tap(v);
                if (name.equals(big)) {
                    return;
                }
                big = name;
                String[] ss = Categories.subs(type, big);
                subCat = ss.length > 0 ? ss[0] : "";
                refreshForm();
            });
            bigChips.add(chip);
            bigChipNames.add(name);
            bigRow.addView(chip);
        }
        col.addView(bigBox);

        subRowBox = hScroll();
        subRow = hRow(subRowBox);
        col.addView(subRowBox);

        customWrap = Ui.column(act);
        customEt = new EditText(act);
        customEt.setHint("请输入自定义分类名称");
        customEt.setSingleLine(true);
        customEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        customEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                customSub = s.toString();
            }
        });
        customWrap.addView(customEt);
        col.addView(customWrap);

        // 日期
        col.addView(label("日期"));
        View dateBox = hScroll();
        LinearLayout dateRow = hRow(dateBox);
        dateRow.addView(dateChip("今天", LocalDate.now()));
        dateRow.addView(dateChip("昨天", LocalDate.now().minusDays(1)));
        dateRow.addView(dateChip("前天", LocalDate.now().minusDays(2)));
        datePickChip = Widgets.chip(act, Fmt.date(date) + " " + Fmt.wd(date), false, accent());
        datePickChip.setOnClickListener(v -> {
            tap(v);
            new DatePickerDialog(act, (dp, y, m, dd) -> {
                date = LocalDate.of(y, m + 1, dd);
                refreshForm();
            }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show();
        });
        dateRow.addView(datePickChip);
        col.addView(dateBox);

        // 支付平台
        col.addView(label("支付方式"));
        View platBox = hScroll();
        LinearLayout platRow = hRow(platBox);
        for (final String p : Categories.PLATFORMS) {
            TextView chip = Widgets.chip(act, p, p.equals(platform), accent());
            chip.setOnClickListener(v -> {
                tap(v);
                platform = p;
                prefs.setDefaultPlatform(p);
                refreshForm();
            });
            platChips.add(chip);
            platChipNames.add(p);
            platRow.addView(chip);
        }
        col.addView(platBox);

        // 备注 + 重点
        col.addView(label("备注"));
        LinearLayout noteCard = Widgets.card(act);
        noteEt = new EditText(act);
        noteEt.setHint("选填，例如：午餐、项目奖金");
        noteEt.setSingleLine(true);
        noteEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        noteCard.addView(noteEt);

        starView = Ui.text(act, "", 14f, R.color.text_dim, false);
        starView.setPadding(0, Ui.dp(act, 10), 0, 0);
        starView.setOnClickListener(v -> {
            tap(v);
            important = !important;
            refreshStar();
        });
        noteCard.addView(starView);
        col.addView(noteCard);

        // 保存
        TextView save = Ui.textColored(act, "保存收支记录", 16f, Theme.onAccent(act), true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(Ui.bg(accent(), Ui.dp(act, 14)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 50));
        slp.topMargin = Ui.dp(act, 16);
        save.setLayoutParams(slp);
        save.setOnClickListener(v -> {
            tap(v);
            saveEntry();
        });
        col.addView(save);

        // 每日预算
        col.addView(label("每日预算"));
        LinearLayout budgetCard = Widgets.card(act);
        budgetCard.addView(Ui.text(act, "设置每天可用的钱数，「今天」页会实时显示还剩多少。",
                12.5f, R.color.text_dim));
        LinearLayout brow = Ui.row(act);
        brow.setPadding(0, Ui.dp(act, 8), 0, 0);
        final EditText budgetEt = new EditText(act);
        budgetEt.setHint("0.00");
        budgetEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        budgetEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        if (prefs.dailyBudget() > 0) {
            budgetEt.setText(String.valueOf(prefs.dailyBudget()));
        }
        budgetEt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        brow.addView(budgetEt);
        TextView bsave = Widgets.chip(act, "保存", true, accent());
        bsave.setOnClickListener(v -> {
            tap(v);
            double v2 = Money.parse(budgetEt.getText().toString());
            prefs.setDailyBudget(v2 < 0 ? 0 : v2);
            host.toast(v2 > 0 ? "每日预算已设为 " + Money.yuan(v2) : "已清除每日预算");
            nav.go(0);
        });
        brow.addView(bsave);
        budgetCard.addView(brow);
        col.addView(budgetCard);

        // 导入支付宝 / 微信账单
        col.addView(label("自动记账"));
        LinearLayout importCard = Widgets.card(act);
        importCard.addView(Ui.text(act, "从支付宝 / 微信导入支出记录", 14f, R.color.text, true));
        importCard.addView(Ui.text(act,
                "① 官方账单导入：拿历史（两家都能导出 CSV）\n"
                        + "② 通知自动记账：开启通知使用权后自动记下以后的支付",
                12.5f, R.color.text_dim));
        TextView goImport = Ui.textColored(act, "打开设置 ›", 13.5f, Theme.accent(act), true);
        goImport.setPadding(0, Ui.dp(act, 10), 0, 0);
        goImport.setOnClickListener(v -> {
            tap(v);
            act.startActivity(new android.content.Intent(act,
                    com.sudfry.todo.BillImportActivity.class));
        });
        importCard.addView(goImport);
        col.addView(importCard);

        refreshForm();
        refreshStar();
    }

    /** 就地刷新整个表单（不重建页面，输入框内容不会丢）。 */
    private void refreshForm() {
        for (int i = 0; i < bigChips.size(); i++) {
            styleChip(bigChips.get(i), bigChipNames.get(i).equals(big));
        }
        for (int i = 0; i < platChips.size(); i++) {
            styleChip(platChips.get(i), platChipNames.get(i).equals(platform));
        }
        for (int i = 0; i < dateChips.size(); i++) {
            styleChip(dateChips.get(i), dateChipDays.get(i).equals(date));
        }
        if (datePickChip != null) {
            datePickChip.setText(Fmt.date(date) + " " + Fmt.wd(date));
            boolean quick = date.equals(LocalDate.now())
                    || date.equals(LocalDate.now().minusDays(1))
                    || date.equals(LocalDate.now().minusDays(2));
            styleChip(datePickChip, !quick);
        }
        if (expBtn != null) {
            styleTypeButton(expBtn, Entry.EXPENSE);
            styleTypeButton(incBtn, Entry.INCOME);
        }
        refreshSubChips();
    }

    private void refreshStar() {
        if (starView == null) {
            return;
        }
        starView.setText(important ? "★ 已标记为重点" : "☆ 标记为重点");
        starView.setTextColor(Ui.color(act, important ? R.color.star : R.color.text_dim));
        starView.setTypeface(null, important ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void styleChip(TextView t, boolean active) {
        if (active) {
            t.setTextColor(0xFFFFFFFF);
            t.setBackground(Ui.pill(accent()));
            t.setTypeface(null, Typeface.BOLD);
        } else {
            t.setTextColor(Ui.color(act, R.color.text_dim));
            t.setBackground(Ui.bgStroke(Ui.color(act, R.color.surface), 999f,
                    Ui.color(act, R.color.divider), d()));
            t.setTypeface(null, Typeface.NORMAL);
        }
    }

    private void styleTypeButton(TextView v, int t) {
        boolean on = type == t;
        int color = Ui.color(act, t == Entry.EXPENSE ? R.color.money_out : R.color.money_in);
        v.setTextColor(on ? 0xFFFFFFFF : Ui.color(act, R.color.text_dim));
        v.setBackground(on ? Ui.bg(color, Ui.dp(act, 12))
                : Ui.bgStroke(Ui.color(act, R.color.surface), Ui.dp(act, 12),
                Ui.color(act, R.color.divider), d()));
        v.setTypeface(null, on ? Typeface.BOLD : Typeface.BOLD);
    }

    private void refreshSubChips() {
        if (subRow == null) {
            return;
        }
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
            TextView chip = Widgets.chip(act, s, s.equals(subCat), accent());
            chip.setOnClickListener(v -> {
                tap(v);
                subCat = s;
                refreshSubChips();
            });
            subRow.addView(chip);
        }
    }

    private TextView typeButton(String label, final int t) {
        int color = Ui.color(act, t == Entry.EXPENSE ? R.color.money_out : R.color.money_in);
        TextView v = Ui.text(act, label, 15f, R.color.text_dim, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(Ui.bgStroke(Ui.color(act, R.color.surface), Ui.dp(act, 12),
                Ui.color(act, R.color.divider), d()));
        if (type == t) {
            v.setTextColor(0xFFFFFFFF);
            v.setBackground(Ui.bg(color, Ui.dp(act, 12)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(act, 44), 1f);
        lp.rightMargin = t == Entry.EXPENSE ? Ui.dp(act, 8) : 0;
        v.setLayoutParams(lp);
        v.setOnClickListener(view -> {
            tap(view);
            if (type == t) {
                return;
            }
            type = t;
            prefs.setDefaultType(t);
            List<String> bigs = Categories.bigNames(type);
            big = bigs.isEmpty() ? "" : bigs.get(0);
            String[] ss = Categories.subs(type, big);
            subCat = ss.length > 0 ? ss[0] : "";
            customSub = "";
            refreshForm();
        });
        return v;
    }

    private void saveEntry() {
        double amount = Money.parse(amountEt == null ? "" : amountEt.getText().toString());
        if (amount <= 0) {
            host.toast("请先填金额");
            return;
        }
        String useSub = "自定义".equals(big) ? customSub.trim() : subCat;
        if (useSub.isEmpty()) {
            useSub = "自定义".equals(big) ? "自定义" : subCat;
        }
        if (useSub.isEmpty()) {
            host.toast("请选择分类");
            return;
        }
        Entry e = new Entry(Entry.dayOf(date), type, amount, big, useSub);
        e.note = noteEt == null ? "" : noteEt.getText().toString().trim();
        e.important = important;
        e.platform = platform;
        store.add(e);
        important = false;
        if (amountEt != null) {
            amountEt.setText("");
        }
        if (noteEt != null) {
            noteEt.setText("");
        }
        host.toast("已记录：" + Categories.typeName(type) + " " + Money.yuan(amount));
        host.onMoneyChanged();
        nav.go(0);
    }

    // ─────────────────────── 流水 ───────────────────────

    private void buildFlow(LinearLayout col) {
        col.addView(label("流水"));
        col.addView(monthBar(this.month, ym -> {
            month = ym;
            nav.go(1);
        }));

        String prefix = EntryStore.monthPrefix(month);
        double[] t = store.rangeTotals(prefix);

        LinearLayout sumCard = Widgets.card(act);
        LinearLayout sumRow = Ui.row(act);
        sumRow.addView(stat("收入", Money.yuan(t[Entry.INCOME]), R.color.money_in));
        sumRow.addView(stat("支出", Money.yuan(t[Entry.EXPENSE]), R.color.money_out));
        sumRow.addView(stat("结余", Money.yuan(t[Entry.INCOME] - t[Entry.EXPENSE]),
                t[Entry.INCOME] - t[Entry.EXPENSE] >= 0 ? R.color.money_in : R.color.money_out));
        sumCard.addView(sumRow);
        col.addView(sumCard);

        List<Entry> all = store.all();
        Collections.sort(all, (a, b) -> {
            int c = b.date.compareTo(a.date);
            if (c != 0) {
                return c;
            }
            return Long.compare(b.createdAt, a.createdAt);
        });

        String lastDate = null;
        LinearLayout groupBox = null;
        double groupOut = 0, groupIn = 0;
        int shown = 0;
        for (Entry e : all) {
            if (e.date == null || !e.date.startsWith(prefix)) {
                continue;
            }
            if (lastDate == null || !lastDate.equals(e.date)) {
                if (groupBox != null) {
                    groupBox.addView(groupTotal(groupOut, groupIn));
                }
                lastDate = e.date;
                groupOut = 0;
                groupIn = 0;
                LocalDate ld = Entry.parseDay(e.date);
                col.addView(Widgets.sectionHeader(act,
                        Fmt.dayLabel(ld, LocalDate.now()) + " · " + Fmt.date(ld) + " " + Fmt.wd(ld),
                        0, Ui.color(act, R.color.none)));
                groupBox = Ui.column(act);
                col.addView(groupBox);
            }
            if (e.isIncome()) {
                groupIn += e.amount;
            } else {
                groupOut += e.amount;
            }
            View row = EntryRow.build(act, e, new EntryRow.Actions() {
                @Override
                public void onOpen(Entry en) {
                    editEntry(en);
                }

                @Override
                public void onDelete(Entry en) {
                    confirmDeleteEntry(en);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(act, 6);
            row.setLayoutParams(lp);
            groupBox.addView(row);
            shown++;
        }
        if (groupBox != null) {
            groupBox.addView(groupTotal(groupOut, groupIn));
        }
        if (shown == 0) {
            col.addView(Widgets.empty(act, "这个月还没有记账，去「记一笔」添加"));
        }
    }

    private View groupTotal(double out, double in) {
        LinearLayout row = Ui.row(act);
        row.setGravity(Gravity.END);
        row.setPadding(0, 0, Ui.dp(act, 4), Ui.dp(act, 10));
        TextView t = Ui.text(act, "小计  收入 " + Money.yuan(in) + "   支出 " + Money.yuan(out),
                12f, R.color.text_dim);
        row.addView(t);
        return row;
    }

    private void editEntry(Entry e) {
        // 每笔账目都要能完整改（金额/分类/日期/备注），所以开独立编辑页
        android.content.Intent i = new android.content.Intent(act,
                com.sudfry.todo.EntryEditActivity.class);
        i.putExtra("id", e.id);
        act.startActivity(i);
    }

    private void confirmDeleteEntry(final Entry e) {
        Sheet.confirm(act, "删除这条记录？",
                "删除后底部会出现「撤销」，8 秒内可以恢复。",
                e.date + "   " + (e.isIncome() ? "+" : "-") + Money.yuan(e.amount)
                        + "   " + e.label(),
                "删除", true, () -> {
                    final Entry removed = store.delete(e.id);
                    host.onMoneyChanged();
                    nav.go(sub);
                    if (removed != null) {
                        host.showUndo("已删除「" + removed.label() + "」", () -> {
                            store.restore(removed);
                            host.toast("已恢复");
                            host.onMoneyChanged();
                            nav.go(sub);
                        });
                    }
                });
    }

    // ─────────────────────── 月度结算 ───────────────────────

    private void buildSettle(LinearLayout col) {
        col.addView(label("月度结算"));
        col.addView(monthBar(settleMonth, ym -> {
            settleMonth = ym;
            nav.go(2);
        }));

        String prefix = EntryStore.monthPrefix(settleMonth);
        double[] total = store.rangeTotals(prefix);
        List<String> selected = new ArrayList<>(prefs.settleCats());
        double selectedOut = store.settleExpense(prefix, selected);
        double fee = prefs.livingFee();

        LinearLayout feeCard = Widgets.card(act);
        feeCard.addView(Ui.text(act, "月生活费", 13f, R.color.text_dim));
        LinearLayout frow = Ui.row(act);
        frow.setPadding(0, Ui.dp(act, 6), 0, 0);
        final EditText feeEt = new EditText(act);
        feeEt.setHint("0.00");
        feeEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        feeEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        if (fee > 0) {
            feeEt.setText(String.valueOf(fee));
        }
        feeEt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        frow.addView(feeEt);
        TextView save = Widgets.chip(act, "保存", true, accent());
        save.setOnClickListener(v -> {
            tap(v);
            double v2 = Money.parse(feeEt.getText().toString());
            prefs.setLivingFee(v2 < 0 ? 0 : v2);
            host.toast("月生活费已保存");
            nav.go(2);
        });
        frow.addView(save);
        feeCard.addView(frow);
        col.addView(feeCard);

        LinearLayout sumCard = Widgets.card(act);
        LinearLayout r1 = Ui.row(act);
        r1.addView(stat("月收入", Money.yuan(total[Entry.INCOME]), R.color.money_in));
        r1.addView(stat("选定支出", Money.yuan(selectedOut), R.color.money_out));
        r1.addView(stat("生活费剩余", Money.yuan(fee - selectedOut),
                fee - selectedOut >= 0 ? R.color.money_in : R.color.money_out));
        sumCard.addView(r1);
        TextView note = Ui.text(act, "只统计勾选了的支出分类；月收入统计当月全部收入。",
                11.5f, R.color.text_dim);
        note.setPadding(0, Ui.dp(act, 8), 0, 0);
        sumCard.addView(note);
        col.addView(sumCard);

        col.addView(label("计入生活费扣除的分类"));
        LinearLayout tools = Ui.row(act);
        TextView all = Widgets.chip(act, "全选", false, accent());
        all.setOnClickListener(v -> {
            tap(v);
            prefs.setSettleCats(Categories.allExpenseSubs());
            nav.go(2);
        });
        TextView none = Widgets.chip(act, "全不选", false, accent());
        none.setOnClickListener(v -> {
            tap(v);
            prefs.setSettleCats(new ArrayList<>());
            nav.go(2);
        });
        tools.addView(all);
        tools.addView(none);
        col.addView(tools);

        for (final String bigName : Categories.bigNames(Entry.EXPENSE)) {
            String[] subs = Categories.subs(Entry.EXPENSE, bigName);
            if ("自定义".equals(bigName)) {
                continue;
            }
            LinearLayout card = Widgets.card(act);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = Ui.dp(act, 8);
            card.setLayoutParams(clp);

            int onCount = 0;
            for (String s : subs) {
                if (selected.contains(s)) {
                    onCount++;
                }
            }
            final boolean allOn = onCount == subs.length;
            LinearLayout head = Ui.row(act);
            TextView title = Ui.text(act, bigName, 14f, R.color.text, true);
            title.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(title);
            TextView toggle = Ui.text(act, allOn ? "☑ 全选" : "☐ 全选", 12.5f,
                    allOn ? R.color.text : R.color.text_dim, false);
            toggle.setOnClickListener(v -> {
                tap(v);
                List<String> cur = new ArrayList<>(prefs.settleCats());
                if (allOn) {
                    cur.removeAll(java.util.Arrays.asList(subs));
                } else {
                    for (String s : subs) {
                        if (!cur.contains(s)) {
                            cur.add(s);
                        }
                    }
                }
                prefs.setSettleCats(cur);
                nav.go(2);
            });
            head.addView(toggle);
            card.addView(head);

            LinearLayout grid = Ui.column(act);
            LinearLayout line = null;
            int perLine = 0;
            for (final String s : subs) {
                if (line == null || perLine >= 2) {
                    line = Ui.row(act);
                    grid.addView(line);
                    perLine = 0;
                }
                final boolean on = selected.contains(s);
                TextView chip = Ui.text(act, (on ? "☑ " : "☐ ") + s, 12.5f,
                        on ? R.color.text : R.color.text_dim, on);
                chip.setPadding(Ui.dp(act, 8), Ui.dp(act, 6), Ui.dp(act, 8), Ui.dp(act, 6));
                chip.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                chip.setOnClickListener(v -> {
                    tap(v);
                    List<String> cur = new ArrayList<>(prefs.settleCats());
                    if (cur.contains(s)) {
                        cur.remove(s);
                    } else {
                        cur.add(s);
                    }
                    prefs.setSettleCats(cur);
                    nav.go(2);
                });
                line.addView(chip);
                perLine++;
            }
            card.addView(grid);
            col.addView(card);
        }
    }

    // ─────────────────────── 分类分析 ───────────────────────

    private void buildAnalysis(LinearLayout col) {
        col.addView(label("分类分析"));

        LinearLayout tabs = Ui.row(act);
        TextView monthTab = Widgets.chip(act, "按月", "month".equals(analysisRange), accent());
        monthTab.setOnClickListener(v -> {
            tap(v);
            analysisRange = "month";
            prefs.setAnalysisRange("month");
            nav.go(3);
        });
        TextView yearTab = Widgets.chip(act, "按年", "year".equals(analysisRange), accent());
        yearTab.setOnClickListener(v -> {
            tap(v);
            analysisRange = "year";
            prefs.setAnalysisRange("year");
            nav.go(3);
        });
        tabs.addView(monthTab);
        tabs.addView(yearTab);
        col.addView(tabs);

        if ("month".equals(analysisRange)) {
            col.addView(monthBar(this.month, ym -> {
                month = ym;
                nav.go(3);
            }));
        } else {
            LinearLayout yb = Ui.row(act);
            TextView prev = Ui.text(act, "‹", 22f, R.color.accent);
            prev.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), 0);
            final TextView label = Ui.text(act, month.getYear() + " 年", 16f, R.color.text, true);
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            label.setGravity(Gravity.CENTER);
            TextView next = Ui.text(act, "›", 22f, R.color.accent);
            next.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), 0);
            prev.setOnClickListener(v -> {
                tap(v);
                month = month.minusYears(1);
                nav.go(3);
            });
            next.setOnClickListener(v -> {
                tap(v);
                month = month.plusYears(1);
                nav.go(3);
            });
            yb.addView(prev);
            yb.addView(label);
            yb.addView(next);
            col.addView(yb);
        }

        View optsBox = hScroll();
        LinearLayout opts = hRow(optsBox);
        opts.addView(chip3("支出分类", analysisType == Entry.EXPENSE, v -> {
            analysisType = Entry.EXPENSE;
            nav.go(3);
        }));
        opts.addView(chip3("收入分类", analysisType == Entry.INCOME, v -> {
            analysisType = Entry.INCOME;
            nav.go(3);
        }));
        opts.addView(chip3(sortByAmount ? "按金额" : "按名称", false, v -> {
            sortByAmount = !sortByAmount;
            nav.go(3);
        }));
        opts.addView(chip3(sortDesc ? "由高到低" : "由低到高", false, v -> {
            sortDesc = !sortDesc;
            nav.go(3);
        }));
        col.addView(optsBox);

        String prefix = "month".equals(analysisRange)
                ? EntryStore.monthPrefix(month)
                : String.format("%04d-", month.getYear());
        String rangeLabel = "month".equals(analysisRange)
                ? Fmt.monthTitle(month) : (month.getYear() + " 年");

        LinkedHashMap<String, LinkedHashMap<String, Double>> data =
                store.byCategory(prefix, analysisType);

        // 组装可排序列表
        List<Object[]> groups = new ArrayList<>();   // {名称, 合计, LinkedHashMap 子项}
        for (Map.Entry<String, LinkedHashMap<String, Double>> en : data.entrySet()) {
            double sum = 0;
            for (double v : en.getValue().values()) {
                sum += v;
            }
            groups.add(new Object[]{en.getKey(), sum, en.getValue()});
        }
        Collections.sort(groups, (a, b) -> {
            int cmp;
            if (sortByAmount) {
                cmp = Double.compare((Double) a[1], (Double) b[1]);
            } else {
                cmp = ((String) a[0]).compareTo((String) b[0]);
            }
            return sortDesc ? -cmp : cmp;
        });

        double total = 0;
        for (Object[] g : groups) {
            total += (Double) g[1];
        }

        LinearLayout totalNote = Widgets.card(act);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(act, 10);
        totalNote.setLayoutParams(tlp);
        totalNote.addView(Ui.text(act, rangeLabel + " · 共 " + Money.yuan(total)
                + "（" + Categories.typeName(analysisType) + "）", 13.5f, R.color.text, true));
        col.addView(totalNote);

        if (groups.isEmpty()) {
            col.addView(Widgets.empty(act, "当前范围暂无可统计的分类数据"));
            return;
        }

        int color = Ui.color(act, analysisType == Entry.EXPENSE ? R.color.money_out : R.color.money_in);
        for (Object[] g : groups) {
            String name = (String) g[0];
            double sum = (Double) g[1];
            @SuppressWarnings("unchecked")
            LinkedHashMap<String, Double> children = (LinkedHashMap<String, Double>) g[2];
            col.addView(categoryBlock(name, sum, total, children, color));
        }
    }

    private View categoryBlock(String name, double sum, double total,
                               LinkedHashMap<String, Double> children, int color) {
        LinearLayout box = Ui.column(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 10);
        box.setLayoutParams(blp);

        LinearLayout head = Ui.row(act);
        final TextView chevron = Ui.text(act, "›", 15f, R.color.text_dim, true);
        chevron.setPadding(0, 0, Ui.dp(act, 6), 0);
        head.addView(chevron);
        TextView nm = Ui.text(act, name, 14f, R.color.text, true);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(nm);
        TextView val = Ui.text(act, Money.yuan(sum), 13.5f, R.color.text, true);
        val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        head.addView(val);
        box.addView(head);

        box.addView(bar(sum / Math.max(1, total), color, 8));

        LinearLayout kids = Ui.column(act);
        Ui.padDp(kids, act, 16, 6, 0, 0);
        kids.setVisibility(View.GONE);
        List<Map.Entry<String, Double>> list = new ArrayList<>(children.entrySet());
        Collections.sort(list, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> e : list) {
            LinearLayout row = Ui.row(act);
            TextView sub = Ui.text(act, e.getKey(), 12.5f, R.color.text_dim);
            sub.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 76),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(sub);
            LinearLayout track = new LinearLayout(act);
            track.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, Ui.dp(act, 6), 1f);
            tlp.topMargin = Ui.dp(act, 5);
            tlp.bottomMargin = Ui.dp(act, 5);
            track.setLayoutParams(tlp);
            track.setBackground(Ui.bg(Ui.color(act, R.color.surface_alt), Ui.dp(act, 3)));
            row.addView(track);
            addBarSegments(track, e.getValue() / Math.max(0.01, sum), color);
            TextView v = Ui.text(act, Money.yuan(e.getValue()), 12f, R.color.text_dim);
            v.setTypeface(Typeface.MONOSPACE);
            v.setGravity(Gravity.END);
            v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 84),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(v);
            kids.addView(row);
        }
        box.addView(kids);

        head.setOnClickListener(v -> {
            tap(v);
            boolean show = kids.getVisibility() != View.VISIBLE;
            kids.setVisibility(show ? View.VISIBLE : View.GONE);
            chevron.setText(show ? "⌄" : "›");
        });
        return box;
    }

    /** 按百分比填充的条形。 */
    private View bar(double pct, int color, int heightDp) {
        LinearLayout track = new LinearLayout(act);
        track.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, heightDp));
        lp.topMargin = Ui.dp(act, 6);
        track.setLayoutParams(lp);
        track.setBackground(Ui.bg(Ui.color(act, R.color.surface_alt), Ui.dp(act, heightDp / 2f)));
        addBarSegments(track, pct, color);
        return track;
    }

    private void addBarSegments(LinearLayout track, double pct, int color) {
        int fill = (int) Math.round(Math.max(1, Math.min(100, pct * 100)));
        View f = new View(act);
        f.setBackground(Ui.bg(color, Ui.dp(act, 4)));
        track.addView(f, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, fill));
        if (fill < 100) {
            View rest = new View(act);
            track.addView(rest, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 100 - fill));
        }
    }

    // ─────────────────────── 复用零件 ───────────────────────

    private interface MonthPick {
        void on(YearMonth ym);
    }

    private View monthBar(YearMonth ym, MonthPick cb) {
        LinearLayout bar = Ui.row(act);
        TextView prev = Ui.text(act, "‹", 22f, R.color.accent);
        prev.setPadding(Ui.dp(act, 14), 0, Ui.dp(act, 14), 0);
        TextView label = Ui.text(act, Fmt.monthTitle(ym), 16f, R.color.text, true);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        label.setGravity(Gravity.CENTER);
        TextView next = Ui.text(act, "›", 22f, R.color.accent);
        next.setPadding(Ui.dp(act, 14), 0, Ui.dp(act, 14), 0);
        prev.setOnClickListener(v -> {
            tap(v);
            cb.on(ym.minusMonths(1));
        });
        next.setOnClickListener(v -> {
            tap(v);
            cb.on(ym.plusMonths(1));
        });
        bar.addView(prev);
        bar.addView(label);
        bar.addView(next);
        return bar;
    }

    private View stat(String name, String value, int colorRes) {
        LinearLayout box = Ui.column(act);
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = Ui.text(act, value, 15f, colorRes, true);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        box.addView(v);
        TextView n = Ui.text(act, name, 11.5f, R.color.text_dim);
        n.setPadding(0, Ui.dp(act, 3), 0, 0);
        box.addView(n);
        return box;
    }

    private TextView label(String s) {
        TextView t = Ui.text(act, s, 12.5f, R.color.text_dim, true);
        t.setPadding(0, Ui.dp(act, 16), 0, Ui.dp(act, 6));
        return t;
    }

    /** 横向滚动的容器；内容行用 hRow(box) 取。 */
    private android.widget.HorizontalScrollView hScroll() {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(act);
        // 必须显式给 WRAP_CONTENT：否则行会被撑成 MATCH_PARENT，
        // 高度解析为 0，里面的胶囊按钮就点不到了（这个 bug 真的踩过）。
        row.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hs.addView(row);
        return hs;
    }

    private LinearLayout hRow(View box) {
        return (LinearLayout) ((ViewGroup) box).getChildAt(0);
    }

    private interface Tap {
        void on(View v);
    }

    private TextView chip3(String text, boolean active, final Tap t) {
        TextView chip = Widgets.chip(act, text, active, accent());
        chip.setOnClickListener(v -> {
            tap(v);
            t.on(v);
        });
        return chip;
    }

    private TextView dateChip(String label, final LocalDate day) {
        boolean on = date.equals(day);
        TextView chip = Widgets.chip(act, label, on, accent());
        chip.setOnClickListener(v -> {
            tap(v);
            date = day;
            refreshForm();
        });
        dateChips.add(chip);
        dateChipDays.add(day);
        return chip;
    }
}
