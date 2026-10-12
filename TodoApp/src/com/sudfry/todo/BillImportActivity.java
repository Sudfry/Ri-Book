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
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.sudfry.todo.billing.BillImporter;
import com.sudfry.todo.billing.PendingPays;
import com.sudfry.todo.model.Entry;
import com.sudfry.todo.notify.PayNotifyService;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.AppPrefs;
import com.sudfry.todo.util.Fmt;
import com.sudfry.todo.util.Money;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 账单导入：支付宝 / 微信官方账单，以及来自支付通知的自动记账。 */
public class BillImportActivity extends Activity {

    private static final int REQ_PICK = 2001;

    private LinearLayout root;
    private TextView statusLabel;
    private LinearLayout previewBox;
    private BillImporter.Preview preview;
    private String lastBatch;
    private int lastBatchCount;
    private LinearLayout undoneBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        // 全树统一挂按压反馈，避免有的按钮有、有的没有
        Ui.pressAll(getWindow().getDecorView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚从系统设置里开完通知权限回来
        refreshNotifyCard();
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

    // ───────────────────── 界面 ─────────────────────

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
        TextView title = Ui.text(this, "获取支付宝 / 微信支出", 16f, R.color.text, true);
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
        root = Ui.column(this);
        Ui.padDp(root, this, 14, 6, 14, 40);
        sv.addView(root);

        buildExplain();
        buildNotify();
        buildImport();
        buildPending();

        statusLabel = Ui.text(this, "", 12.5f, R.color.text_dim);
        statusLabel.setPadding(0, Ui.dp(this, 14), 0, 0);
        root.addView(statusLabel);

        col.addView(sv);
        return col;
    }

