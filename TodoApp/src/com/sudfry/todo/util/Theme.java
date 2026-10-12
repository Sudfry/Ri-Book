package com.sudfry.todo.util;

import android.app.UiModeManager;
import android.content.Context;

import com.sudfry.todo.R;
import android.graphics.Color;
import android.os.Build;

/**
 * 主题色：强调色由「色相 + 饱和度」实时算出来（对应 ribook 的颜色调节）。
 * 注意只改强调色，紧急度那五个语义色（红/橙/黄/绿/灰）保持不变，
 * 否则「哪件事最急」就看不出来了。
 */
public final class Theme {

    private Theme() {
    }

    /** 预设色点：{名字, 色相, 饱和度} */
    public static final Object[][] PRESETS = {
            {"默认蓝", 230, 69},
            {"薄荷绿", 160, 45},
            {"青", 190, 55},
            {"紫", 275, 55},
            {"珊瑚", 12, 62},
            {"金", 40, 70},
            {"粉", 330, 55},
            {"石墨", 220, 8},
    };

    // ───────────────── 紧急度配色方案 ─────────────────
    // 五档顺序固定：已逾期 / 今天 / 临近 / 以后 / 无期限。
    // 只换颜色，不动语义 —— 这是「哪件事最急」的信息载体。

    public static final String[] PALETTE_IDS = {"standard", "soft", "vivid", "cb"};

    private static final int[][] PALETTES = {
            // 标准
            {0xFFE5484D, 0xFFF76B15, 0xFFD99A00, 0xFF2F9E62, 0xFF8A8F98},
            // 柔和（降饱和，长时间看更舒服）
            {0xFFD9737B, 0xFFE39A63, 0xFFC7A94E, 0xFF6BA88A, 0xFF9AA0A8},
            // 高对比（更深更饱和，阳光下也看得清）
            {0xFFC2181F, 0xFFD9480F, 0xFFA67C00, 0xFF12784F, 0xFF6C757D},
            // 色盲友好（把红绿轴换成红蓝轴，红绿色盲也能区分「逾期」和「以后」）
            {0xFFC2185B, 0xFFEF6C00, 0xFFF9A825, 0xFF1565C0, 0xFF78909C},
    };

    public static String paletteName(String id) {
        switch (id == null ? "standard" : id) {
            case "soft":
                return "柔和";
            case "vivid":
                return "高对比";
            case "cb":
                return "色盲友好";
            default:
                return "标准";
        }
    }

    public static String paletteHint(String id) {
        switch (id == null ? "standard" : id) {
            case "soft":
                return "降低饱和度，长时间看不刺眼";
            case "vivid":
                return "更深更饱和，户外强光下也看得清";
            case "cb":
                return "红绿色盲也能区分「已逾期」和「以后」（用红—蓝轴）";
            default:
                return "红 / 橙 / 黄 / 绿 / 灰，最直观";
        }
    }

    /** 紧急度第 level 档的颜色（0 逾期 1 今天 2 临近 3 以后 4 无期限）。 */
    public static int urgency(Context c, int level) {
        int idx = paletteIndex(AppPrefs.get(c).urgencyPalette());
        int lv = Math.max(0, Math.min(4, level));
        int color = PALETTES[idx][lv];
        return isDark(c) ? brightenForDark(color) : color;
    }

    /** 默认方案直接用资源里的颜色，保证和旧数据、旧截图一致。 */
    public static int urgencyResOrColor(Context c, int level) {
        return urgency(c, level);
    }

