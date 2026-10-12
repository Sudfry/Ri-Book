package com.sudfry.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.sudfry.todo.model.Categories;
import com.sudfry.todo.model.Entry;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.ui.Sheet;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.AppPrefs;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

/** 设置：外观（明暗 + 主题色）、音效、记账默认值、数据。 */
public class SettingsActivity extends Activity {

    /** 作者仓库。 */
    private static final String GITHUB_URL = "https://github.com/Sudfry/Ri-Book";

    private AppPrefs prefs;
    private LinearLayout root;
    private View previewSwatch;
    private TextView previewText;

    private ScrollView scrollView;
    private java.util.List<TextView> styleChips;
    private TextView styleHintView;
    private int pendingScroll = -1;
    /** 各组的胶囊按钮，点选时就地改样式 —— 不再 recreate()（那会让整页跳回顶部）。 */
    private final java.util.ArrayList<TextView> themeModeChips = new java.util.ArrayList<>();
    private final java.util.ArrayList<TextView> soundTypeChips = new java.util.ArrayList<>();
    private final java.util.ArrayList<TextView> defTypeChips = new java.util.ArrayList<>();
    private final java.util.ArrayList<TextView> defPlatChips = new java.util.ArrayList<>();
    private TextView dataCountLabel;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = AppPrefs.get(this);
        if (b != null) {
            pendingScroll = b.getInt("settings_scroll", -1);
        }
        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
        if (pendingScroll > 0 && scrollView != null) {
            scrollView.post(() -> scrollView.scrollTo(0, pendingScroll));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (scrollView != null) {
            out.putInt("settings_scroll", scrollView.getScrollY());
        }
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    private void tap(View v) {
        Sfx.tap(v);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private View buildUi() {
        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.color(this, R.color.bg));

        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.color(this, R.color.surface));
        Ui.padDp(bar, this, 12, 10, 12, 10);
        TextView back = Ui.text(this, "‹ 返回", 15f, R.color.accent);
        back.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.text(this, "设置", 16f, R.color.text, true);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        bar.addView(Ui.text(this, "      ", 15f, R.color.accent));
        col.addView(bar);
        col.addView(Widgets.divider(this));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scrollView = sv;
        root = Ui.column(this);
        Ui.padDp(root, this, 14, 6, 14, 40);
        sv.addView(root);

        buildAppearance();
        buildHelp();
        buildPalette();
        buildStylePack();
        buildSound();
        buildMoney();
        buildData();
        buildAbout();

        col.addView(sv);
        return col;
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim, true);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return t;
    }

    // ───────────────── 外观 ─────────────────

    /** 教程与备份入口：放在设置最上面，找得到。 */
    private void buildHelp() {
        root.addView(section("帮助"));
        LinearLayout card = Widgets.card(this);

        LinearLayout gRow = Ui.row(this);
        LinearLayout gTxt = Ui.column(this);
        gTxt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        gTxt.addView(Ui.text(this, "使用教程", 14.5f, R.color.text, true));
        gTxt.addView(Ui.text(this, "每个功能怎么用、为什么这么设计，一条条讲清楚",
                11.5f, R.color.text_dim));
        gRow.addView(gTxt);
        gRow.addView(Ui.textColored(this, "查看 ›", 13.5f, Theme.accent(this), true));
        gRow.setOnClickListener(v -> {
            tap(v);
            startActivity(new Intent(this, GuideActivity.class));
        });
        card.addView(gRow);

        LinearLayout bRow = Ui.row(this);
        bRow.setPadding(0, Ui.dp(this, 14), 0, 0);
        LinearLayout bTxt = Ui.column(this);
        bTxt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bTxt.addView(Ui.text(this, "备份与恢复", 14.5f, R.color.text, true));
        bTxt.addView(Ui.text(this, "导出 / 导入 / 自动备份（间隔可调）", 11.5f, R.color.text_dim));
        bRow.addView(bTxt);
        bRow.addView(Ui.textColored(this, "打开 ›", 13.5f, Theme.accent(this), true));
        bRow.setOnClickListener(v -> {
            tap(v);
            startActivity(new Intent(this, BackupActivity.class));
        });
        card.addView(bRow);
        root.addView(card);
    }

    private void buildAppearance() {
        root.addView(section("外观 · 明暗模式"));
        LinearLayout card = Widgets.card(this);
        LinearLayout modes = Ui.row(this);
        String[] names = {"跟随系统", "浅色", "深色"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            boolean on = prefs.themeMode() == i;
            TextView chip = Widgets.chip(this, names[i], on, Theme.accent(this));
            themeModeChips.add(chip);
            chip.setOnClickListener(v -> {
                tap(v);
                prefs.setThemeMode(mode);
                restyle(themeModeChips, mode);
                Theme.applyNightMode(this);
                if (!Theme.supportsManualNight() && mode != 0) {
                    toast("这台设备是 Android 11 或更早，只能跟随系统");
                }
            });
            modes.addView(chip);
        }
        card.addView(modes);
        if (!Theme.supportsManualNight()) {
            TextView hint = Ui.text(this, "Android 12 以下不支持单独给应用强制明暗，只能跟随系统。",
                    11.5f, R.color.text_dim);
            hint.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(hint);
        }
        root.addView(card);

        root.addView(section("外观 · 主题色"));
        LinearLayout colorCard = Widgets.card(this);
        LinearLayout previewRow = Ui.row(this);
        previewSwatch = new View(this);
        previewSwatch.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        previewSwatch.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 12)));
        previewRow.addView(previewSwatch);
        previewText = Ui.text(this, hslText(), 13f, R.color.text_dim);
        previewText.setPadding(Ui.dp(this, 12), 0, 0, 0);
        previewText.setTypeface(Typeface.MONOSPACE);
        previewRow.addView(previewText);
        colorCard.addView(previewRow);

        final TextView hueLabel = sliderLabel("色相  " + prefs.hue() + "°");
        colorCard.addView(hueLabel);
        SeekBar hueBar = new SeekBar(this);
        hueBar.setMax(360);
        hueBar.setProgress(prefs.hue());
        colorCard.addView(hueBar);

        final TextView satLabel = sliderLabel("饱和度  " + prefs.sat() + "%");
        colorCard.addView(satLabel);
        SeekBar satBar = new SeekBar(this);
        satBar.setMax(80);
        satBar.setProgress(prefs.sat());
        colorCard.addView(satBar);

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                prefs.setAccent(hueBar.getProgress(), satBar.getProgress());
                hueLabel.setText("色相  " + hueBar.getProgress() + "°");
                satLabel.setText("饱和度  " + satBar.getProgress() + "%");
                previewSwatch.setBackground(Ui.bg(Theme.accent(SettingsActivity.this),
                        Ui.dp(SettingsActivity.this, 12)));
                previewText.setText(hslText());
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                Sfx.click(SettingsActivity.this);
            }
        };
        hueBar.setOnSeekBarChangeListener(l);
        satBar.setOnSeekBarChangeListener(l);

        LinearLayout presets = Ui.row(this);
        presets.setPadding(0, Ui.dp(this, 12), 0, 0);
        for (Object[] p : Theme.PRESETS) {
            final String name = (String) p[0];
            final int hue = (Integer) p[1];
            final int sat = (Integer) p[2];
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 32), Ui.dp(this, 32));
            lp.rightMargin = Ui.dp(this, 8);
            dot.setLayoutParams(lp);
            dot.setBackground(Ui.pill(Color.HSVToColor(new float[]{hue, sat / 100f,
                    Theme.isDark(this) ? 0.72f : 0.55f})));
            dot.setOnClickListener(v -> {
                tap(v);
                prefs.setAccent(hue, sat);
                hueBar.setProgress(hue);
                satBar.setProgress(sat);
                previewSwatch.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 12)));
                previewText.setText(hslText());
                toast("已切换到「" + name + "」");
            });
            presets.addView(dot);
        }
        colorCard.addView(presets);

        TextView note = Ui.text(this, "主题色只改强调色（按钮、选中态、今天）；"
                + "红/橙/黄/绿/灰这五个「紧急度」颜色是固定的，不会跟着变。",
                11.5f, R.color.text_dim);
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        colorCard.addView(note);
        root.addView(colorCard);
    }

    private String hslText() {
        return "hsl(" + prefs.hue() + ", " + prefs.sat() + "%, 45%)";
    }

    /** 紧急度五档配色方案 + 色卡预览。 */
    // ───────────────── 皮肤风格 ─────────────────

    /** 圆润 / 标准 / 方正：只改形状语言（圆角、描边、阴影），不改颜色。 */
    private void buildStylePack() {
        root.addView(section("外观 · 整体风格"));
        LinearLayout card = Widgets.card(this);
        LinearLayout row = Ui.row(this);
        styleChips = new java.util.ArrayList<>();
        for (final String id : Theme.STYLE_IDS) {
            TextView chip = Widgets.chip(this, Theme.styleName(id),
                    Theme.style(this).equals(id), Theme.accent(this));
            chip.setOnClickListener(v -> {
                tap(v);
                if (Theme.style(this).equals(id)) {
                    return;
                }
                prefs.setStyle(id);
                for (int i = 0; i < Theme.STYLE_IDS.length; i++) {
                    restyleChip(styleChips.get(i), Theme.STYLE_IDS[i].equals(id));
                }
                // 就地重建这一页，让圆角/描边立刻可见（不会跳回顶部）
                int keep = scrollView.getScrollY();
                root.removeAllViews();
                buildAppearance();
                buildPalette();
                buildStylePack();
                buildSound();
                buildMoney();
                buildData();
                buildAbout();
                root.post(() -> scrollView.scrollTo(0, keep));
            });
            styleChips.add(chip);
            row.addView(chip);
        }
        card.addView(row);
        TextView hint = Ui.text(this, Theme.styleHint(Theme.style(this)), 12f, R.color.text_dim);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        styleHintView = hint;
        card.addView(hint);
        // 预览：三张小卡片示意圆角差异
        LinearLayout preview = Ui.row(this);
        preview.setPadding(0, Ui.dp(this, 12), 0, 0);
        int[] radii = {Math.round(18 * d()), Math.round(12 * d()), Math.round(4 * d())};
        for (int i = 0; i < radii.length; i++) {
            View box = new View(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
            blp.rightMargin = i < 2 ? Ui.dp(this, 8) : 0;
            box.setLayoutParams(blp);
            box.setBackground(Ui.bgStroke(Theme.accentSoft(this), radii[i],
                    Theme.accent(this), d()));
            preview.addView(box);
        }
        card.addView(preview);
        root.addView(card);
    }

    private void restyleChip(TextView t, boolean active) {
        float d = d();
        float r = Theme.chipRadius(this);
        if (active) {
            t.setTextColor(0xFFFFFFFF);
            t.setBackground(r >= 999f ? Ui.pill(Theme.accent(this)) : Ui.bg(Theme.accent(this), r));
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else {
            t.setTextColor(Ui.color(this, R.color.text_dim));
            t.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), r,
                    Theme.cardStrokeColor(this), Theme.cardStroke(this)));
            t.setTypeface(android.graphics.Typeface.DEFAULT);
        }
    }

    private void buildPalette() {        root.addView(section("外观 · 紧急度配色"));
        LinearLayout card = Widgets.card(this);
        card.addView(Ui.text(this, "五档颜色（已逾期 / 今天 / 临近 / 以后 / 无期限）用来表达紧迫程度，"
                + "换一套配色不影响语义，只换观感。", 12.5f, R.color.text_dim));

        // 色卡预览
        final LinearLayout preview = Ui.row(this);
        preview.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(preview);

        final android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = Ui.row(this);
        row.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        hs.addView(row);
        card.addView(hs);

        final java.util.ArrayList<TextView> chips = new java.util.ArrayList<>();
        final TextView hint = Ui.text(this, Theme.paletteHint(prefs.urgencyPalette()),
                11.5f, R.color.text_dim);
        for (int i = 0; i < Theme.PALETTE_IDS.length; i++) {
            final int idx = i;
            final String id = Theme.PALETTE_IDS[i];
            TextView chip = Widgets.chip(this, Theme.paletteName(id),
                    id.equals(prefs.urgencyPalette()), Theme.accent(this));
            chips.add(chip);
            chip.setOnClickListener(v -> {
                tap(v);
                prefs.setUrgencyPalette(id);
                restyle(chips, idx);
                fillPalettePreview(preview);
                hint.setText(Theme.paletteHint(id));
            });
            row.addView(chip);
        }
        hint.setPadding(0, Ui.dp(this, 10), 0, 0);
        card.addView(hint);

        fillPalettePreview(preview);
        root.addView(card);
    }

    private void fillPalettePreview(LinearLayout box) {
        box.removeAllViews();
        for (int lv = 0; lv <= 4; lv++) {
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 46), Ui.dp(this, 26));
            lp.rightMargin = Ui.dp(this, 6);
            sw.setLayoutParams(lp);
            sw.setBackground(Ui.bg(Theme.urgency(this, lv), Ui.dp(this, 7)));
            box.addView(sw);
        }
        TextView lv = Ui.text(this, "逾期 → 无期限", 11.5f, R.color.text_dim);
        lv.setPadding(Ui.dp(this, 4), 0, 0, 0);
        box.addView(lv);
    }

    private TextView sliderLabel(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim);
        t.setPadding(0, Ui.dp(this, 12), 0, 0);
        return t;
    }

    /** 就地改胶囊样式，避免整页重建（重建会让滚动位置跳回顶部）。 */
    private void restyle(java.util.List<TextView> chips, int activeIndex) {
        for (int i = 0; i < chips.size(); i++) {
            boolean on = i == activeIndex;
            TextView t = chips.get(i);
            t.setTextColor(on ? 0xFFFFFFFF : Ui.color(this, R.color.text_dim));
            t.setBackground(on ? Ui.pill(Theme.accent(this))
                    : Ui.bgStroke(Ui.color(this, R.color.surface), 999f,
                    Ui.color(this, R.color.divider), d()));
            t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    // ───────────────── 音效 ─────────────────

    private void buildSound() {
        root.addView(section("音效"));
        LinearLayout card = Widgets.card(this);

        LinearLayout onRow = Ui.row(this);
        onRow.addView(Ui.text(this, "点按音效", 14f, R.color.text, true));
        onRow.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView toggle = Widgets.chip(this, prefs.soundOn() ? "已开启" : "已关闭",
                prefs.soundOn(), Theme.accent(this));
        toggle.setOnClickListener(v -> {
            prefs.setSoundOn(!prefs.soundOn());
            toggle.setText(prefs.soundOn() ? "已开启" : "已关闭");
            toggle.setBackground(prefs.soundOn()
                    ? Ui.pill(Theme.accent(this))
                    : Ui.bgStroke(Ui.color(this, R.color.surface), 999f,
                    Ui.color(this, R.color.divider), d()));
            toggle.setTextColor(prefs.soundOn() ? 0xFFFFFFFF : Ui.color(this, R.color.text_dim));
            if (prefs.soundOn()) {
                tap(v);
            }
        });
        onRow.addView(toggle);
        card.addView(onRow);

        final TextView volLabel = sliderLabel("音量  " + prefs.soundVol() + "%");
        card.addView(volLabel);
        SeekBar vol = new SeekBar(this);
        vol.setMax(100);
        vol.setProgress(prefs.soundVol());
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                prefs.setSoundVol(value);
                volLabel.setText("音量  " + value + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                Sfx.preview(SettingsActivity.this, prefs.soundVol(), prefs.soundType());
            }
        });
        card.addView(vol);

        card.addView(sliderLabel("音色"));
        LinearLayout types = Ui.row(this);
        int ti = 0;
        for (final String t : Sfx.TYPES) {
            final int idx = ti++;
            TextView chip = Widgets.chip(this, Sfx.typeName(t), t.equals(prefs.soundType()),
                    Theme.accent(this));
            soundTypeChips.add(chip);
            chip.setOnClickListener(v -> {
                prefs.setSoundType(t);
                restyle(soundTypeChips, idx);
                Sfx.preview(SettingsActivity.this, Math.max(1, prefs.soundVol()), t);
            });
            types.addView(chip);
        }
        card.addView(types);

        TextView tryIt = Ui.textColored(this, "试听一下", 13f, Theme.accent(this), true);
        tryIt.setPadding(0, Ui.dp(this, 10), 0, 0);
        tryIt.setOnClickListener(v ->
                Sfx.preview(this, Math.max(1, prefs.soundVol()), prefs.soundType()));
        card.addView(tryIt);
        root.addView(card);
    }

    // ───────────────── 记账默认值 ─────────────────

    private void buildMoney() {
        root.addView(section("记账"));
        LinearLayout card = Widgets.card(this);

        TextView bill = Ui.textColored(this, "获取支付宝 / 微信支出（账单导入 · 通知自动记账）",
                14f, Theme.accent(this), true);
        bill.setOnClickListener(v -> {
            tap(v);
            startActivity(new Intent(this, BillImportActivity.class));
        });
        card.addView(bill);

        // 通知栏快捷记账
        LinearLayout quickRow = Ui.row(this);
        quickRow.setPadding(0, Ui.dp(this, 14), 0, 0);
        LinearLayout quickTxt = Ui.column(this);
        quickTxt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        quickTxt.addView(Ui.text(this, "通知栏快捷记账", 14f, R.color.text, true));
        quickTxt.addView(Ui.text(this, "挂一条常驻通知，下拉点一下就记一笔，不用开应用",
                11.5f, R.color.text_dim));
        quickRow.addView(quickTxt);
        final TextView qToggle = Widgets.chip(this, prefs.quickNotify() ? "已开启" : "已关闭",
                prefs.quickNotify(), Theme.accent(this));
        qToggle.setOnClickListener(v -> {
            boolean on = !prefs.quickNotify();
            prefs.setQuickNotify(on);
            if (on) {
                tap(v);
                com.sudfry.todo.notify.QuickNotifier.show(this);
            } else {
                com.sudfry.todo.notify.QuickNotifier.hide(this);
            }
            qToggle.setText(on ? "已开启" : "已关闭");
            qToggle.setBackground(on ? Ui.pill(Theme.accent(this))
                    : Ui.bgStroke(Ui.color(this, R.color.surface), 999f,
                    Ui.color(this, R.color.divider), d()));
            qToggle.setTextColor(on ? 0xFFFFFFFF : Ui.color(this, R.color.text_dim));
        });
        quickRow.addView(qToggle);
        card.addView(quickRow);

        // 统计报表入口
        TextView stats = Ui.textColored(this, "统计报表（完成率 · 收支对比 · 习惯）",
                14f, Theme.accent(this), true);
        stats.setPadding(0, Ui.dp(this, 14), 0, 0);
        stats.setOnClickListener(v -> {
            tap(v);
            startActivity(new Intent(this, StatsActivity.class));
        });
        card.addView(stats);

        card.addView(Ui.text(this, "默认类型", 13.5f, R.color.text, true));
        LinearLayout types = Ui.row(this);
        types.setPadding(0, Ui.dp(this, 8), 0, 0);
        String[] names = {"支出", "收入"};
        for (int i = 0; i < 2; i++) {
            final int t = i;
            TextView chip = Widgets.chip(this, names[i], prefs.defaultType() == i,
                    Ui.color(this, i == 0 ? R.color.money_out : R.color.money_in));
            defTypeChips.add(chip);
            chip.setOnClickListener(v -> {
                tap(v);
                prefs.setDefaultType(t);
                // 支出/收入两色不同，单独按各自的语义色重画
                for (int k = 0; k < defTypeChips.size(); k++) {
                    boolean on = k == t;
                    TextView c = defTypeChips.get(k);
                    int color = Ui.color(this, k == 0 ? R.color.money_out : R.color.money_in);
                    c.setTextColor(on ? 0xFFFFFFFF : Ui.color(this, R.color.text_dim));
                    c.setBackground(on ? Ui.pill(color)
                            : Ui.bgStroke(Ui.color(this, R.color.surface), 999f,
                            Ui.color(this, R.color.divider), d()));
                    c.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
                }
            });
            types.addView(chip);
        }
        card.addView(types);

        card.addView(sliderLabel("默认支付方式"));
        LinearLayout plats = Ui.row(this);
        int pi = 0;
        for (final String p : Categories.PLATFORMS) {
            final int idx = pi++;
            TextView chip = Widgets.chip(this, p, p.equals(prefs.defaultPlatform()),
                    Theme.accent(this));
            defPlatChips.add(chip);
            chip.setOnClickListener(v -> {
                tap(v);
                prefs.setDefaultPlatform(p);
                restyle(defPlatChips, idx);
            });
            plats.addView(chip);
        }
        card.addView(plats);

        card.addView(sliderLabel("每日预算"));
        final EditText budget = new EditText(this);
        budget.setHint("0.00");
        budget.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        budget.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        if (prefs.dailyBudget() > 0) {
            budget.setText(String.valueOf(prefs.dailyBudget()));
        }
        card.addView(budget);

        card.addView(sliderLabel("月生活费（月度结算用）"));
        final EditText fee = new EditText(this);
        fee.setHint("0.00");
        fee.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fee.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        if (prefs.livingFee() > 0) {
            fee.setText(String.valueOf(prefs.livingFee()));
        }
        card.addView(fee);

        TextView save = Ui.textColored(this, "保存金额设置", 14f, Theme.onAccent(this), true);
        save.setGravity(Gravity.CENTER);
        save.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 12)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        slp.topMargin = Ui.dp(this, 14);
        save.setLayoutParams(slp);
        save.setOnClickListener(v -> {
            tap(v);
            double b = Money.parse(budget.getText().toString());
            double f = Money.parse(fee.getText().toString());
            prefs.setDailyBudget(b < 0 ? 0 : b);
            prefs.setLivingFee(f < 0 ? 0 : f);
            toast("已保存");
        });
        card.addView(save);
        root.addView(card);
    }

    // ───────────────── 数据 ─────────────────

    private void buildData() {
        root.addView(section("数据"));
        LinearLayout card = Widgets.card(this);
        dataCountLabel = Ui.text(this, countText(), 13f, R.color.text);
        card.addView(dataCountLabel);

        TextView sync = Ui.textColored(this, "数据与同步（文件 / 剪贴板 / 局域网直传）",
                14f, Theme.accent(this), true);
        sync.setPadding(0, Ui.dp(this, 12), 0, 0);
        sync.setOnClickListener(v -> {
            tap(v);
            startActivity(new Intent(this, SyncActivity.class));
        });
        card.addView(sync);

        TextView clearDone = Ui.text(this, "清除已完成的任务", 14f, R.color.overdue);
        clearDone.setPadding(0, Ui.dp(this, 12), 0, 0);
        clearDone.setOnClickListener(v -> {
            tap(v);
            Sheet.confirm(this, "清除已完成的任务",
                    "删除全部标记为完成的任务，不可恢复。", null, "清除", true, () -> {
                        int n = TodoStore.get(this).clearCompleted();
                        toast("已清除 " + n + " 项");
                        refreshDataCount();
                    });
        });
        card.addView(clearDone);

        TextView wipe = Ui.text(this, "清空全部数据", 14f, R.color.overdue);
        wipe.setPadding(0, Ui.dp(this, 12), 0, 0);
        wipe.setOnClickListener(v -> {
            tap(v);
            Sheet.confirm(this, "清空全部数据？",
                    "任务和账目都会被删除，且无法恢复。建议先到「数据与同步」导出一份备份。",
                    null, "确定清空", true, () -> {
                        TodoStore.get(this).clearAll();
                        EntryStore.get(this).clearAll();
                        toast("已清空");
                        refreshDataCount();
                    });
        });
        card.addView(wipe);
        root.addView(card);
    }

    private String countText() {
        return "任务 " + TodoStore.get(this).count()
                + " 条 · 账目 " + EntryStore.get(this).count() + " 笔";
    }

    /** 就地更新统计，不重建整页。 */
    private void refreshDataCount() {
        if (dataCountLabel != null) {
            dataCountLabel.setText(countText());
        }
    }

    private void buildAbout() {
        root.addView(section("关于"));
        LinearLayout card = Widgets.card(this);
        card.addView(Ui.text(this, "SudfryTodo  v1.5.1", 14f, R.color.text, true));
        TextView whatsNew = Ui.text(this,
                "v1.5.1：账单导入支持微信新版导出（.xlsx），并支持带密码的压缩包（直接填密码导入）。\n"
                        + "v1.5：应用更名为 SudfryTodo；iOS 网页版同步升到 v1.5。\n"
                        + "v1.4：使用教程、作者与 GitHub 入口、本地备份与恢复（可自动备份 + 间隔可调）。\n"
                        + "v1.3.2：全局统一按键反馈（按下回弹）、页面细节优化；"
                        + "实时记账（支付宝/微信通知自动入账）加了状态自检与一键开启。\n"
                        + "v1.3.1：修好删除撤销条的位置、账目删除也能撤销。\n"
                        + "v1.3：标题里直接写「明天下午3点开会 重要」自动识别时间与优先级、"
                        + "长按色块拖动排期、三套整体风格（圆润/标准/方正）、"
                        + "搜索覆盖账目并记住搜索历史、4×2 数据看板小组件。",
                12f, R.color.accent);
        whatsNew.setLineSpacing(Ui.dp(this, 4), 1f);
        whatsNew.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(whatsNew);
        TextView note = Ui.text(this,
                "待办部分参考主流的「日历 + 时间轴 + 紧急度」做法；"
                        + "记账部分的功能与分类体系对齐 ribook·日簿（支出/收入、二级分类、"
                        + "每日预算、月度结算、分类分析）。\n\n"
                        + "数据全部存在本机，不注册账号、不上传服务器；"
                        + "局域网直传只在同一个 Wi-Fi 内点对点传输。",
                12f, R.color.text_dim);
        note.setLineSpacing(Ui.dp(this, 4), 1f);
        note.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(note);
        root.addView(card);

        // ── 作者 ──
        LinearLayout authorCard = Widgets.card(this);
        LinearLayout aRow = Ui.row(this);
        LinearLayout aTxt = Ui.column(this);
        aTxt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        aTxt.addView(Ui.text(this, "作者：Sudfry", 14.5f, R.color.text, true));
        aTxt.addView(Ui.text(this, "日簿 / Ri-Book 的作者", 11.5f, R.color.text_dim));
        aRow.addView(aTxt);
        authorCard.addView(aRow);

        TextView url = Ui.text(this, GITHUB_URL, 11.5f, R.color.text_dim);
        url.setPadding(0, Ui.dp(this, 8), 0, 0);
        authorCard.addView(url);

        TextView gitBtn = Ui.textColored(this, "打开作者 GitHub 仓库", 14.5f,
                Theme.onAccent(this), true);
        gitBtn.setGravity(android.view.Gravity.CENTER);
        gitBtn.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 14)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        glp.topMargin = Ui.dp(this, 12);
        gitBtn.setLayoutParams(glp);
        gitBtn.setOnClickListener(v -> {
            tap(v);
            openRepo();
        });
        authorCard.addView(gitBtn);
        root.addView(authorCard);
    }

    /** 打开作者仓库；没有浏览器就把链接复制到剪贴板，别让人干瞪眼。 */
    private void openRepo() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            try {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("repo", GITHUB_URL));
                }
            } catch (Throwable ignored) {
            }
            toast("没有可用的浏览器，链接已复制到剪贴板");
        }
    }
}