    private View section(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim, true);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return t;
    }

    private View note(String s) {
        TextView t = Ui.text(this, s, 12.5f, R.color.text_dim);
        t.setLineSpacing(Ui.dp(this, 4), 1f);
        return t;
    }

    private TextView button(String text, boolean primary, View.OnClickListener l) {
        TextView t = primary
                ? Ui.textColored(this, text, 14f, Theme.onAccent(this), true)
                : Ui.textColored(this, text, 14f, Theme.accent(this), true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(primary
                ? Ui.bg(Theme.accent(this), Ui.dp(this, 12))
                : Ui.bgStroke(Ui.color(this, R.color.surface), Ui.dp(this, 12),
                Theme.accent(this), d()));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 11));
        t.setOnClickListener(l);
        return t;
    }

    private void buildExplain() {
        root.addView(section("能拿到什么、拿不到什么"));
        LinearLayout card = Widgets.card(this);
        card.addView(note(
                "支付宝和微信的账单数据存在各自的应用私有目录里（微信还是加密库），"
                        + "第三方应用读不到，除非 root 并破解加密 —— 这条路不做。\n\n"
                        + "所以这里提供两条正规通道：\n"
                        + "① 官方账单导入：拿历史。两家都提供账单导出，导出的 CSV 可以完整导入，"
                        + "含交易单号，重复导入也不会重复记账。\n"
                        + "② 通知自动记账：记以后。开启通知使用权后，"
                        + "支付宝/微信弹出的支付通知会被解析成待确认账目。"));
        root.addView(card);
    }

    // ── 通知自动记账 ──

    private LinearLayout notifyCard;

    private void buildNotify() {
        root.addView(section("① 通知自动记账（记以后）"));
        notifyCard = Widgets.card(this);
        root.addView(notifyCard);
        refreshNotifyCard();
    }

    private void refreshNotifyCard() {
        if (notifyCard == null) {
            return;
        }
        notifyCard.removeAllViews();
        boolean on = PayNotifyService.isEnabled(this);
        boolean linked = PayNotifyService.isConnected();
        long lastAt = AppPrefs.get(this).lastCaptureAt();
        String lastTxt = AppPrefs.get(this).lastCaptureText();

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "通知使用权", 14f, R.color.text, true));
        head.addView(new View(this), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.text(this, on ? "已开启" : "未开启", 13f,
                on ? R.color.money_in : R.color.text_dim, true));
        notifyCard.addView(head);

        notifyCard.addView(note(on
                ? "正在监听支付宝 / 微信的支付通知，识别到的记录会先进入「待确认」，"
                + "确认后才写进账本 —— 自动记账也要对得上账。"
                : "开启后，收到「微信支付 向 XX 付款 ¥12.50」这类通知会自动记下来。"
                + "只读通知文本，不含聊天内容；随时可以在系统设置里关掉。"));

        // 运行状态：让「有没有在工作」看得见，而不是靠等一条真通知
        LinearLayout stat = Ui.row(this);
        stat.setPadding(0, Ui.dp(this, 10), 0, 0);
        stat.addView(statusDot(on ? (linked ? 2 : 1) : 0));
        stat.addView(Ui.text(this, on
                        ? (linked ? "服务已连接，正在实时接收" : "已授权，服务等待连接（收到通知时会自动连上）")
                        : "未授权，不会收到任何通知",
                12.5f, R.color.text_dim));
        notifyCard.addView(stat);

        LinearLayout cap = Ui.row(this);
        cap.setPadding(0, Ui.dp(this, 6), 0, 0);
        cap.addView(Ui.text(this, "最近捕获：", 12.5f, R.color.text_dim));
        cap.addView(Ui.text(this, lastAt == 0 ? "还没有捕获到支付通知"
                        : Fmt.dateFull(lastAt) + "  " + lastTxt,
                12.5f, lastAt == 0 ? R.color.text_dim : R.color.text));
        notifyCard.addView(cap);

        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);
        row.addView(button(on ? "去系统设置里管理" : "去开启通知使用权", true, v -> {
            tap(v);
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                toast("打不开系统设置，请手动到「设置 → 通知 → 通知使用权」里开启");
            }
        }));
        row.addView(button("自检解析", false, v -> {
            tap(v);
            showSelfTest();
        }));
        notifyCard.addView(row);
    }

    private View statusDot(int level) {
        View dot = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 8), Ui.dp(this, 8));
        lp.rightMargin = Ui.dp(this, 8);
        dot.setLayoutParams(lp);
        int color = level == 2 ? Ui.color(this, R.color.money_in)
                : level == 1 ? Ui.color(this, R.color.soon)
                : Ui.color(this, R.color.text_dim);
        dot.setBackground(Ui.pill(color));
        return dot;
    }

    /** 用几条真实样式的通知跑一遍解析，把结果摊给用户看。 */
    private void showSelfTest() {
        StringBuilder sb = new StringBuilder();
        java.util.List<String[]> r = PayNotifyService.selfTest();
        for (String[] x : r) {
            sb.append("【").append(x[0]).append("】").append(x[2]).append('\n')
                    .append("　").append(x[1]).append("\n\n");
        }
        com.sudfry.todo.ui.Sheet.confirm(this, "解析自检",
                sb.toString().trim(), null, "知道了", false, null);
    }

    // ── 账单导入 ──

    private void buildImport() {
        root.addView(section("② 导入官方账单（拿历史）"));
        LinearLayout card = Widgets.card(this);
        card.addView(note(
                "微信：我 → 服务 → 钱包 → 账单 → 右上角常见问题 → 下载账单 → 用于个人对账。\n"
                        + "微信给的是带密码的压缩包（老版是加密 zip、新版直接是 .xlsx）；"
                        + "加密 zip 的密码在「微信支付」公众号的那条消息里 —— 选完文件填一下就行，"
                        + "不用自己先解压。\n\n"
                        + "支付宝：我的 → 账单 → 右上角 ··· → 开具交易流水证明 → 用于个人对账。\n"
                        + "支付宝给的一般是 zip（无密码）或 .csv，选中即可，里面是什么格式都能自动认。\n\n"
                        + "支持 CSV / Excel(.xlsx) / 压缩包（含加密 zip）。"
                        + "编码和列顺序都不一样，这里会自动识别（支付宝 GBK、微信 UTF-8）。"
                        + "退款、已关闭、不计收支的记录默认跳过。"));

        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 12), 0, 0);
        row.addView(button("选择账单文件", true, v -> {
            tap(v);
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            try {
                startActivityForResult(i, REQ_PICK);
            } catch (Exception e) {
                toast("这台设备没有文件选择器");
            }
        }));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                Ui.dp(this, 8), 1);
        View space = new View(this);
        space.setLayoutParams(sp);
        row.addView(space);
        row.addView(button("从剪贴板粘贴", false, v -> {
            tap(v);
            pasteFromClipboard();
        }));
        card.addView(row);
        root.addView(card);

        previewBox = Ui.column(this);
        root.addView(previewBox);

        undoneBox = Ui.column(this);
        root.addView(undoneBox);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            byte[] bytes = readAll(uri);
            String name = String.valueOf(uri.getLastPathSegment());
            doPreview(bytes, name);
        } catch (Exception e) {
            setStatus("读取文件失败：" + e.getMessage());
        }
    }

    /**
     * 只做「把文件读成字节」。
     * 解压、认 xlsx、要不要密码，全部交给 BillImporter —— 那边有一份统一的判断，
     * 免得这里和导入逻辑各判断一次、早晚不一致。
     */
    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new Exception("打不开这个文件");
            }
            return drain(is);
        }
    }

    private static byte[] drain(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 加密压缩包：让用户填微信支付公众号里给的密码。 */
    private void askPassword(String why) {
        previewBox.removeAllViews();
        setStatus("需要解压密码");
        LinearLayout box = com.sudfry.todo.ui.Widgets.card(this);
        box.addView(Ui.text(this, "这个账单是加密的压缩包", 14.5f, R.color.text, true));
        TextView hint = Ui.text(this, why == null ? "" : why, 12f, R.color.text_dim);
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        hint.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(hint);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("解压密码");
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        box.addView(input);

        TextView go = Ui.textColored(this, "用这个密码导入", 14.5f, Theme.onAccent(this), true);
        go.setGravity(android.view.Gravity.CENTER);
        go.setBackground(Ui.bg(Theme.accent(this), Ui.dp(this, 14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        lp.topMargin = Ui.dp(this, 10);
        go.setLayoutParams(lp);
        go.setOnClickListener(v -> {
            tap(v);
            String pw = input.getText().toString().trim();
            if (pw.isEmpty()) {
                toast("先填密码");
                return;
            }
            doPreview(lastBytes, lastName, pw);
        });
        box.addView(go);
        previewBox.addView(box);
        Ui.pressAll(previewBox);
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || cm.getPrimaryClip() == null
                || cm.getPrimaryClip().getItemCount() == 0) {
            toast("剪贴板是空的");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (cs == null || cs.length() == 0) {
            toast("剪贴板是空的");
            return;
        }
        doPreview(cs.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")),
                "剪贴板内容");
    }

    /** 上一次选的文件，输密码后要拿它重试。 */
    private byte[] lastBytes;
    private String lastName;

    private void doPreview(byte[] bytes, String name) {
        doPreview(bytes, name, null);
    }

    private void doPreview(byte[] bytes, String name, String password) {
        lastBytes = bytes;
        lastName = name;
        preview = BillImporter.preview(this, bytes, name, password);
        if (preview.needPassword) {
            askPassword(preview.error);
            return;
        }
        previewBox.removeAllViews();
        if (preview.error != null) {
            setStatus("解析失败：" + preview.error);
            toast("没认出这是账单文件");
            return;
        }
        LinearLayout card = Widgets.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        card.setLayoutParams(lp);

        card.addView(Ui.text(this, "识别结果", 14f, R.color.text, true));
        TextView sum = Ui.text(this, preview.summary(), 12.5f, R.color.text_dim);
        sum.setLineSpacing(Ui.dp(this, 4), 1f);
        sum.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(sum);

        for (String line : BillImporter.sampleLines(preview, 12)) {
            TextView t = Ui.text(this, line, 12f, R.color.text_dim);
            t.setPadding(0, Ui.dp(this, 4), 0, 0);
            t.setSingleLine(true);
            card.addView(t);
        }

        if (preview.fresh.isEmpty()) {
            TextView hint = Ui.text(this, "没有需要新增的记录（可能已经导入过了）",
                    13f, R.color.money_in, true);
            hint.setPadding(0, Ui.dp(this, 12), 0, 0);
            card.addView(hint);
        } else {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 14), 0, 0);
            row.addView(button("确认导入 " + preview.fresh.size() + " 条", true, v -> {
                tap(v);
                lastBatchCount = preview.fresh.size();
                lastBatch = BillImporter.commit(this, preview);
                setStatus("已导入 " + lastBatchCount + " 条账目，可以整体撤销");
                toast("导入完成");
                previewBox.removeAllViews();
                preview = null;
                refreshImportedCard();
            }));
            card.addView(row);
        }
        previewBox.addView(card);
        setStatus("解析完成，确认无误再点导入");
    }

    /** 「刚刚导入了 N 条」+ 一键撤销整批。 */
    private void refreshImportedCard() {
        if (undoneBox == null) {
            return;
        }
        undoneBox.removeAllViews();
        if (lastBatch == null) {
            return;
        }
        LinearLayout card = Widgets.card(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        card.setLayoutParams(lp);

        card.addView(Ui.text(this, "刚刚导入了 " + lastBatchCount + " 条账目", 14f,
                R.color.text, true));
        card.addView(note("导错了或者分类不对，可以整批撤销 —— 只删这一批，"
                + "手动记的账和其它批次不受影响。"));
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 10), 0, 0);
        row.addView(button("撤销这次导入", false, v -> {
            tap(v);
            int n = EntryStore.get(this).deleteBatch(lastBatch);
            setStatus("已撤销这次导入，删除 " + n + " 条");
            toast("已撤销");
            lastBatch = null;
            lastBatchCount = 0;
            refreshImportedCard();
        }));
        card.addView(row);
        undoneBox.addView(card);
    }

    // ── 待确认的支付通知 ──

    private LinearLayout pendingCard;

    private void buildPending() {
        root.addView(section("③ 来自支付通知的待确认记录"));
        pendingCard = Widgets.card(this);
        root.addView(pendingCard);
        refreshPendingCard();
    }

    private void refreshPendingCard() {
        if (pendingCard == null) {
            return;
        }
        pendingCard.removeAllViews();
        final ArrayList<PendingPays.Item> list = PendingPays.all(this);
        if (list.isEmpty()) {
            pendingCard.addView(note("暂时没有。开启通知使用权后，"
                    + "支付宝/微信的支付通知会先到这里等你确认。"));
            return;
        }
        double out = 0, in = 0;
        for (PendingPays.Item it : list) {
            if (it.type == Entry.INCOME) {
                in += it.amount;
            } else {
                out += it.amount;
            }
        }
        pendingCard.addView(Ui.text(this, "有 " + list.size() + " 条待确认（支出 "
                + Money.yuan(out) + "、收入 " + Money.yuan(in) + "）", 14f, R.color.text, true));

        for (final PendingPays.Item it : list) {
            LinearLayout row = Ui.row(this);
            row.setPadding(0, Ui.dp(this, 10), 0, 0);
            LinearLayout txt = Ui.column(this);
            txt.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            txt.addView(Ui.text(this, it.platform + " · " + (it.title == null ? "" : it.title),
                    13.5f, R.color.text, true));
            TextView sub = Ui.text(this, (it.text == null ? "" : it.text), 11.5f, R.color.text_dim);
            sub.setSingleLine(true);
            txt.addView(sub);
            row.addView(txt);
            row.addView(Ui.textColored(this,
                    (it.type == Entry.INCOME ? "+" : "-") + Money.yuan(it.amount),
                    14f, Ui.color(this, it.type == Entry.INCOME ? R.color.money_in : R.color.money_out),
                    true));
            TextView ok = Ui.textColored(this, "  确认", 13f, Theme.accent(this), true);
            ok.setOnClickListener(v -> {
                tap(v);
                EntryStore.get(this).add(PendingPays.toEntry(it));
                PendingPays.remove(this, it.uid);
                refreshPendingCard();
                setStatus("已记一笔");
            });
            row.addView(ok);
            TextView no = Ui.text(this, "  忽略", 13f, R.color.text_dim);
            no.setOnClickListener(v -> {
                tap(v);
                PendingPays.remove(this, it.uid);
                refreshPendingCard();
            });
            row.addView(no);
            pendingCard.addView(row);
        }

        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 14), 0, 0);
        row.addView(button("全部确认", true, v -> {
            tap(v);
            int n = 0;
            for (PendingPays.Item it : PendingPays.all(this)) {
                EntryStore.get(this).add(PendingPays.toEntry(it));
                n++;
            }
            PendingPays.clear(this);
            refreshPendingCard();
            setStatus("已确认 " + n + " 条");
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 8), 1));
        row.addView(sp);
        row.addView(button("全部忽略", false, v -> {
            tap(v);
            PendingPays.clear(this);
            refreshPendingCard();
        }));
        pendingCard.addView(row);
    }

    private void setStatus(String s) {
        if (statusLabel != null) {
            statusLabel.setText(s);
        }
    }
}
