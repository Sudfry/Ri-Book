package com.sudfry.todo.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.sudfry.todo.model.Categories;
import com.sudfry.todo.model.Entry;

import java.util.ArrayList;
import java.util.List;

/** 全部偏好设置（主题、音效、预算、结算）集中在这里。 */
public final class AppPrefs {

    private static final String NAME = "todo_prefs";

    private static final String K_ORIENT = "orientation";
    private static final String K_THEME_MODE = "theme_mode";      // 0 跟随系统 1 浅色 2 深色
    private static final String K_HUE = "accent_hue";
    private static final String K_SAT = "accent_sat";
    private static final String K_SOUND_ON = "sound_on";
    private static final String K_SOUND_VOL = "sound_vol";
    private static final String K_SOUND_TYPE = "sound_type";
    private static final String K_BUDGET = "daily_budget";
    private static final String K_FEE = "living_fee";
    private static final String K_SETTLE = "settle_subs";
    private static final String K_PLATFORM = "default_platform";
    private static final String K_DEF_TYPE = "default_type";
    private static final String K_ANALYSIS = "analysis_range";
    private static final String K_PALETTE = "urgency_palette";
    private static final String K_TODO_VIEW = "todo_view";
    private static final String K_CAL_VIEW = "calendar_view";
    private static final String K_FOCUS_ID = "focus_id";
    private static final String K_FOCUS_START = "focus_start";
    private static final String K_FOCUS_MIN = "focus_min";
    private static final String K_FOCUS_ACTIVE = "focus_active";
    private static final String K_FOCUS_LENGTH = "focus_length";
    private static final String K_QUICK_NOTIFY = "quick_notify";
    private static final String K_DRAG_HINT = "drag_hint_shown";
    private static final String K_STYLE = "ui_style";
    private static final String K_AUTO_BK = "auto_backup";
    private static final String K_AUTO_BK_H = "auto_backup_hours";
    private static final String K_LAST_BK = "last_backup_at";
    private static final String K_LAST_CAP_AT = "last_cap_at";
    private static final String K_LAST_CAP_TXT = "last_cap_txt";
    private static final String K_SEARCH_HIST = "search_history";

    public static final int DEFAULT_HUE = 230;
    public static final int DEFAULT_SAT = 69;

    private static AppPrefs instance;

    private final SharedPreferences sp;

    private AppPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AppPrefs get(Context c) {
        if (instance == null) {
            instance = new AppPrefs(c);
        }
        return instance;
    }

    // ── 屏幕方向 ──
    public int orientation() {
        return sp.getInt(K_ORIENT, 0);
    }

    public void setOrientation(int v) {
        sp.edit().putInt(K_ORIENT, v).apply();
    }

    // ── 主题 ──
    public int themeMode() {
        return sp.getInt(K_THEME_MODE, 0);
    }

    public void setThemeMode(int v) {
        sp.edit().putInt(K_THEME_MODE, v).apply();
    }

    public int hue() {
        return sp.getInt(K_HUE, DEFAULT_HUE);
    }

    public void setHue(int v) {
        sp.edit().putInt(K_HUE, ((v % 360) + 360) % 360).apply();
    }

    public int sat() {
        return sp.getInt(K_SAT, DEFAULT_SAT);
    }

    public void setSat(int v) {
        sp.edit().putInt(K_SAT, Math.max(0, Math.min(80, v))).apply();
    }

    public void setAccent(int hue, int sat) {
        sp.edit().putInt(K_HUE, ((hue % 360) + 360) % 360)
                .putInt(K_SAT, Math.max(0, Math.min(80, sat))).apply();
    }

    // ── 音效 ──
    public boolean soundOn() {
        return sp.getBoolean(K_SOUND_ON, true);
    }

    public void setSoundOn(boolean v) {
        sp.edit().putBoolean(K_SOUND_ON, v).apply();
    }

    public int soundVol() {
        return sp.getInt(K_SOUND_VOL, 60);
    }

    public void setSoundVol(int v) {
        sp.edit().putInt(K_SOUND_VOL, Math.max(0, Math.min(100, v))).apply();
    }

    public String soundType() {
        return sp.getString(K_SOUND_TYPE, "soft");
    }

    public void setSoundType(String v) {
        sp.edit().putString(K_SOUND_TYPE, v).apply();
    }

    // ── 记账 ──
    public double dailyBudget() {
        return Double.longBitsToDouble(sp.getLong(K_BUDGET, Double.doubleToLongBits(0)));
    }

    public void setDailyBudget(double v) {
        sp.edit().putLong(K_BUDGET, Double.doubleToLongBits(Math.max(0, v))).apply();
    }

    public double livingFee() {
        return Double.longBitsToDouble(sp.getLong(K_FEE, Double.doubleToLongBits(0)));
    }

    public void setLivingFee(double v) {
        sp.edit().putLong(K_FEE, Double.doubleToLongBits(Math.max(0, v))).apply();
    }

    /** 纳入生活费结算的支出小类。默认全选。 */
    public List<String> settleCats() {
        String raw = sp.getString(K_SETTLE, null);
        if (raw == null) {
            return Categories.allExpenseSubs();
        }
        ArrayList<String> out = new ArrayList<>();
        for (String s : raw.split("\n")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    public void setSettleCats(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s);
        }
        sp.edit().putString(K_SETTLE, sb.toString()).apply();
    }

