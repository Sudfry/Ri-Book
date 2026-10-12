package com.sudfry.todo.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sudfry.todo.QuickEntryActivity;
import com.sudfry.todo.model.Entry;

/**
 * 接住常驻通知上「记支出 / 记收入」两个 action 的点击，把快速记账页拉起来。
 *
 * 为什么要走广播而不是直接用 PendingIntent.getActivity：
 *   广播这一层可以顺手做防抖和额外处理（例如连续点两下不重复开页），
 *   并且以后想在通知里直接入库、不经过界面时，不用改通知那边的代码。
 *
 * 注意：广播可能在任何进程状态下收到（应用被杀、锁屏），所以这里不能假设
 * 有 Activity 存在，启动 Intent 必须带 FLAG_ACTIVITY_NEW_TASK。
 */
public class QuickAddReceiver extends BroadcastReceiver {

    public static final String ACTION_QUICK_ADD = "com.sudfry.todo.action.QUICK_ADD";
    /** 传给记账页的收支类型 extra，与 QuickEntryActivity / QuickNotifier 保持一致。 */
    public static final String EXTRA_TYPE = "type";

    /** 防抖：忽略 600ms 内的重复点击（通知 action 手滑连点两次很常见）。 */
    private static final long DEBOUNCE_MS = 600;
    private static long lastAt;

    @Override
    public void onReceive(Context context, Intent intent) {
        int type = Entry.EXPENSE;
        if (intent != null) {
            type = intent.getIntExtra(EXTRA_TYPE, Entry.EXPENSE);
        }
        if (type != Entry.EXPENSE && type != Entry.INCOME) {
            type = Entry.EXPENSE;
        }

        long now = System.currentTimeMillis();
        if (now - lastAt < DEBOUNCE_MS) {
            return;
        }
        lastAt = now;

        Intent open = new Intent(context, QuickEntryActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        open.putExtra(EXTRA_TYPE, type);
        try {
            context.startActivity(open);
        } catch (Throwable ignored) {
            // 极少数 ROM 在后台禁止启动 Activity，静默失败即可（通知还在，用户可再点）
        }
    }
}
