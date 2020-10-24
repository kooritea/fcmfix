package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kooritea.fcmfix.R;
import com.kooritea.fcmfix.util.ContentProviderHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static android.content.Context.NOTIFICATION_SERVICE;

public class ReconnectManagerFix extends XposedModule {

    private Class<?> GcmChimeraService;


    public ReconnectManagerFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHookGcmServiceStart();
    }

    private void startHookGcmServiceStart() {
        this.GcmChimeraService = XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService", loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService", loadPackageParam.classLoader), "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction("com.kooritea.fcmfix.log");
                AndroidAppHelper.currentApplication().getApplicationContext().registerReceiver(logBroadcastReceive, intentFilter);
                if (AndroidAppHelper.currentApplication().getApplicationContext().getSystemService(UserManager.class).isUserUnlocked()) {
                    try {
                        startHook();
                        printLog("ReconnectManagerFixStartHook 完成");
                    } catch (Exception e) {
                        printLog("ReconnectManagerFixStartHook 初始化失败: " + e.getMessage());
                    }
                } else {
                    IntentFilter userUnlockIntentFilter = new IntentFilter();
                    userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                    AndroidAppHelper.currentApplication().getApplicationContext().registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                }
            }
        });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService", loadPackageParam.classLoader), "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                AndroidAppHelper.currentApplication().getApplicationContext().unregisterReceiver(logBroadcastReceive);
            }
        });

    }

    private void startHook() throws Exception {
        Context context = AndroidAppHelper.currentApplication().getApplicationContext();
        final SharedPreferences sharedPreferences = context.getSharedPreferences("fcmfix_config", Context.MODE_PRIVATE);
        String versionName = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        if (!sharedPreferences.getBoolean("isInit", false)) {
            this.printLog("fcmfix_config init");
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("isInit", true);
            editor.putBoolean("enable", false);
            editor.putLong("heartbeatInterval", 117000L);
            editor.putString("timer_class", "");
            editor.putString("timer_settimeout_method", "");
            editor.putString("timer_next_time_property", "");
            editor.putString("timer_intent_property", "");
            editor.putString("gms_version", versionName);
            editor.commit();
            this.sendUpdateNotification("[xposed-fcmfix]请初始化fcmfix配置");
            return;
        }
        if (!sharedPreferences.getBoolean("enable", false)) {
            this.printLog("ReconnectManagerFix配置已关闭");
            return;
        }
        if (!sharedPreferences.getString("gms_version", "").equals(versionName)) {
            this.printLog("gms已更新，请重新编辑fcmfix_config.xml");
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean("enable", false);
            editor.putString("gms_version", versionName);
            editor.commit();
            this.sendUpdateNotification("[xposed-fcmfix]gms已更新");
            return;
        }
        this.printLog("ReconnectManagerFix读取配置已成功,timer_class: " + sharedPreferences.getString("timer_class", ""));
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass(sharedPreferences.getString("timer_class", ""), loadPackageParam.classLoader), sharedPreferences.getString("timer_settimeout_method", ""), long.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                // 修改心跳间隔
                Intent intent = (Intent) XposedHelpers.getObjectField(param.thisObject, sharedPreferences.getString("timer_intent_property", ""));
                if ("com.google.android.gms.gcm.HEARTBEAT_ALARM".equals(intent.getAction())) {
                    param.args[0] = sharedPreferences.getLong("heartbeatInterval", 117000L);
                }
            }

            @Override
            protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                // 防止计时器出现负数计时,分别是心跳计时和重连计时
                Intent intent = (Intent) XposedHelpers.getObjectField(param.thisObject, sharedPreferences.getString("timer_intent_property", ""));
                if ("com.google.android.intent.action.GCM_RECONNECT".equals(intent.getAction()) || "com.google.android.gms.gcm.HEARTBEAT_ALARM".equals(intent.getAction())) {
                    new Timer("ReconnectManagerFix").schedule(new TimerTask() {
                        @Override
                        public void run() {
                            long nextConnectionTime = XposedHelpers.getLongField(param.thisObject, sharedPreferences.getString("timer_next_time_property", ""));
                            if (nextConnectionTime != 0 && nextConnectionTime - SystemClock.elapsedRealtime() < 0) {
                                AndroidAppHelper.currentApplication().getApplicationContext().sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                                printLog("Send broadcast GCM_RECONNECT");
                            }
                        }
                    }, (long) param.args[0] + 5000);
                }
            }
        });
    }

    private void sendUpdateNotification(String text) {
        Context context = AndroidAppHelper.currentApplication().getApplicationContext();
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, "fcmfix");
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle(text);
        // builder.setContentText("点击编辑fcmfix.xml");
        builder.setAutoCancel(true);
        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void createFcmfixChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            List<NotificationChannel> channelList = notificationManager.getNotificationChannels();
            for (NotificationChannel item : channelList) {
                if (item.getId() == "fcmfix") {
                    item.setName("fcmfix");
                    item.setImportance(NotificationManager.IMPORTANCE_HIGH);
                    item.setDescription("fcmfix");
                    return;
                }
            }
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix更新通知");
            notificationManager.createNotificationChannel(channel);
        }
    }

    private BroadcastReceiver logBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.kooritea.fcmfix.log".equals(action)) {
                XposedHelpers.callStaticMethod(GcmChimeraService, "a", new Class<?>[]{String.class, Object[].class}, "[fcmfix] " + intent.getStringExtra("text"), null);
            }
        }
    };

    private BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                printLog("UserUnlock Broadcast");
                try {
                    startHook();
                    printLog("ReconnectManagerFixStartHook 完成");
                    AndroidAppHelper.currentApplication().getApplicationContext().unregisterReceiver(unlockBroadcastReceive);
                } catch (Exception e) {
                    printLog("ReconnectManagerFixStartHook 初始化失败: " + e.getMessage());
                }
            }
        }
    };
}