    public String defaultPlatform() {
        return sp.getString(K_PLATFORM, "银行卡");
    }

    public void setDefaultPlatform(String v) {
        sp.edit().putString(K_PLATFORM, v).apply();
    }

    public int defaultType() {
        return sp.getInt(K_DEF_TYPE, Entry.EXPENSE);
    }

    public void setDefaultType(int v) {
        sp.edit().putInt(K_DEF_TYPE, v).apply();
    }

    public String analysisRange() {
        return sp.getString(K_ANALYSIS, "month");
    }

    public void setAnalysisRange(String v) {
        sp.edit().putString(K_ANALYSIS, v).apply();
    }

    /** 紧急度配色方案：standard / soft / vivid / cb */
    public String urgencyPalette() {
        return sp.getString(K_PALETTE, "standard");
    }

    public void setUrgencyPalette(String v) {
        sp.edit().putString(K_PALETTE, v).apply();
    }

    /** 待办页的呈现方式：list 列表 / matrix 四象限 */
    public String todoView() {
        return sp.getString(K_TODO_VIEW, "list");
    }

    public void setTodoView(String v) {
        sp.edit().putString(K_TODO_VIEW, v).apply();
    }

    /** 日历页：month 月 / week 周 */
    public String calendarView() {
        return sp.getString(K_CAL_VIEW, "month");
    }

    public void setCalendarView(String v) {
        sp.edit().putString(K_CAL_VIEW, v).apply();
    }

    // ── 专注计时（番茄钟）会话 ──

    public void setFocus(long todoId, long start, int minutes, boolean active) {
        sp.edit().putLong(K_FOCUS_ID, todoId)
                .putLong(K_FOCUS_START, start)
                .putInt(K_FOCUS_MIN, minutes)
                .putBoolean(K_FOCUS_ACTIVE, active).apply();
    }

    public void clearFocus() {
        sp.edit().putBoolean(K_FOCUS_ACTIVE, false).apply();
    }

    public boolean focusActive() {
        return sp.getBoolean(K_FOCUS_ACTIVE, false);
    }

    public long focusTodoId() {
        return sp.getLong(K_FOCUS_ID, 0);
    }

    public long focusStart() {
        return sp.getLong(K_FOCUS_START, 0);
    }

    public int focusMinutes() {
        return sp.getInt(K_FOCUS_MIN, 25);
    }

    public int focusLength() {
        return sp.getInt(K_FOCUS_LENGTH, 25);
    }

    public void setFocusLength(int v) {
        sp.edit().putInt(K_FOCUS_LENGTH, Math.max(5, Math.min(120, v))).apply();
    }

    // ── 通知栏快捷记账 ──

    /** 最近一次从通知里捕获到的账目（用来证明实时记账在工作）。 */
    public void setLastCapture(long at, String text) {
        sp.edit().putLong(K_LAST_CAP_AT, at).putString(K_LAST_CAP_TXT, text).apply();
    }

    public long lastCaptureAt() {
        return sp.getLong(K_LAST_CAP_AT, 0);
    }

    public String lastCaptureText() {
        return sp.getString(K_LAST_CAP_TXT, "");
    }

    /** 自动备份。 */
    public boolean autoBackup() {
        return sp.getBoolean(K_AUTO_BK, false);
    }

    public void setAutoBackup(boolean v) {
        sp.edit().putBoolean(K_AUTO_BK, v).apply();
    }

    public int autoBackupHours() {
        return sp.getInt(K_AUTO_BK_H, 24);
    }

    public void setAutoBackupHours(int h) {
        sp.edit().putInt(K_AUTO_BK_H, h).apply();
    }

    public long lastBackupAt() {
        return sp.getLong(K_LAST_BK, 0);
    }

    public void setLastBackupAt(long at) {
        sp.edit().putLong(K_LAST_BK, at).apply();
    }

    public boolean quickNotify() {
        return sp.getBoolean(K_QUICK_NOTIFY, false);
    }

    public void setQuickNotify(boolean v) {
        sp.edit().putBoolean(K_QUICK_NOTIFY, v).apply();
    }

    /** 皮肤风格：round 圆润 / standard 标准 / square 方正。 */
    public String style() {
        return sp.getString(K_STYLE, "standard");
    }

    /** 搜索历史：最近 8 条，新的排前面。 */
    public java.util.List<String> searchHistory() {
        String raw = sp.getString(K_SEARCH_HIST, "");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String s : raw.split("\\n")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    public void addSearchHistory(String q) {
        java.util.List<String> h = searchHistory();
        h.remove(q);
        h.add(0, q);
        while (h.size() > 8) {
            h.remove(h.size() - 1);
        }
        sp.edit().putString(K_SEARCH_HIST, String.join("\n", h)).apply();
    }

    public void clearSearchHistory() {
        sp.edit().remove(K_SEARCH_HIST).apply();
    }

    public void setStyle(String v) {
        sp.edit().putString(K_STYLE, v == null ? "standard" : v).apply();
    }

    /** 「长按可拖动」的提示只在第一次露面一次，避免反复打扰。 */
    public boolean dragHintShown() {
        return sp.getBoolean(K_DRAG_HINT, false);
    }

    public void setDragHintShown(boolean v) {
        sp.edit().putBoolean(K_DRAG_HINT, v).apply();
    }
}
