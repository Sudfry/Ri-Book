package com.sudfry.todo.billing;

import android.content.Context;

import com.sudfry.todo.model.Entry;
import com.sudfry.todo.store.EntryStore;
import com.sudfry.todo.util.Money;

import java.util.ArrayList;
import java.util.HashSet;

/** 账单导入：解析 → 分类 → 查重 → 入库。 */
public final class BillImporter {

    private BillImporter() {
    }

    public static class Preview {
        public BillParser.Result parsed = new BillParser.Result();
        /** 过滤掉已存在的之后，真正会写入的条目 */
        public final ArrayList<Entry> fresh = new ArrayList<>();
        public int duplicate;
        public int refunded;
        public int ignored;
        public double sumOut;
        public double sumIn;
        public String error;
        /** 文件来源说明（xlsx / 已解压的压缩包…） */
        public String source = "";
        /** 需要用户输入解压密码 */
        public boolean needPassword;

        public String summary() {
            return "识别为 " + parsed.platform + " 账单（" + parsed.encoding + "）" 
                    + (source.isEmpty() ? "" : " · " + source) + "\n"
                    + "可导入 " + fresh.size() + " 条（支出 " + Money.yuan(sumOut)
                    + "、收入 " + Money.yuan(sumIn) + "）\n"
                    + "已存在跳过 " + duplicate + " 条 · 退款/关闭跳过 " + refunded
                    + " 条 · 不计收支跳过 " + ignored + " 条";
        }
    }

    /** 读文件的结果：可能是 xlsx、可能是 zip 里的一层、也可能需要密码。 */
    public static class Loaded {
        public byte[] payload;
        public String label = "";
        public String error;
        public boolean needPassword;
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static boolean isBillName(String n) {
        if (n == null) {
            return false;
        }
        String s = n.toLowerCase();
        return (s.endsWith(".csv") || s.endsWith(".txt") || s.endsWith(".xlsx")
                || s.endsWith(".xls")) && !s.startsWith("__macosx");
    }

    /**
     * 把用户选的文件读成「可以直接解析的字节」。
     *
     * 支持：CSV/文本 · xlsx（微信新版账单就是这个）· 压缩包（里面的 csv 或 xlsx）·
     * 带密码的压缩包（微信账单的老格式，密码在「微信支付」公众号消息里）。
     * 不联网、不猜密码、不做字典爆破。
     */
    public static Loaded load(byte[] data, String name, String password) {
        Loaded l = new Loaded();
        if (data == null || data.length == 0) {
            l.error = "文件是空的";
            return l;
        }
        try {
            String lower = name == null ? "" : name.toLowerCase();
            if (ZipCrypto.isZip(data)) {
                byte[] inner = null;
                String innerName = "";
                if (ZipCrypto.isEncrypted(data)) {
                    if (password == null || password.trim().isEmpty()) {
                        l.needPassword = true;
                        l.error = "这是加密的压缩包。微信账单的密码在「微信支付」公众号的那条消息里，"
                                + "点下面的输入框填进去再导入。";
                        return l;
                    }
                    java.util.List<ZipCrypto.Entry> es = ZipCrypto.decrypt(data, password.trim());
                    for (ZipCrypto.Entry e : es) {
                        if (isBillName(e.name)) {
                            inner = e.data;
                            innerName = e.name;
                            break;
                        }
                    }
                    if (inner == null) {
                        inner = es.get(0).data;
                        innerName = es.get(0).name;
                    }
                    l.label = "已解压：" + innerName;
                } else {
                    java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                            new java.io.ByteArrayInputStream(data));
                    java.util.zip.ZipEntry e;
                    byte[] first = null;
                    String firstName = "";
                    boolean looksXlsx = false;
                    while ((e = zis.getNextEntry()) != null) {
                        if (e.isDirectory()) {
                            continue;
                        }
                        String n = e.getName();
                        if (n.startsWith("__MACOSX")) {
                            continue;
                        }
                        // xlsx 本身就是个 zip，特征是里面有 xl/ 或 [Content_Types].xml。
                        // 这种情况不能抽单个条目，要把整个包原样交给 xlsx 解析。
                        if (n.startsWith("xl/") || n.equals("[Content_Types].xml")) {
                            looksXlsx = true;
                        }
                        byte[] b = readAll(zis);
                        if (isBillName(n)) {
                            inner = b;
                            innerName = n;
                            break;
                        }
                        if (first == null) {
                            first = b;
                            firstName = n;
                        }
                    }
                    zis.close();
                    if (inner == null && !looksXlsx) {
                        inner = first;      // 不是 xlsx 容器，那就退而取第一个条目
                        innerName = firstName;
                    }
                    if (inner == null && !looksXlsx) {
                        l.error = "压缩包里没有可读文件";
                        return l;
                    }
                    if (looksXlsx) {
                        l.label = "Excel 工作簿";
                    } else {
                        l.label = "压缩包里的 " + innerName;
                        data = inner;
                        name = innerName;
                        lower = innerName.toLowerCase();
                    }
                }
            }

            // 还是 zip，或者扩展名就是 xlsx → 当 Excel 处理
            if (ZipCrypto.isZip(data) || lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
                java.util.List<java.util.List<String>> rows =
                        XlsxReader.read(new java.io.ByteArrayInputStream(data));
                if (rows.isEmpty()) {
                    l.error = "这个 Excel 里没有内容";
                    return l;
                }
                l.payload = XlsxReader.toCsv(rows).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                l.label = (l.label.isEmpty() ? "" : l.label + " · ") + "Excel " + rows.size() + " 行";
                return l;
            }
            l.payload = data;
            if (l.label.isEmpty()) {
                l.label = "账单文本";
            }
            return l;
        } catch (Throwable t) {
            l.error = "读文件失败：" + t.getMessage();
            return l;
        }
    }