    private static int paletteIndex(String id) {
        for (int i = 0; i < PALETTE_IDS.length; i++) {
            if (PALETTE_IDS[i].equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private static int brightenForDark(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(1f, hsv[2] * 1.28f);
        hsv[1] = Math.max(0f, hsv[1] * 0.92f);
        return Color.HSVToColor(hsv);
    }

    public static boolean isDark(Context c) {
        int mode = AppPrefs.get(c).themeMode();
        if (mode == 1) {
            return false;
        }
        if (mode == 2) {
            return true;
        }
        return Ui.night(c);
    }

    public static int accent(Context c) {
        AppPrefs p = AppPrefs.get(c);
        boolean dark = isDark(c);
        return Color.HSVToColor(new float[]{
                p.hue(), p.sat() / 100f, dark ? 0.72f : 0.55f});
    }

    public static int accentDeep(Context c) {
        AppPrefs p = AppPrefs.get(c);
        boolean dark = isDark(c);
        return Color.HSVToColor(new float[]{
                p.hue(), Math.min(1f, p.sat() / 100f + 0.05f), dark ? 0.85f : 0.42f});
    }

    /** 强调色的浅底（选中态背景）。 */
    public static int accentSoft(Context c) {
        AppPrefs p = AppPrefs.get(c);
        boolean dark = isDark(c);
        return Color.HSVToColor(new float[]{
                p.hue(), dark ? 0.35f : 0.22f, dark ? 0.18f : 0.94f});
    }

    /** 强调色上的文字颜色。 */
    public static int onAccent(Context c) {
        return Color.WHITE;
    }

    /**
     * 应用明暗模式的强制选择。Android 12(API 31) 起支持给单个应用设定日夜模式，
     * 更早的系统只能跟随系统设置（设置页会说明这一点）。
     */
    public static void applyNightMode(Context c) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            UiModeManager um = c.getSystemService(UiModeManager.class);
            if (um == null) {
                return;
            }
            int mode = AppPrefs.get(c).themeMode();
            int target = mode == 1 ? UiModeManager.MODE_NIGHT_NO
                    : mode == 2 ? UiModeManager.MODE_NIGHT_YES
                    : UiModeManager.MODE_NIGHT_AUTO;
            if (um.getNightMode() != target) {
                um.setApplicationNightMode(target);
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean supportsManualNight() {
        return Build.VERSION.SDK_INT >= 31;
    }

    // ───────────────── 皮肤风格 ─────────────────
    // 只动「形状语言」：圆角大小、描边、阴影浓度。
    // 颜色仍然由主题色 + 紧急度配色管，两件事不互相干扰。

    public static final String[] STYLE_IDS = {"round", "standard", "square"};

    public static String style(Context c) {
        return AppPrefs.get(c).style();
    }

    public static String styleName(String id) {
        switch (id == null ? "standard" : id) {
            case "round":
                return "圆润";
            case "square":
                return "方正";
            default:
                return "标准";
        }
    }

    public static String styleHint(String id) {
        switch (id == null ? "standard" : id) {
            case "round":
                return "大圆角、更柔和的卡片，界面更活泼";
            case "square":
                return "小圆角、细描边、不加阴影，信息密度更高";
            default:
                return "中等圆角 + 淡描边，最平衡";
        }
    }

    private static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** 卡片圆角（px）。 */
    public static float cardRadius(Context c) {
        switch (style(c)) {
            case "round":
                return dp(c, 24);
            case "square":
                return dp(c, 7);
            default:
                return dp(c, 16);
        }
    }

    /** 列表行圆角（px）。 */
    public static float rowRadius(Context c) {
        switch (style(c)) {
            case "round":
                return dp(c, 20);
            case "square":
                return dp(c, 6);
            default:
                return dp(c, 14);
        }
    }

    /** 胶囊/标签圆角。方正风格下不再是全圆。 */
    public static float chipRadius(Context c) {
        return "square".equals(style(c)) ? dp(c, 8) : 999f;
    }

    /** 底部面板 / 对话框的圆角。 */
    public static float panelRadius(Context c) {
        switch (style(c)) {
            case "round":
                return dp(c, 30);
            case "square":
                return dp(c, 12);
            default:
                return dp(c, 22);
        }
    }

    /** 卡片描边宽度；方正风格描边更明显，圆润风格干脆不要描边。 */
    public static float cardStroke(Context c) {
        switch (style(c)) {
            case "round":
                return dp(c, 1);
            case "square":
                return dp(c, 1.4f);
            default:
                return dp(c, 1);
        }
    }

    public static int cardStrokeColor(Context c) {
        return "square".equals(style(c)) ? 0x22000000 : Ui.color(c, R.color.divider);
    }

    /** 卡片阴影；方正风格不求立体，靠描边分层。 */
    public static float cardElevation(Context c) {
        switch (style(c)) {
            case "round":
                return dp(c, 2.5f);
            case "square":
                return 0f;
            default:
                return dp(c, 1.5f);
        }
    }
}
