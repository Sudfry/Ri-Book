package com.sudfry.todo.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sudfry.todo.R;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 自绘的底部面板与确认框。
 *
 * 系统 PopupMenu / AlertDialog 的样式和应用本身差得远（方角、灰色、没有层级感），
 * 这里统一成：圆角面板 + 图标方块 + 危险操作红色 + 蒙层 + 上滑动画。
 */
public final class Sheet {

    private Sheet() {
    }

    public static class Item {
        public final String glyph;
        public final String label;
        public final String hint;
        public final boolean danger;
        public final Runnable action;

        public Item(String glyph, String label, String hint, boolean danger, Runnable action) {
            this.glyph = glyph;
            this.label = label;
            this.hint = hint;
            this.danger = danger;
            this.action = action;
        }
    }

    private static FrameLayout overlayOf(Activity act) {
        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) {
            return null;
        }
        FrameLayout overlay = new FrameLayout(act);
        overlay.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setClickable(true);
        content.addView(overlay);
        return overlay;
    }

    private static View backdrop(Activity act, final FrameLayout overlay) {
        View dim = new View(act);
        dim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim.setBackgroundColor(0xB3000000);
        dim.setAlpha(0f);
        dim.setOnClickListener(v -> dismiss(overlay));
        overlay.addView(dim);
        dim.animate().alpha(1f).setDuration(180).start();
        return dim;
    }

    private static void dismiss(FrameLayout overlay) {
        if (overlay == null || overlay.getParent() == null) {
            return;
        }
        View child = overlay.getChildAt(overlay.getChildCount() > 1 ? 1 : 0);
        if (child != null && child.getTranslationY() < child.getHeight()) {
            child.animate().translationY(child.getHeight())
                    .setDuration(160).start();
        }
        overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) {
                parent.removeView(overlay);
            }
        }).start();
    }

    private static float d(Activity a) {
        return a.getResources().getDisplayMetrics().density;
    }

    // ───────────────────── 长按菜单 ─────────────────────

    public static void showMenu(Activity act, String title, String subtitle, List<Item> items) {
        final FrameLayout overlay = overlayOf(act);
        if (overlay == null) {
            return;
        }
        backdrop(act, overlay);

        LinearLayout panel = Ui.column(act);
        panel.setBackground(Ui.bgTopRounded(Ui.color(act, R.color.surface), Theme.panelRadius(act)));
        panel.setClickable(true);
        Ui.padDp(panel, act, 12, 10, 12, 14);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        panel.setLayoutParams(lp);

        // 拖动条
        View handle = new View(act);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Ui.dp(act, 38), Ui.dp(act, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = Ui.dp(act, 2);
        hlp.bottomMargin = Ui.dp(act, 10);
        handle.setLayoutParams(hlp);
        handle.setBackground(Ui.pill(Ui.color(act, R.color.divider)));
        panel.addView(handle);

        if (title != null && !title.isEmpty()) {
            TextView t = Ui.text(act, title, 16f, R.color.text, true);
            t.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 6), 0);
            panel.addView(t);
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = Ui.text(act, subtitle, 12.5f, R.color.text_dim);
            s.setPadding(Ui.dp(act, 6), Ui.dp(act, 4), Ui.dp(act, 6), Ui.dp(act, 10));
            s.setSingleLine(true);
            panel.addView(s);
        } else {
            panel.addView(space(act, 8));
        }

        for (Item it : items) {
            panel.addView(row(act, overlay, it));
        }

        TextView cancel = Ui.text(act, "取消", 15f, R.color.text_dim, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Ui.bgStroke(Ui.color(act, R.color.surface_alt), 14 * d(act),
                Ui.color(act, R.color.divider), d(act)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48));
        clp.topMargin = Ui.dp(act, 10);
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(v -> {
            Sfx.tap(v);
            dismiss(overlay);
        });
        panel.addView(cancel);

        Ui.pressAll(panel);
        overlay.addView(panel);
        panel.post(() -> {
            panel.setTranslationY(panel.getHeight());
            panel.animate().translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    private static View row(Activity act, final FrameLayout overlay, final Item it) {
        float d = d(act);
        int accent = Theme.accent(act);
        int color = it.danger ? Ui.color(act, R.color.overdue) : accent;

        LinearLayout r = Ui.row(act);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 54));
        r.setLayoutParams(rlp);
        r.setBackground(Ui.bgStroke(Ui.color(act, R.color.surface), Theme.rowRadius(act),
                Ui.color(act, R.color.surface), 0));
        Ui.padDp(r, act, 8, 4, 8, 4);

        // 图标方块
        TextView icon = Ui.textColored(act, it.glyph, 16f, color, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.bg(it.danger
                ? Ui.color(act, R.color.surface_alt) : Theme.accentSoft(act), 11 * d));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(act, 36), Ui.dp(act, 36));
        ilp.rightMargin = Ui.dp(act, 12);
        icon.setLayoutParams(ilp);
        r.addView(icon);

        LinearLayout txt = Ui.column(act);
        txt.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        txt.addView(Ui.textColored(act, it.label, 15f, color, true));
        if (it.hint != null && !it.hint.isEmpty()) {
            TextView h = Ui.text(act, it.hint, 11.5f, R.color.text_dim);
            h.setPadding(0, Ui.dp(act, 2), 0, 0);
            txt.addView(h);
        }
        r.addView(txt);

        Ui.press(r);
        r.setOnClickListener(v -> {
            Sfx.tap(v);
            dismiss(overlay);
            if (it.action != null) {
                v.postDelayed(it.action, 160);
            }
        });
        return r;
    }

    private static View space(Activity act, int dp) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(act, dp)));
        return v;
    }

    // ───────────────────── 确认框 ─────────────────────

    public static void confirm(Activity act, String title, String message,
                               String okLabel, boolean danger, final Runnable onOk) {
        confirm(act, title, message, null, okLabel, danger, onOk);
    }

    /** preview 会以「引用块」样式显示，用来提示到底要删哪一条。 */
    public static void confirm(Activity act, String title, String message, String preview,
                               String okLabel, boolean danger, final Runnable onOk) {
        final FrameLayout overlay = overlayOf(act);
        if (overlay == null) {
            return;
        }
        backdrop(act, overlay);

        LinearLayout card = Ui.column(act);
        card.setBackground(Ui.bg(Ui.color(act, R.color.surface), Theme.panelRadius(act)));
        card.setClickable(true);
        Ui.padDp(card, act, 22, 20, 22, 16);
        // 注意：MATCH_PARENT 是 -1，不能直接丢进 Math.min，否则宽度会被拉满整屏
        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.min((int) (320 * d(act)), screenW - (int) (48 * d(act)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        card.setLayoutParams(lp);

        TextView t = Ui.text(act, title, 17f, R.color.text, true);
        card.addView(t);

        if (preview != null && !preview.isEmpty()) {
            TextView pv = Ui.text(act, preview, 13.5f, R.color.text);
            pv.setBackground(Ui.bg(Ui.color(act, R.color.surface_alt), 12 * d(act)));
            pv.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = Ui.dp(act, 12);
            pv.setLayoutParams(plp);
            card.addView(pv);
        }

        if (message != null && !message.isEmpty()) {
            TextView m = Ui.text(act, message, 13f, R.color.text_dim);
            m.setLineSpacing(Ui.dp(act, 4), 1f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = Ui.dp(act, 12);
            m.setLayoutParams(mlp);
            card.addView(m);
        }

        LinearLayout buttons = Ui.row(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 20);
        buttons.setLayoutParams(blp);

        TextView cancel = Ui.text(act, "取消", 15f, R.color.text_dim, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(Ui.bgStroke(Ui.color(act, R.color.surface_alt), 14 * d(act),
                Ui.color(act, R.color.divider), d(act)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, Ui.dp(act, 48), 1f);
        clp.rightMargin = Ui.dp(act, 6);
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(v -> {
            Sfx.tap(v);
            dismiss(overlay);
        });
        buttons.addView(cancel);

        int okColor = danger ? Ui.color(act, R.color.overdue) : Theme.accent(act);
        TextView ok = Ui.textColored(act, okLabel == null ? "确定" : okLabel,
                15f, 0xFFFFFFFF, true);
        ok.setGravity(Gravity.CENTER);
        ok.setBackground(Ui.bg(okColor, Theme.rowRadius(act)));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(0, Ui.dp(act, 48), 1f);
        olp.leftMargin = Ui.dp(act, 6);
        ok.setLayoutParams(olp);
        ok.setOnClickListener(v -> {
            Sfx.tap(v);
            dismiss(overlay);
            if (onOk != null) {
                v.postDelayed(onOk, 160);
            }
        });
        buttons.addView(ok);
        card.addView(buttons);

        Ui.pressAll(card);
        overlay.addView(card);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.setAlpha(0f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** 常用项构造。 */
    public static List<Item> items(Item... arr) {
        ArrayList<Item> list = new ArrayList<>();
        for (Item i : arr) {
            list.add(i);
        }
        return list;
    }

    public static Item item(String glyph, String label, String hint, boolean danger, Runnable r) {
        return new Item(glyph, label, hint, danger, r);
    }

    // ───────────────────── 撤销条 ─────────────────────

    private static View undoView;
    private static Runnable undoHide;
    private static final android.os.Handler SH = new android.os.Handler(
            android.os.Looper.getMainLooper());

    /**
     * 底部撤销条。
     *
     * 容器用的是 Activity 的 android.R.id.content —— 这是唯一能确定「铺满整屏」的那一层
     * （Sheet 的蒙层和居中卡片一直用它对位，实测是准的）。
     * 之前把撤销条挂在应用自己的 FrameLayout 里靠 gravity=bottom 定位，
     * 实测会跑到屏幕垂直中间，所以改成和 Sheet 共用同一套容器。
     */
    public static void showUndo(final Activity act, String msg, final Runnable onUndo) {
        final FrameLayout content = act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        dismissUndo();

        final FrameLayout host = new FrameLayout(act);
        host.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.setClipChildren(false);

        LinearLayout bar = Ui.row(act);
        bar.setBackground(Ui.bgStroke(0xF21F2229, Ui.dp(act, 14), 0x33FFFFFF, Ui.dp(act, 1)));
        bar.setElevation(Ui.dp(act, 10));
        Ui.padDp(bar, act, 16, 12, 10, 12);
        bar.addView(Ui.text(act, msg, 13.5f, R.color.on_color));
        bar.addView(new View(act), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView undo = Ui.text(act, "撤销", 14.5f, R.color.accent, true);
        undo.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        Ui.press(undo);
        undo.setOnClickListener(v -> {
            Sfx.tap(v);
            dismissUndo();
            if (onUndo != null) {
                v.postDelayed(onUndo, 120);
            }
        });
        bar.addView(undo);

        // 高度写死：撤销条这种一行高的东西，用 WRAP_CONTENT 会被 AT_MOST 撑成整屏高
        // （实测过：容器是全屏 FrameLayout 时，wrap_content 的子视图被量成满高，
        //  结果整条黑底盖住全屏、文字被垂直居中到屏幕中央）
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 54));
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.leftMargin = Ui.dp(act, 16);
        lp.rightMargin = Ui.dp(act, 16);
        lp.bottomMargin = Ui.dp(act, 24);
        host.addView(bar, lp);
        Ui.pressAll(bar);
        bar.setMinimumHeight(Ui.dp(act, 54));

        content.addView(host);
        undoView = host;
        bar.setAlpha(0f);
        bar.setTranslationY(Ui.dp(act, 26));
        bar.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();

        undoHide = Sheet::dismissUndo;
        SH.postDelayed(undoHide, 8000);
    }

    /** 收掉撤销条。 */
    public static void dismissUndo() {
        if (undoHide != null) {
            SH.removeCallbacks(undoHide);
            undoHide = null;
        }
        final View v = undoView;
        undoView = null;
        if (v != null && v.getParent() != null) {
            v.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                ViewGroup p = (ViewGroup) v.getParent();
                if (p != null) {
                    p.removeView(v);
                }
            }).start();
        }
    }
}
