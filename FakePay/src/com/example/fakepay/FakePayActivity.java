package com.example.fakepay;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;

/**
 * 测试用：以「微信」/「支付宝」的包名发几条支付通知，
 * 用来验证日簿的通知监听能不能正确识别。
 * 代码里刻意不引用 R，这样同一份源码可以用不同包名编译两次。
 */
public class FakePayActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            finish();
            return;
        }
        String ch = "fakepay";
        nm.createNotificationChannel(new NotificationChannel(ch, "支付通知",
                NotificationManager.IMPORTANCE_HIGH));

        post(nm, ch, 1, "微信支付", "向 星巴克(武林店) 付款 ¥32.00");
        post(nm, ch, 2, "微信支付", "收款到账通知 ¥88.00");
        post(nm, ch, 3, "张三", "晚上一起吃饭吗");
        post(nm, ch, 4, "支付宝", "支付成功 15.80元");
        post(nm, ch, 5, "支付宝", "退款到账 ￥9.90 已退回零钱");

        // 留个通知在栏里，然后退出
        finish();
    }

    private void post(NotificationManager nm, String ch, int id, String title, String text) {
        Notification n = new Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .build();
        nm.notify(id, n);
    }
}
