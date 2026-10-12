package com.sudfry.todo;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sudfry.todo.ui.Sheet;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.AppPrefs;
import com.sudfry.todo.util.Backup;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 备份与恢复。
 *
 * 备份存在应用私有目录 backups/，导出走系统的「另存为」，导入走系统的「打开文件」——
 * 全程不需要存储权限，换手机时把导出的 JSON 拷过去再导入即可。
 */
public class BackupActivity extends Activity {

    private static final int REQ_SAVE = 7301;
    private static final int REQ_OPEN = 7302;

    private AppPrefs prefs;
    private LinearLayout root;
    private LinearLayout listBox;
    private ScrollView sv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = AppPrefs.get(this);
        setContentView(buildUi());
        Ui.pressAll(getWindow().getDecorView());
        renderList();
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    private void tap(View v) {
        Sfx.tap(v);
    }

    // ───────────────── 界面 ─────────────────

    private View buildUi() {
        LinearLayout col = Ui.column(this);
        col.setBackgroundColor(Ui.color(this, R.color.bg));

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
        TextView t = Ui.text(this, "备份与恢复", 16f, R.color.text, true);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);
        bar.addView(Ui.text(this, "     ", 15f, R.color.accent));
        col.addView(bar);
        col.addView(Widgets.divider(this));

