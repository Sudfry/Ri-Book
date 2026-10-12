package com.sudfry.todo.util;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 纯代码建界面用到的小工具。全部用系统控件，不依赖任何第三方库。 */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static boolean night(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int color(Context c, int res) {
        return c.getColor(res);
    }

    public static GradientDrawable bg(int fill, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable bgStroke(int fill, float radiusPx, int strokeColor, float strokePx) {
        GradientDrawable d = bg(fill, radiusPx);
        if (strokePx > 0) {
            d.setStroke(Math.max(1, Math.round(strokePx)), strokeColor);
        }
        return d;
    }

    public static GradientDrawable pill(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(999f);
        return d;
    }

    /** 只有上面两个角是圆的（底部面板用）。 */
    public static GradientDrawable bgTopRounded(int fill, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return d;
    }

    public static TextView text(Context c, CharSequence s, float sizeSp, int colorRes) {
        return text(c, s, sizeSp, colorRes, false);
    }

    public static TextView text(Context c, CharSequence s, float sizeSp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color(c, colorRes));
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 与 text(...) 相同，但第 4 个参数直接就是颜色值（不是资源 id）。 */
    public static TextView textColored(Context c, CharSequence s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View space(Context c, int widthPx, int heightPx) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(widthPx, heightPx));
        return v;
    }

    public static LinearLayout.LayoutParams lpw(int w, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.weight = weight;
        return p;
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    public static void pad(View v, int l, int t, int r, int b) {
        v.setPadding(l, t, r, b);
    }

    public static void padDp(View v, Context c, float l, float t, float r, float b) {
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    // ───────────────── 动画小工具 ─────────────────

    /** 按下去轻微缩小，松开弹回 —— 让点击有「手感」。 */
    public static void press(View v) {
        if (v == null) {
            return;
        }
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.86f)
                            .setDuration(90)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                            .start();
                    break;
                default:
                    break;
            }
            return false;   // 不拦截，点击照常生效
        });
    }

    /**
     * 给一整棵树里「能点的东西」统一加上按压反馈。
     *
     * 与其在每个按钮上手动调 press()，不如建完界面扫一遍 ——
     * 这样新加的按钮天然就有反馈，不会再出现「有的有、有的没有」。
     */
    public static void pressAll(View root) {
        if (root == null) {
            return;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                pressAll(g.getChildAt(i));
            }
        }
        if (!root.isClickable() || root.isLongClickable()) {
            return;   // 长按有别的语义（菜单），别抢
        }
        // 输入框、滚动容器、进度条不缩放，缩了反而怪
        if (root instanceof android.widget.EditText
                || root instanceof android.widget.ScrollView
                || root instanceof android.widget.HorizontalScrollView
                || root instanceof android.widget.ProgressBar
                || root instanceof android.widget.ImageView) {
            return;
        }
        press(root);
    }

    /** 淡入 + 轻微上移，用于列表/页面内容出现。 */
    public static void enter(View v) {
        enter(v, 0);
    }

    public static void enter(View v, int delayMs) {
        if (v == null) {
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 12));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delayMs).setDuration(230)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    /** 从下方滑入（撤销条、面板）。 */
    public static void slideUp(View v, float fromDp) {
        if (v == null) {
            return;
        }
        v.setTranslationY(dp(v.getContext(), fromDp));
        v.setAlpha(0.6f);
        v.animate().translationY(0f).alpha(1f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }
}
