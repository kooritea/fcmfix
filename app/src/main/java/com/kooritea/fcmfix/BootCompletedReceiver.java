package com.kooritea.fcmfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("fcmfix", "Boot completed, notify hooked processes to reload config");
        // 开机后通知 system_server / GMS 中的 hook 重新加载配置文件
        try {
            context.sendBroadcast(new Intent("com.kooritea.fcmfix.update.config"));
        } catch (Throwable e) {
            Log.e("fcmfix", "send update config broadcast failed: " + e.getMessage());
        }
    }
}
