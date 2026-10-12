package com.sudfry.todo.billing;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 读 .xlsx（微信新版账单导出的就是这个格式）。
 *
 * xlsx 本质是个 zip，里面是 XML：
 *   xl/sharedStrings.xml      字符串池（单元格里存的是下标）
 *   xl/worksheets/sheet1.xml  单元格数据，形如 <c r="B3" t="s"><v>12</v></c>
 *
 * 这里只做「把表格读成二维字符串」这一件事，
 * 读出来之后交给 BillParser 那套按表头找列的规则，CSV 和 Excel 走同一条路。
 * 不引入任何第三方库，用系统自带的 zip + XmlPullParser。
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    /** xlsx（或装了 xlsx 的 zip）里的表格，按行返回。 */
    public static List<List<String>> read(InputStream in) throws Exception {
        Map<Integer, String> shared = new HashMap<>();
        byte[] sheet = null;
        String sheetName = null;

        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry e;
        while ((e = zis.getNextEntry()) != null) {
            String n = e.getName();
            if (n.startsWith("__MACOSX")) {
                continue;
            }
            if ("xl/sharedStrings.xml".equals(n)) {
                shared = parseShared(collect(zis));
            } else if (n.startsWith("xl/worksheets/") && n.endsWith(".xml")) {
                // 取第一个工作表（账单只有一个）
                if (sheet == null || n.compareTo(sheetName) < 0) {
                    sheet = collect(zis);
                    sheetName = n;
                }
            }
        }
        zis.close();
        if (sheet == null) {
            throw new Exception("这个 xlsx 里没有工作表");
        }
        return parseSheet(sheet, shared);
    }

    private static byte[] collect(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    // ───────────────── 字符串池 ─────────────────

    private static Map<Integer, String> parseShared(byte[] xml) throws Exception {
        Map<Integer, String> out = new HashMap<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");
        int idx = -1;
        StringBuilder sb = new StringBuilder();
        boolean inSi = false, inT = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            String name = p.getName();
            if (ev == XmlPullParser.START_TAG) {
                if ("si".equals(name)) {
                    inSi = true;
                    sb.setLength(0);
                    idx++;
                } else if ("t".equals(name) && inSi) {
                    inT = true;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inT) {
                    sb.append(p.getText());
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("t".equals(name)) {
                    inT = false;
                } else if ("si".equals(name)) {
                    out.put(idx, sb.toString());
                    inSi = false;
                }
            }
        }
        return out;
    }

    // ───────────────── 工作表 ─────────────────

    private static List<List<String>> parseSheet(byte[] xml, Map<Integer, String> shared)
            throws Exception {
        List<List<String>> rows = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new ByteArrayInputStream(xml), "UTF-8");

        List<String> cur = null;
        int col = 0;
        String type = null;
        boolean inV = false, inT = false, inIs = false;
        StringBuilder text = new StringBuilder();

        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            String name = p.getName();
            if (ev == XmlPullParser.START_TAG) {
                if ("row".equals(name)) {
                    cur = new ArrayList<>();
                } else if ("c".equals(name) && cur != null) {
                    String ref = attr(p, "r");
                    col = ref == null ? col : colOf(ref);
                    type = attr(p, "t");
                    text.setLength(0);
                } else if ("v".equals(name)) {
                    inV = true;
                } else if ("is".equals(name)) {
                    inIs = true;
                } else if ("t".equals(name) && inIs) {
                    inT = true;
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inV || inT) {
                    text.append(p.getText());
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("v".equals(name)) {
                    inV = false;
                } else if ("t".equals(name)) {
                    inT = false;
                } else if ("is".equals(name)) {
                    inIs = false;
                } else if ("c".equals(name) && cur != null) {
                    String val = text.toString();
                    if ("s".equals(type)) {
                        // 存的是字符串池下标
                        try {
                            val = shared.containsKey(Integer.parseInt(val))
                                    ? shared.get(Integer.parseInt(val)) : "";
                        } catch (Exception ex) {
                            val = "";
                        }
                    } else if ("n".equals(type) || type == null) {
                        val = excelDateIfLooksLikeOne(val);
                    }
                    while (cur.size() < col) {
                        cur.add("");
                    }
                    if (cur.size() == col) {
                        cur.add(val);
                    } else {
                        cur.set(col, val);
                    }
                    col++;
                } else if ("row".equals(name) && cur != null) {
                    // 去掉行尾空格，账单末尾常有一堆空列
                    while (!cur.isEmpty() && cur.get(cur.size() - 1).trim().isEmpty()) {
                        cur.remove(cur.size() - 1);
                    }
                    rows.add(cur);
                    cur = null;
                }
            }
        }
        return rows;
    }

    private static String attr(XmlPullParser p, String name) {
        return p.getAttributeValue(null, name);
    }

    /** "B12" → 1（0 基列号）。 */
    static int colOf(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                n = n * 26 + (ch - 'A' + 1);
            } else if (ch >= 'a' && ch <= 'z') {
                n = n * 26 + (ch - 'a' + 1);
            } else {
                break;
            }
        }
        return Math.max(0, n - 1);
    }

    /**
     * 有些账单把时间存成 Excel 序列号（45000.5 这种）。
     * 直接当数字给下游会认不出日期，所以这里顺手转成 yyyy-MM-dd HH:mm:ss。
     * 注意只转「看着像日期」的整数/小数，金额不会落在这个区间。
     */
    static String excelDateIfLooksLikeOne(String v) {
        if (v == null || v.isEmpty()) {
            return v;
        }
        try {
            double d = Double.parseDouble(v);
            if (d < 20000 || d > 60000) {
                return v;   // 1954 年之前 / 2064 年之后，不像账单日期
            }
            long days = (long) Math.floor(d);
            double frac = d - days;
            java.time.LocalDateTime base = java.time.LocalDateTime.of(1899, 12, 30, 0, 0);
            java.time.LocalDateTime dt = base.plusDays(days)
                    .plusSeconds(Math.round(frac * 86400));
            return String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                    dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(),
                    dt.getHour(), dt.getMinute(), dt.getSecond());
        } catch (Exception e) {
            return v;
        }
    }

    /** 从 xlsx 里认出平台：看前几行有没有「微信」/「支付宝」字样。 */
    public static String guessPlatform(List<List<String>> rows, String nameHint) {
        StringBuilder sb = new StringBuilder(nameHint == null ? "" : nameHint);
        for (int i = 0; i < Math.min(rows.size(), 6); i++) {
            for (String c : rows.get(i)) {
                sb.append(' ').append(c);
            }
        }
        String s = sb.toString();
        boolean wx = s.contains("微信");
        boolean ali = s.contains("支付宝");
        if (wx && !ali) {
            return BillParser.PLATFORM_WECHAT;
        }
        if (ali && !wx) {
            return BillParser.PLATFORM_ALIPAY;
        }
        return wx ? BillParser.PLATFORM_WECHAT
                : (ali ? BillParser.PLATFORM_ALIPAY : "");
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
    /**
     * 把表格转成标准 CSV，交给 BillParser 那套按表头找列的规则。
     * 这样 CSV 和 Excel 走的是同一条解析路径，规则只有一份。
     */
    public static String toCsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String v = row.get(i) == null ? "" : row.get(i);
                if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(v);
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}