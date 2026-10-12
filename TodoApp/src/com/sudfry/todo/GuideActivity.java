package com.sudfry.todo;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sudfry.todo.ui.Widgets;
import com.sudfry.todo.util.Sfx;
import com.sudfry.todo.util.Theme;
import com.sudfry.todo.util.Ui;

/**
 * 使用教程。
 *
 * 放在设置里，一条条对着功能讲「怎么用、为什么这么设计」，
 * 不写营销话术，只写点下去会发生什么。
 */
public class GuideActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        Ui.pressAll(getWindow().getDecorView());
    }

    private void tap(View v) {
        Sfx.tap(v);
    }

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
        TextView t = Ui.text(this, "使用教程", 16f, R.color.text, true);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);
        bar.addView(Ui.text(this, "     ", 15f, R.color.accent));
        col.addView(bar);
        col.addView(Widgets.divider(this));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        Ui.padDp(root, this, 14, 12, 14, 40);
        sv.addView(root);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        intro(root);
        todo(root);
        calendar(root);
        money(root);
        realtime(root);
        data(root);
        faq(root);
        return col;
    }

    // ───────────────── 内容 ─────────────────

    private TextView head(String s) {
        TextView t = Ui.text(this, s, 13f, R.color.text, true);
        t.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), 0, Ui.dp(this, 8));
        return t;
    }

    private View card(String title, String... lines) {
        LinearLayout c = Widgets.card(this);
        c.addView(Ui.text(this, title, 14.5f, R.color.text, true));
        for (String l : lines) {
            boolean bullet = l.startsWith("·") || l.startsWith("　");
            TextView t = Ui.text(this, l, 12.5f, R.color.text_dim);
            t.setLineSpacing(Ui.dp(this, 4), 1f);
            t.setPadding(0, Ui.dp(this, 7), 0, 0);
            if (!bullet) {
                t.setTextColor(Ui.color(this, R.color.text));
            }
            c.addView(t);
        }
        return c;
    }

    private void intro(LinearLayout root) {
        root.addView(head("30 秒上手"));
        root.addView(card("这个应用由四块组成",
                "· 今天：今天要处理什么、花了多少钱",
                "· 日历：月视图 / 周视图，看这一周怎么排",
                "· 待办：全部任务的列表，或按「重要 × 紧急」分四格",
                "· 账目：记一笔、流水、月度结算、分类分析",
                "右下角的 ＋ 是「加一条」，在哪个页面就加对应的东西。"));
        root.addView(card("最短路径",
                "1. 右下角 ＋ → 写一句「明天下午3点开会 重要」→ 保存",
                "   时间、日期、优先级会自动认出来，不认也不会改坏你的标题。",
                "2. 回到今天，就能看到它按紧急度排好了。",
                "3. 点一下勾掉，或者往右看时间轴上的色块。"));
    }

    private void todo(LinearLayout root) {
        root.addView(head("待办"));
        root.addView(card("加与改",
                "· 点右下角 ＋ 新建；点任务打开编辑",
                "· 编辑页里能设：分类、截止时间、时长、优先级、重复、提醒、子任务",
                "· 「子任务」把一件事拆成几步，任务行上会出现进度条和 ☑ 2/5",
                "· 标题里直接写时间也行：「周五交周报 ！」"));
        root.addView(card("长按一条任务会弹出什么",
                "· 编辑 / 标记完成",
                "· 开始专注：番茄钟，可选 15/25/45/60 分钟，到点提醒并把实际时长记进任务",
                "· 推迟到明天 / 推迟 1 小时 / 复制一条",
                "· 删除：删完底部会出现「撤销」，8 秒内可以点回来"));
        root.addView(card("紧急度是怎么来的",
                "· 五档颜色：已逾期红、今天橙、临近黄、以后绿、无期限灰",
                "· 完全按截止时间算，不靠你手动标",
                "· 颜色可以在设置里换方案（标准/柔和/高对比/色盲友好），"
                        + "但每档的含义不会变 —— 换肤不能把「哪件事最急」换没了。"));
    }

    private void calendar(LinearLayout root) {
        root.addView(head("日历与拖拽排期"));
        root.addView(card("月 / 周两种看法",
                "· 月视图：整月色条看紧急度、红绿点看当天收支",
                "· 周视图：7 列 × 小时的网格，一眼看出这周哪天扎堆"));
        root.addView(card("怎么拖动改时间",
                "· **长按**时间轴或周视图上的色块，等它振动一下再拖",
                "· 拖动时旁边会显示落点（例如「周日 12:15」），松手即改",
                "· 为什么必须长按：直接按住就拖会和「上下滚动页面」抢手势，"
                        + "没长按就滑动 = 正常滚动，不会误拖",
                "· 时间轴吸附 5 分钟，周视图吸附 15 分钟，可跨天"));
        root.addView(card("在空白处点一下",
                "· 时间轴上点空白 = 按那个时刻建任务",
                "· 周视图上点空白 = 那天的那个时间建任务"));
    }

    private void money(LinearLayout root) {
        root.addView(head("记账"));
        root.addView(card("四个子页",
                "· 记一笔：数字键盘 + 常用分类，最快三下记完",
                "· 流水：按天分组，点任意一条进完整编辑（金额、分类、日期、支付方式、备注都能改）",
                "· 月度结算：这个月花了多少、和上月比、负债与固定支出",
                "· 分类分析：大类小类的占比与趋势"));
        root.addView(card("预算与提醒",
                "· 设置里能设每日预算和月生活费，今天页会显示「还能花多少」",
                "· 超支会变红并提示"));
        root.addView(card("删除也能撤销",
                "· 流水里长按一条 → 删除 → 底部出现「撤销」",
                "· 真实删除前还有一次确认，确认框里会显示这条的日期和金额"));
    }

    private void realtime(LinearLayout root) {
        root.addView(head("实时记账（通知自动入账）"));
        root.addView(card("它怎么工作",
                "· 开启「通知使用权」后，支付宝/微信弹出的支付通知会被读取并解析成待确认账目",
                "· 只读通知文本，不读聊天内容，也不需要 root",
                "· 识别到的会**先进入待确认**，你确认了才写进账本 —— 自动记账也要对得上账",
                "· 只能拿到开启之后的新通知，历史记录要用下面的账单导入"));
        root.addView(card("怎么确认它真的在工作",
                "· 账单导入页的卡片会显示：授权状态 · 服务连接状态 · 最近一次捕获",
                "· 点「自检解析」可以拿 7 条真实样式的通知跑一遍，看判断对不对",
                "· 聊天消息会被正确排除（判为「不计入」）"));
        root.addView(card("历史账单导入",
                "· 微信：我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账",
                "  （给的是带密码的 zip，密码在「微信支付」公众号消息里）",
                "· 支付宝：我的 → 账单 → … → 开具交易流水证明 → 用于个人对账",
                "· 编码和列顺序都不一样，应用会自动识别（支付宝 GBK、微信 UTF-8）",
                "· 重复导入不会重复记账（按交易单号判重），导错了可以整批撤销"));
    }

    private void data(LinearLayout root) {
        root.addView(head("数据"));
        root.addView(card("数据在哪",
                "· 全部存在本机，不注册账号、不上传服务器",
                "· 局域网同步也只是同一个 Wi-Fi 下点对点传输"));
        root.addView(card("多设备传数据",
                "· 设置 → 数据与同步：文件、剪贴板、或同一 Wi-Fi 局域网直传（4 位配对码）",
                "· 合并规则是「同一条以新的为准」，所以两边都改过也不会互相覆盖",
                "· 注意：删除不会同步 —— 同步只做增加和更新"));
        root.addView(card("备份与恢复",
                "· 设置 → 备份与恢复：可以立即备份、导出成文件、从文件导入",
                "· 开启自动备份后，按你选的间隔（6 小时 ~ 每周）自动存一份，只保留最近 12 份",
                "· 导入默认是「合并」（只增不减）；想彻底还原可以选「覆盖恢复」",
                "· 导入也认旧版本单独导出的数据文件，换手机、升级都不会丢"));
    }

    private void faq(LinearLayout root) {
        root.addView(head("常见问题"));
        root.addView(card("点了按钮页面会重置吗",
                "· 不会。切筛选、勾分类、换配色、切风格都是就地更新，滚动位置也会记住",
                "· 横屏下左右两栏的滚动位置分别记忆"));
        root.addView(card("提醒收不到",
                "· 先看设置 → 提醒里是否开着，以及系统有没有给「通知」权限",
                "· Android 12 以上还要允许「闹钟与提醒」，否则只能给个大致时间"));
        root.addView(card("为什么有些事要手动开权限",
                "· 通知使用权、精确闹钟、通知权限都属于系统敏感权限，"
                        + "应用不能自己开，也不会绕过 —— 该你点的那一下必须你点。"));
        root.addView(card("想换配色 / 换风格",
                "· 设置 → 外观：主题色改强调色（色相 + 饱和度），"
                        + "整体风格改圆角与阴影（圆润 / 标准 / 方正）",
                "· 两层互不干扰：换肤不会改变任何一档紧急度颜色的含义。"));
    }
}