    public static Preview preview(Context c, byte[] data, String name) {
        return preview(c, data, name, null);
    }

    public static Preview preview(Context c, byte[] data, String name, String password) {
        Preview p = new Preview();
        Loaded loaded = load(data, name, password);
        p.source = loaded.label;
        p.needPassword = loaded.needPassword;
        if (loaded.error != null) {
            p.error = loaded.error;
            p.parsed.error = loaded.error;
            return p;
        }
        p.parsed = BillParser.parse(loaded.payload, name);
        p.refunded = p.parsed.skippedRefund;
        p.ignored = p.parsed.skippedNoDirection;
        if (p.parsed.error != null) {
            p.error = p.parsed.error;
            return p;
        }
        HashSet<String> existing = new HashSet<>();
        for (Entry e : EntryStore.get(c).all()) {
            if (e.uid != null) {
                existing.add(e.uid);
            }
        }
        HashSet<String> seen = new HashSet<>();
        for (BillParser.Row row : p.parsed.rows) {
            Entry e = toEntry(row);
            if (existing.contains(e.uid) || !seen.add(e.uid)) {
                p.duplicate++;
                continue;
            }
            p.fresh.add(e);
            if (e.isIncome()) {
                p.sumIn += e.amount;
            } else {
                p.sumOut += e.amount;
            }
        }
        return p;
    }

    public static Entry toEntry(BillParser.Row row) {
        // 备注里也常有关键词（例如微信转账的「还款」），一起交给分类规则
        String hint = (row.product == null ? "" : row.product) + " "
                + (row.note == null ? "" : row.note);
        String[] cat = CategoryRules.classify(row.type, row.rawCategory,
                row.counterparty, hint);
        Entry e = new Entry(row.date, row.type, row.amount, cat[0], cat[1]);
        e.uid = row.uid();
        e.platform = row.platform;
        StringBuilder note = new StringBuilder();
        if (row.product != null && !row.product.isEmpty()) {
            note.append(row.product);
        } else if (row.counterparty != null && !row.counterparty.isEmpty()) {
            note.append(row.counterparty);
        }
        if (row.counterparty != null && !row.counterparty.isEmpty()
                && !row.counterparty.equals(row.product)) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append(row.counterparty);
        }
        e.note = note.length() > 60 ? note.substring(0, 60) : note.toString();
        return e;
    }

    /** 写入并返回本次批次号（用于整体撤销）。 */
    public static String commit(Context c, Preview p) {
        EntryStore store = EntryStore.get(c);
        String batch = "bill:" + p.parsed.platform + ":" + System.currentTimeMillis();
        for (Entry e : p.fresh) {
            e.batch = batch;
            store.add(e);
        }
        return batch;
    }

    /** 预览列表文本（前若干条）。 */
    public static ArrayList<String> sampleLines(Preview p, int limit) {
        ArrayList<String> out = new ArrayList<>();
        int i = 0;
        for (Entry e : p.fresh) {
            if (i++ >= limit) {
                out.add("… 还有 " + (p.fresh.size() - limit) + " 条");
                break;
            }
            out.add(e.date + "  " + (e.isIncome() ? "+" : "-") + Money.yuan(e.amount)
                    + "  " + e.category + "/" + e.sub
                    + (e.note == null || e.note.isEmpty() ? "" : "  " + e.note));
        }
        return out;
    }
}
