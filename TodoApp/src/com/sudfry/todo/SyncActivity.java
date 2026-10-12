package com.sudfry.todo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
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

import com.sudfry.todo.reminder.Reminders;
import com.sudfry.todo.store.TodoStore;
import com.sudfry.todo.sync.SyncEngine;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.Ui;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** 数据与同步：文件导出导入、剪贴板 / 分享、同一 Wi-Fi 下直传。 */
public class SyncActivity extends Activity implements SyncEngine.Listener {

    private static final int REQ_EXPORT = 1001;
    private static final int REQ_IMPORT = 1002;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private TodoStore store;
    private SyncEngine engine;
    private String pairCode = "";

    private TextView countLabel;
    private TextView statusLabel;
    private LinearLayout serverBox;
    private TextView serverInfo;
    private LinearLayout deviceBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = TodoStore.get(this);
        engine = new SyncEngine(this, this);
        setContentView(buildUi());
        refreshCount();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.stopServer();
    }

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

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
        TextView title = Ui.text(this, "数据与同步", 16f, R.color.text, true);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        bar.addView(Ui.text(this, "     ", 15f, R.color.accent));
        root.addView(bar);
        root.addView(Widgets.divider(this));

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout col = Ui.column(this);
        Ui.padDp(col, this, 14, 12, 14, 24);
        sv.addView(col);

        countLabel = Ui.text(this, "", 13f, R.color.text_dim);
        col.addView(countLabel);

        // ── 用文件传 ─────────────────────────────
        col.addView(sectionTitle("用文件传（U 盘 / 网盘 / 微信文件助手都行）"));
        LinearLayout fileCard = Widgets.card(this);
        fileCard.addView(note("导出得到一个 .json 文件；在另一台设备上「从文件导入」即可，"
                + "同名任务会自动合并，不会重复。"));
        LinearLayout fileRow = Ui.row(this);
        fileRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        fileRow.addView(primaryButton("导出到文件", v -> doExport()));
        fileRow.addView(space(8));
        fileRow.addView(secondButton("从文件导入", v -> doImportPick()));
        fileCard.addView(fileRow);
        col.addView(fileCard);

        // ── 用文本传 ─────────────────────────────
        col.addView(sectionTitle("用文本传（微信 / QQ / 备忘录）"));
        LinearLayout textCard = Widgets.card(this);
        textCard.addView(note("把全部数据变成一段文本，复制或分享出去；对方粘贴回来即可导入。"));
        LinearLayout textRow = Ui.row(this);
        textRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        textRow.addView(secondButton("复制全部数据", v -> copyAll()));
        textRow.addView(space(8));
        textRow.addView(secondButton("从剪贴板导入", v -> importFromClipboard()));
        textCard.addView(textRow);
        LinearLayout shareRow = Ui.row(this);
        shareRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        shareRow.addView(secondButton("分享给其他应用", v -> shareAll()));
        textCard.addView(shareRow);
        col.addView(textCard);

        // ── 同一 Wi-Fi 直传 ──────────────────────
        col.addView(sectionTitle("同一 Wi-Fi 下直传（不用流量、不经过服务器）"));
        LinearLayout lanCard = Widgets.card(this);
        lanCard.addView(note("两台设备连同一个 Wi-Fi：一台点「等待对方连接」并把这边的配对码念给对面，"
                + "另一台点「搜索设备」选中后输入配对码，一次连接双向同步。"));
        LinearLayout lanRow = Ui.row(this);
        lanRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        lanRow.addView(primaryButton("等待对方连接", v -> startServer()));
        lanRow.addView(space(8));
        lanRow.addView(secondButton("搜索设备", v -> engine.discover()));
        lanCard.addView(lanRow);

        serverBox = Ui.column(this);
        serverBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = Ui.dp(this, 10);
        serverBox.setLayoutParams(sblp);
        serverBox.setBackground(Ui.bgStroke(Ui.color(this, R.color.accent_soft), 12 * d(),
                Ui.color(this, R.color.accent), d()));
        Ui.padDp(serverBox, this, 12, 10, 12, 10);
        serverInfo = Ui.text(this, "", 14f, R.color.text, true);
        serverBox.addView(serverInfo);
        TextView stop = Ui.text(this, "停止等待", 13f, R.color.overdue);
        stop.setPadding(0, Ui.dp(this, 8), 0, 0);
        stop.setOnClickListener(v -> {
            engine.stopServer();
            serverBox.setVisibility(View.GONE);
            status("已停止等待");
        });
        serverBox.addView(stop);
        lanCard.addView(serverBox);

        deviceBox = Ui.column(this);
        lanCard.addView(deviceBox);

        LinearLayout manualRow = Ui.row(this);
        manualRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        manualRow.addView(secondButton("手动输入地址", v -> manualConnect()));
        lanCard.addView(manualRow);
        col.addView(lanCard);

        statusLabel = Ui.text(this, "", 12.5f, R.color.text_dim);
        statusLabel.setPadding(0, Ui.dp(this, 14), 0, 0);
        col.addView(statusLabel);

        root.addView(sv);
        return root;
    }

    private View sectionTitle(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim, true);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return t;
    }

    private View note(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        return t;
    }

    private View space(int dp) {
        return Ui.space(this, Ui.dp(this, dp), 1);
    }

    private TextView primaryButton(String text, View.OnClickListener l) {
        TextView t = Ui.text(this, text, 14f, R.color.on_color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.bg(Ui.color(this, R.color.accent), Ui.dp(this, 12)));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        t.setOnClickListener(l);
        return t;
    }

    private TextView secondButton(String text, View.OnClickListener l) {
        TextView t = Ui.text(this, text, 14f, R.color.accent, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), Ui.dp(this, 12),
                Ui.color(this, R.color.accent), d()));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        t.setOnClickListener(l);
        return t;
    }

    private void refreshCount() {
        countLabel.setText("本机现有 " + store.count() + " 条任务、"
                + com.sudfry.todo.store.EntryStore.get(this).count() + " 笔账目");
    }

    private void status(String s) {
        statusLabel.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ───────────────── 文件 ─────────────────

    private void doExport() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "待办备份-" + stamp + ".json");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast("这台设备没有文件选择器");
        }
    }

    private void doImportPick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            toast("这台设备没有文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (req == REQ_EXPORT) {
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                byte[] out = com.sudfry.todo.store.Backup
                        .export(this, SyncEngine.deviceName()).getBytes(UTF8);
                os.write(out);
                os.flush();
                status("已导出 " + store.count() + " 条任务、"
                        + com.sudfry.todo.store.EntryStore.get(this).count()
                        + " 笔账目（" + out.length + " 字节）");
                toast("导出成功");
            } catch (Exception e) {
                status("导出失败：" + e.getMessage());
            }
        } else if (req == REQ_IMPORT) {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[65536];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = is.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, UTF8));
                }
                askMergeMode(sb.toString(), "文件");
            } catch (Exception e) {
                status("读取文件失败：" + e.getMessage());
            }
        }
    }

    private void askMergeMode(final String json, String from) {
        new AlertDialog.Builder(this)
                .setTitle("导入方式")
                .setMessage("读到了" + from + "数据。\n\n"
                        + "合并：保留两边，同一条任务按最新修改时间取新的。\n"
                        + "覆盖：先清空本机，再放入对方数据。")
                .setNegativeButton("取消", null)
                .setNeutralButton("覆盖本机", (dlg, w) -> doMerge(json, true))
                .setPositiveButton("合并", (dlg, w) -> doMerge(json, false))
                .show();
    }

    private void doMerge(String json, boolean replace) {
        try {
            com.sudfry.todo.store.Backup.Result r =
                    com.sudfry.todo.store.Backup.importJson(this, json, replace);
            Reminders.syncAll(this);
            refreshCount();
            status("导入完成：" + r.detail().replace("\n", "；"));
            toast("导入完成");
        } catch (Exception e) {
            status("导入失败：" + e.getMessage());
            toast("导入失败，可能不是本应用导出的数据");
        }
    }

    // ───────────────── 文本 ─────────────────

    private void copyAll() {
        String json = com.sudfry.todo.store.Backup.export(this, SyncEngine.deviceName());
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("待办数据", json));
            status("已复制 " + store.count() + " 条任务、"
                    + com.sudfry.todo.store.EntryStore.get(this).count()
                    + " 笔账目（" + json.length() + " 字符）");
            toast("已复制，粘贴到微信/备忘录即可");
        }
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            toast("剪贴板里没有内容");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (cs == null || cs.length() == 0) {
            toast("剪贴板里没有内容");
            return;
        }
        askMergeMode(cs.toString(), "剪贴板里的");
    }

    private void shareAll() {
        String json = com.sudfry.todo.store.Backup.export(this, SyncEngine.deviceName());
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "待办数据备份");
        i.putExtra(Intent.EXTRA_TEXT, json);
        try {
            startActivity(Intent.createChooser(i, "分享待办数据"));
        } catch (Exception e) {
            toast("没有可分享的应用");
        }
    }

    // ───────────────── 局域网 ─────────────────

    private void startServer() {
        if (pairCode.isEmpty()) {
            pairCode = String.format(Locale.US, "%04d", (int) (Math.random() * 10000));
        }
        engine.startServer(pairCode);
    }

    private void manualConnect() {
        final EditText ip = new EditText(this);
        ip.setHint("对方地址，如 192.168.1.7");
        ip.setInputType(InputType.TYPE_CLASS_TEXT);
        final EditText code = new EditText(this);
        code.setHint("4 位配对码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = Ui.column(this);
        Ui.padDp(box, this, 20, 6, 20, 0);
        box.addView(ip);
        box.addView(code);
        new AlertDialog.Builder(this)
                .setTitle("手动连接")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("连接", (dlg, w) -> {
                    String host = ip.getText().toString().trim();
                    if (host.isEmpty()) {
                        toast("请先填对方地址");
                        return;
                    }
                    engine.syncWith(host, SyncEngine.TCP_PORT,
                            code.getText().toString().trim());
                })
                .show();
    }

    private void askCodeAndSync(final SyncEngine.Device dev) {
        final EditText code = new EditText(this);
        code.setHint("对方屏幕上显示的 4 位配对码");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout box = Ui.column(this);
        Ui.padDp(box, this, 20, 6, 20, 0);
        box.addView(code);
        new AlertDialog.Builder(this)
                .setTitle("连接 " + dev.name)
                .setMessage(dev.ip + ":" + dev.port)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("同步", (dlg, w) ->
                        engine.syncWith(dev.ip, dev.port, code.getText().toString().trim()))
                .show();
    }

    // ───────────────── SyncEngine.Listener ─────────────────

    @Override
    public void onStatus(String msg) {
        status(msg);
    }

    @Override
    public void onServerReady(String ip, int port, String code) {
        serverBox.setVisibility(View.VISIBLE);
        serverInfo.setText("本机地址：" + ip + ":" + port + "\n配对码：" + code
                + "\n在一台设备上点「搜索设备」选中本机，输入这个配对码即可。");
    }

    @Override
    public void onDevices(ArrayList<SyncEngine.Device> devices) {
        deviceBox.removeAllViews();
        if (devices.isEmpty()) {
            status("没搜到设备。确认两台设备连的是同一个 Wi-Fi、"
                    + "对方已点「等待对方连接」，也可以「手动输入地址」。");
            return;
        }
        status("搜到 " + devices.size() + " 台设备，点一下开始同步");
        for (final SyncEngine.Device dv : devices) {
            TextView t = Ui.text(this, "📱 " + dv.name + "   " + dv.ip + ":" + dv.port,
                    14f, R.color.text, true);
            t.setBackground(Ui.bgStroke(Ui.color(this, R.color.surface), 12 * d(),
                    Ui.color(this, R.color.divider), d()));
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 11), Ui.dp(this, 12), Ui.dp(this, 11));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> askCodeAndSync(dv));
            deviceBox.addView(t);
        }
    }

    @Override
    public void onDone(String summary, boolean isServer) {
        Reminders.syncAll(this);
        refreshCount();
        status(summary);
        toast("同步完成");
    }

    @Override
    public void onError(String msg) {
        status(msg);
        toast(msg);
    }
}
