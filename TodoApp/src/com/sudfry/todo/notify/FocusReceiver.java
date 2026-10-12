package com.sudfry.todo.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 专注到点：结算这次专注，并弹通知。 */
public class FocusReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        FocusSession.commit(context, FocusSession.plannedMin(context));
    }
}