        sv = new ScrollView(this);
        sv.setFillViewport(true);
        root = Ui.column(this);
        Ui.padDp(root, this, 14, 10, 14, 40);
        sv.addView(root);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildAuto();
        buildActions();
        buildList();
        return col;
    }

    private void section(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim, true);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        root.addView(t);
    }

    private void buildAuto() {
        section("自动备份");
        LinearLayout card = Widgets.card(this);

        LinearLayout row = Ui.row(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView rowLabel = Ui.text(this, "开启自动备份", 14.5f, R.color.text, true);
        rowLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(rowLabel);
        final TextView toggle = Widgets.chip(this, prefs.autoBackup() ? "已开启" : "已关闭",
                prefs.autoBackup(), Theme.accent(this));
        toggle.setOnClickListener(v -> {
            tap(v);
            boolean on = !prefs.autoBackup();
            prefs.setAutoBackup(on);
            toggle.setText(on ? "已开启" : "已关闭");
            styleChip(toggle, on);
            if (on) {
                File f = Backup.create(this, true);
                toast(f == null ? "已开启，首次备份失败" : "已开启，已备份一份");
                renderList();
            }
        });
        row.addView(toggle);
        // 显式定高：卡片是竖向容器，行必须 wrap_content，否则可能被量成整屏高
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);

        LinearLayout ir = Ui.row(this);
        ir.setPadding(0, Ui.dp(this, 14), 0, 0);
        TextView irLabel = Ui.text(this, "备份间隔", 14f, R.color.text, true);
        irLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ir.addView(irLabel);
        ir.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(ir);

        LinearLayout chips = Ui.row(this);
        chips.setPadding(0, Ui.dp(this, 8), 0, 0);
        final int[] hours = Backup.intervalHours();
        final String[] names = Backup.intervalNames();
        for (int i = 0; i < hours.length; i++) {
            final int h = hours[i];
            TextView c = Widgets.chip(this, names[i], prefs.autoBackupHours() == h,
                    Theme.accent(this));
            c.setOnClickListener(v -> {
                tap(v);
                prefs.setAutoBackupHours(h);
                // 只重画这一行，别把整页重建了（免得跳回顶部）
                for (int k = 0; k < chips.getChildCount(); k++) {
                    View child = chips.getChildAt(k);
                    if (child instanceof TextView) {
                        styleChip((TextView) child, hours[k] == h);
                    }
                }
                TextView last = (TextView) chips.getTag();
                if (last != null) {
                    last.setText(lastText());
                }
            });
            chips.addView(c);
        }
        chips.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(chips);

        TextView last = Ui.text(this, lastText(), 12f, R.color.text_dim);
        last.setPadding(0, Ui.dp(this, 12), 0, 0);
        chips.setTag(last);
        card.addView(last);
        root.addView(card);
    }

    private String lastText() {
        long last = prefs.lastBackupAt();
        if (last == 0) {
            return "还没有备份过";
        }
        String next = prefs.autoBackup()
                ? "　下一次：" + Fmt.dateFull(last + Backup.intervalMs(this))
                : "";
        return "上次备份：" + Fmt.dateFull(last) + next;
    }

    private void styleChip(TextView t, boolean on) {
        float r = Theme.chipRadius(this);
        if (on) {
            t.setTextColor(0xFFFFFFFF);
            t.setBackground(r >= 999f ? Ui.pill(Theme.accent(this))
                    : Ui.bg(Theme.accent(this), r));
            t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        } else {
            t.setTextColor(Ui.color(this, R.color.text_dim));
            t.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), r,
                    Theme.cardStrokeColor(this), Theme.cardStroke(this)));
            t.setTypeface(android.graphics.Typeface.DEFAULT);
        }
    }

    private void buildActions() {
        section("手动操作");
        LinearLayout card = Widgets.card(this);

        TextView now = Ui.textColored(this, "立即备份一份", 14.5f, Theme.accent(this), true);
        now.setOnClickListener(v -> {
            tap(v);
            File f = Backup.create(this, false);
            toast(f == null ? "备份失败" : "已备份：" + f.getName());
            renderList();
        });
        card.addView(now);

        TextView exp = Ui.textColored(this, "导出备份文件（另存为）", 14.5f, Theme.accent(this), true);
        exp.setPadding(0, Ui.dp(this, 14), 0, 0);
        exp.setOnClickListener(v -> {
            tap(v);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "日簿备份-"
                    + Fmt.dateShort(System.currentTimeMillis()) + ".json");
            try {
                startActivityForResult(i, REQ_SAVE);
            } catch (Exception e) {
                toast("这台设备没有文件保存器，可先用「立即备份」");
            }
        });
        card.addView(exp);

        TextView imp = Ui.textColored(this, "从文件导入（支持旧版本数据）", 14.5f,
                Theme.accent(this), true);
        imp.setPadding(0, Ui.dp(this, 14), 0, 0);
        imp.setOnClickListener(v -> {
            tap(v);
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_OPEN);
            } catch (Exception e) {
                toast("这台设备没有文件选择器");
            }
        });
        card.addView(imp);

        TextView hint = Ui.text(this,
                "导入默认是「合并」：只增加和更新，不会删掉你现在的记录；"
                        + "恢复前会自动先备份一次当前数据。备份文件是纯 JSON，可以自己打开看。",
                11.5f, R.color.text_dim);
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        hint.setPadding(0, Ui.dp(this, 12), 0, 0);
        card.addView(hint);
        root.addView(card);
    }

    private void buildList() {
        section("本机备份");
        listBox = Ui.column(this);
        root.addView(listBox);
    }

    private void renderList() {
        if (listBox == null) {
            return;
        }
        listBox.removeAllViews();
        List<Backup.Meta> all = Backup.list(this);
        if (all.isEmpty()) {
            listBox.addView(Widgets.empty(this, "还没有备份。点上面的「立即备份一份」试试。"));
            return;
        }
        for (final Backup.Meta m : all) {
            LinearLayout card = Widgets.card(this);
            LinearLayout row = Ui.row(this);
            LinearLayout txt = Ui.column(this);
            txt.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            txt.addView(Ui.text(this, (m.auto ? "自动 · " : "手动 · ") + Fmt.dateFull(m.at),
                    14f, R.color.text, true));
            txt.addView(Ui.text(this, m.todos + " 条待办 / " + m.entries + " 条账目 · "
                    + m.sizeText(), 11.5f, R.color.text_dim));
            row.addView(txt);
            card.addView(row);

            LinearLayout btns = Ui.row(this);
            btns.setPadding(0, Ui.dp(this, 10), 0, 0);
            btns.addView(smallButton("恢复（合并）", Theme.accent(this), v -> {
                tap(v);
                restoreFrom(m, false);
            }));
            btns.addView(smallButton("覆盖恢复", Ui.color(this, R.color.overdue), v -> {
                tap(v);
                Sheet.confirm(this, "覆盖恢复？",
                        "会先清空当前的待办和账目，再导入这份备份。恢复前会自动备份当前数据，"
                                + "万一弄错了还能从列表里退回来。",
                        m.describe(), "覆盖恢复", true, () -> restoreFrom(m, true));
            }));
            btns.addView(smallButton("删除", Ui.color(this, R.color.text_dim), v -> {
                tap(v);
                Sheet.confirm(this, "删除这份备份？", "文件会从本机移除。",
                        m.describe(), "删除", true, () -> {
                            Backup.delete(m.file);
                            renderList();
                        });
            }));
            card.addView(btns);
            listBox.addView(card);
        }
        root.post(() -> Ui.pressAll(listBox));
    }

    private TextView smallButton(String label, int color, View.OnClickListener l) {
        TextView t = Ui.textColored(this, label, 12.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), Ui.dp(this, 12),
                Theme.cardStrokeColor(this), Theme.cardStroke(this)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38));
        lp.rightMargin = Ui.dp(this, 8);
        t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    // ───────────────── 动作 ─────────────────

    private void restoreFrom(Backup.Meta m, boolean replace) {
        try {
            JSONObject parsed = Backup.parse(Backup.read(m.file));
            Backup.Result r = Backup.restore(this, parsed, replace);
            toast(r.describe());
            renderList();
        } catch (Throwable t) {
            toast("这份备份读不出来：" + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (req == REQ_SAVE) {
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    toast("写不进去");
                    return;
                }
                os.write(Backup.snapshot(this).toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                toast("已导出，换手机时导入这个文件即可");
            } catch (Throwable t) {
                toast("导出失败：" + t.getMessage());
            }
        } else if (req == REQ_OPEN) {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    toast("读不出来");
                    return;
                }
                String text = Backup.read(in);
                final JSONObject parsed = Backup.parse(text);
                boolean legacy = parsed.optBoolean("legacy");
                int nt = parsed.getJSONArray("todos").length();
                int ne = parsed.getJSONArray("entries").length();
                Sheet.confirm(this, legacy ? "导入旧版本数据？" : "导入这份备份？",
                        (legacy ? "识别到旧版格式（" : "识别到备份，共（")
                                + nt + " 条待办、" + ne + " 条账目）。"
                                + "导入是合并：只增加和更新，不会删掉现有记录。",
                        null, "导入", false, () -> {
                            try {
                                Backup.Result r = Backup.restore(this, parsed, false);
                                toast(r.describe());
                                renderList();
                            } catch (Throwable t) {
                                toast("导入失败：" + t.getMessage());
                            }
                        });
            } catch (Throwable t) {
                toast("这个文件不是日簿的备份：" + t.getMessage());
            }
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }
}
