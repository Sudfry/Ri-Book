package com.sudfry.todo.billing;

import com.sudfry.todo.model.Entry;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析支付宝 / 微信导出的官方账单 CSV。
 *
 * 两家的格式不一样，而且以后还可能变，所以这里**不按固定列号读**：
 * 先在前 40 行里找到表头行，再按列名关键字建立映射，这样列顺序变了也不怕。
 *
 * 已知差异：
 *   支付宝：GBK 编码，前面约 24 行说明；列有 交易分类 / 商品说明 / 交易订单号
 *   微信  ：UTF-8(BOM)，前面约 16 行说明；列有 交易类型 / 商品 / 交易单号 / 金额(元)
 */
public final class BillParser {

    private BillParser() {
    }

    public static final String PLATFORM_ALIPAY = "支付宝";
    public static final String PLATFORM_WECHAT = "微信";

    public static class Row {
        public String platform = "";
        public String date = "";           // yyyy-MM-dd
        public int type = Entry.EXPENSE;
        public double amount;
        public String rawCategory = "";    // 平台自己的分类（支付宝有）
        public String counterparty = "";
        public String product = "";
        public String payMethod = "";
        public String status = "";
        public String orderNo = "";
        public String note = "";

        /** 去重用的稳定标识：优先用交易单号。 */
        public String uid() {
            String key = orderNo == null ? "" : orderNo.trim();
            if (key.isEmpty()) {
                key = (counterparty + "|" + product + "|" + date + "|" + amount);
            }
            return "bill:" + platform + ":" + key;
        }
    }

    public static class Result {
        public String platform = "";
        public String encoding = "";
        public String headerLine = "";
        public String error;
        public int totalLines;
        public int skippedNoDirection;
        public int skippedRefund;
        public int skippedBadAmount;
        public final ArrayList<Row> rows = new ArrayList<>();
    }

    // ───────────────────── 入口 ─────────────────────

    public static Result parse(byte[] data, String nameHint) {
        Result r = new Result();
        if (data == null || data.length == 0) {
            r.error = "文件是空的";
            return r;
        }
        String text = decode(data, r);
        String[] lines = text.split("\r\n|\n|\r");
        r.totalLines = lines.length;

        int headerIdx = -1;
        List<String> header = null;
        for (int i = 0; i < Math.min(lines.length, 40); i++) {
            List<String> cols = splitCsv(lines[i]);
            if (looksLikeHeader(cols)) {
                headerIdx = i;
                header = cols;
                break;
            }
        }
        if (header == null) {
            r.error = "没找到账单表头（应该在文件前 40 行内，含「交易时间」和「收/支」）。"
                    + "请确认导出的是 CSV 而不是 Excel/PDF。";
            return r;
        }
        r.headerLine = lines[headerIdx].length() > 160
                ? lines[headerIdx].substring(0, 160) : lines[headerIdx];

        int iTime = indexOf(header, "交易时间", "交易创建时间", "时间");
        int iDir = indexOf(header, "收/支", "收支", "资金方向");
        int iAmt = indexOf(header, "金额");
        int iParty = indexOf(header, "交易对方", "对方");
        int iGoods = indexOf(header, "商品说明", "商品", "商品名称");
        int iPay = indexOf(header, "收/付款方式", "支付方式", "付款方式");
        int iStatus = indexOf(header, "当前状态", "交易状态", "状态");
        int iOrder = indexOf(header, "交易单号", "交易订单号", "交易号", "订单号");
        int iCat = indexOf(header, "交易分类", "分类");
        int iNote = indexOf(header, "备注");
        int iType = indexOf(header, "交易类型");

        r.platform = detectPlatform(header, nameHint, iCat, iGoods, iType);
        if (iTime < 0 || iDir < 0 || iAmt < 0) {
            r.error = "表头缺少必要列（需要「交易时间」「收/支」「金额」）。识别到的表头：" + r.headerLine;
            return r;
        }

        for (int i = headerIdx + 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            List<String> cols = splitCsv(line);
            String dir = cell(cols, iDir).trim();
            if (!"收入".equals(dir) && !"支出".equals(dir)) {
                if (!dir.isEmpty()) {
                    r.skippedNoDirection++;
                }
                continue;
            }
            String status = cell(cols, iStatus);
            if (isRefundOrClosed(status)) {
                r.skippedRefund++;
                continue;
            }
            double amount = parseAmount(cell(cols, iAmt));
            if (amount <= 0) {
                r.skippedBadAmount++;
                continue;
            }
            String date = normalizeDate(cell(cols, iTime));
            if (date.isEmpty()) {
                r.skippedBadAmount++;
                continue;
            }

            Row row = new Row();
            row.platform = r.platform;
            row.date = date;
            row.type = "收入".equals(dir) ? Entry.INCOME : Entry.EXPENSE;
            row.amount = amount;
            row.rawCategory = cell(cols, iCat);
            if (row.rawCategory.isEmpty()) {
                // 微信没有「交易分类」列，但「交易类型」一样有用：
                // 微信红包 / 转账 / 商户消费 / 零钱提现……
                row.rawCategory = cell(cols, iType);
            }
            row.counterparty = cell(cols, iParty);
            row.product = cell(cols, iGoods);
            row.payMethod = cell(cols, iPay);
            row.status = status;
            row.orderNo = cell(cols, iOrder);
            row.note = cell(cols, iNote);
            if (row.product.isEmpty()) {
                row.product = row.rawCategory;
            }
            r.rows.add(row);
        }
        return r;
    }

    // ───────────────────── 内部工具 ─────────────────────

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset GBK = Charset.forName("GBK");

    /** 先看 BOM，再按 UTF-8 严格解码，失败就按 GBK（支付宝账单就是 GBK）。 */
    public static String decode(byte[] data, Result r) {
        String enc = "UTF-8";
        String text;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            text = new String(data, 3, data.length - 3, UTF8);
            enc = "UTF-8 (BOM)";
        } else {
            try {
                CharsetDecoder dec = UTF8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                text = dec.decode(ByteBuffer.wrap(data)).toString();
            } catch (Exception e) {
                text = new String(data, GBK);
                enc = "GBK";
            }
        }
        if (r != null) {
            r.encoding = enc;
        }
        return text;
    }

    private static boolean looksLikeHeader(List<String> cols) {
        String joined = String.join(",", cols);
        boolean hasTime = joined.contains("交易时间") || joined.contains("交易创建时间");
        boolean hasDir = joined.contains("收/支") || joined.contains("收支");
        boolean hasAmount = joined.contains("金额");
        return hasTime && hasDir && hasAmount;
    }

    private static String detectPlatform(List<String> header, String nameHint,
                                         int iCat, int iGoods, int iType) {
        String joined = String.join(",", header);
        if (joined.contains("交易分类") && joined.contains("商品说明")) {
            return PLATFORM_ALIPAY;
        }
        if (joined.contains("金额(元)") || joined.contains("交易单号")
                || joined.contains("当前状态") || joined.contains("交易类型")) {
            return PLATFORM_WECHAT;
        }
        String hint = nameHint == null ? "" : nameHint.toLowerCase();
        if (hint.contains("微信") || hint.contains("wechat") || hint.contains("wx")) {
            return PLATFORM_WECHAT;
        }
        if (hint.contains("支付宝") || hint.contains("alipay")) {
            return PLATFORM_ALIPAY;
        }
        if (iCat >= 0) {
            return PLATFORM_ALIPAY;
        }
        return "未知平台";
    }

    /** 退款 / 关闭的交易默认不计入，避免重复统计。 */
    private static boolean isRefundOrClosed(String status) {
        if (status == null) {
            return false;
        }
        String s = status.trim();
        return s.contains("退款") || s.contains("已关闭") || s.contains("交易关闭")
                || s.contains("已撤销") || s.contains("失败");
    }

    private static int indexOf(List<String> cols, String... keys) {
        for (String key : keys) {
            for (int i = 0; i < cols.size(); i++) {
                String c = cols.get(i).trim().replace("\uFEFF", "");
                if (c.equals(key)) {
                    return i;
                }
            }
        }
        for (String key : keys) {
            for (int i = 0; i < cols.size(); i++) {
                String c = cols.get(i).trim().replace("\uFEFF", "");
                if (c.contains(key)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String cell(List<String> cols, int idx) {
        if (idx < 0 || idx >= cols.size()) {
            return "";
        }
        String s = cols.get(idx);
        return s == null ? "" : s.trim();
    }

    /** 处理带引号的 CSV，引号内的逗号不算分隔符。 */
    public static List<String> splitCsv(String line) {
        ArrayList<String> out = new ArrayList<>();
        if (line == null) {
            return out;
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuote) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    sb.append(ch);
                }
            } else if (ch == '"') {
                inQuote = true;
            } else if (ch == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out;
    }

    /** ¥1,234.56 / ￥12.50 / "12.50" → 1234.56 */
    public static double parseAmount(String raw) {
        if (raw == null) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                sb.append(c);
            }
        }
        try {
            return Double.parseDouble(sb.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 2026-10-11 12:34:56 / 2026/10/11 12:34 → 2026-10-11 */
    public static String normalizeDate(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replace('/', '-');
        if (s.length() >= 10) {
            String d = s.substring(0, 10);
            if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return d;
            }
        }
        return "";
    }
}
