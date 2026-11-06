package com.kooritea.fcmfix.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    private static String selfPackageName = "UNKNOWN";
    /**
     * 模块应用自身的包名。改动包名时请同步修改这里与 app/build.gradle 的 applicationId。
     */
    public static final String SELF_PACKAGE_NAME = "com.kooritea.fcmfix";

    protected final ClassLoader classLoader;
    public static Set<String> allowList = null;
    static final String TAG = "FcmFix";
    private static final HashMap<String, Object> config = new HashMap<>();

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static Boolean isInitReceiver = false;
    public static Boolean isBootComplete = false;
    private static Thread loadConfigThread = null;
    private static volatile boolean bootInitThreadStarted = false;
    private static volatile boolean bootInitInvoked = false;

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        instances.add(this);
        if (instances.size() == 1) {
            initContext(classLoader);
        } else if (context != null) {
            try {
                UserManager userManager = context.getSystemService(UserManager.class);
                if (userManager != null && userManager.isUserUnlocked()) {
                    onCanReadConfig();
                }
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    private static void initContext(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) {
                    if (context == null) {
                        context = (Context) methodHookParam.thisObject;
                        startBootInitThread();
                    }
                }
            });
        } catch (Throwable e) {
            printLog("hook ContextWrapper.attachBaseContext 失败: " + e.getMessage());
        }
    }

    /**
     * system_server 专用：主动获取系统上下文。
     * <p>
     * 现代 Xposed API 的 onSystemServerStarting 在 SystemServer.startBootstrapServices 时回调，
     * 此时系统 Application 的 attachBaseContext 早已执行完毕，initContext 里的 hook
     * 在 system_server 中可能永远不会触发，导致 isBootComplete/config 永远无法初始化。
     * 因此这里直接通过 ActivityThread 反射拿到系统上下文。
     */
    public static void initSystemServerContext(Context systemContext) {
        if (context != null || systemContext == null) {
            return;
        }
        context = systemContext;
        startBootInitThread();
    }

    /**
     * 等待用户解锁后执行模块初始化。启动早期 AMS/UserManager 可能尚未注册，
     * 因此带重试；同时尽早注册 ACTION_USER_UNLOCKED 接收器作为兜底。
     */
    private static void startBootInitThread() {
        if (bootInitThreadStarted) {
            return;
        }
        bootInitThreadStarted = true;
        new Thread(() -> {
            boolean receiverRegistered = false;
            while (true) {
                if (bootInitInvoked) {
                    return;
                }
                try {
                    UserManager userManager = context.getSystemService(UserManager.class);
                    if (userManager != null && userManager.isUserUnlocked()) {
                        callAllOnCanReadConfig();
                        return;
                    }
                } catch (Throwable ignored) {
                }
                if (!receiverRegistered) {
                    try {
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                        receiverRegistered = true;
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fcmfix-boot-init").start();
    }

    private static synchronized void callAllOnCanReadConfig() {
        if (bootInitInvoked) {
            return;
        }
        bootInitInvoked = true;
        initReceiver();
        if ("android".equals(getSelfPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(60000);
                    isBootComplete = true;
                    printLog("Boot Complete");
                } catch (Throwable e) {
                    printLog(e.getMessage());
                }
            }).start();
        } else {
            isBootComplete = true;
        }
        // 开机/解锁时立即加载一次配置，避免第一条 FCM 推送因配置未加载而被丢弃
        onUpdateConfig();
        for (XposedModule instance : instances) {
            try {
                instance.onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Throwable {
    }

    protected static void printLog(String text) {
        printLog(text, false);
    }

    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        Log.d(TAG, text);
        if (isDiagnosticsLog) {
            Intent log = new Intent("com.kooritea.fcmfix.log");
            log.putExtra("text", "[" + getSelfPackageName() + "]" + text);

            try {
                context.sendBroadcast(log);
            } catch (Throwable e) {
                XposedBridge.log("[fcmfix] [" + getSelfPackageName() + "]" + text);
            }
        } else {
            XposedBridge.log("[fcmfix] [" + getSelfPackageName() + "]" + text);
        }
    }

    protected void checkUserDeviceUnlockAndUpdateConfig() {
        if (context == null) {
            return;
        }
        try {
            UserManager userManager = context.getSystemService(UserManager.class);
            if (userManager != null && userManager.isUserUnlocked()) {
                onUpdateConfig();
            }
        } catch (Throwable e) {
            printLog("更新配置文件失败: " + e.getMessage());
        }
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Throwable ignored) {
                }
                callAllOnCanReadConfig();
            }
        }
    };

    protected boolean targetIsAllow(String packageName) {
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if (SELF_PACKAGE_NAME.equals(packageName)) {
            return true;
        }
        if (allowList != null) {
            return allowList.contains(packageName);
        }
        return false;
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        if (config.get("init") == null) {
            return defaultValue;
        }
        Object value = config.get(key);
        return value == null ? defaultValue : (Boolean) value;
    }

    protected static void onUpdateConfig() {
        if (loadConfigThread == null) {
            loadConfigThread = new Thread() {
                @Override
                public void run() {
                    super.run();
                    try {
                        loadConfigFromRemotePreferences();
                    } catch (Throwable e) {
                        printLog("通过现代Xposed API读取配置失败: " + e.getMessage());
                        try {
                            loadConfigFromContentProvider();
                        } catch (Throwable e2) {
                            printLog("通过ContentProvider读取配置失败: " + e2.getMessage());
                        }
                    }
                    loadConfigThread = null;
                }
            };
            loadConfigThread.start();
        }
    }

    private static void loadConfigFromRemotePreferences() {
        SharedPreferences remotePreferences = XposedBridge.getRemotePreferences("config");
        if (remotePreferences == null) {
            throw new IllegalStateException("remotePreferences 不可用");
        }
        if (!remotePreferences.getBoolean("init", false)) {
            // 远端配置尚未写入过，回退到 ContentProvider 直接读取模块应用
            throw new IllegalStateException("remotePreferences 未初始化");
        }
        allowList = remotePreferences.getStringSet("allowList", new HashSet<>());
        config.put("disableAutoCleanNotification", remotePreferences.getBoolean("disableAutoCleanNotification", false));
        config.put("includeIceBoxDisableApp", remotePreferences.getBoolean("includeIceBoxDisableApp", false));
        config.put("noResponseNotification", remotePreferences.getBoolean("noResponseNotification", false));
        config.put("init", true);
        if ("android".equals(getSelfPackageName())) {
            printLog("[RemotePreferences]onUpdateConfig allowList size: " + allowList.size());
        }
    }

    @SuppressLint("Range")
    private static void loadConfigFromContentProvider() throws Throwable {
        if (context == null) {
            throw new IllegalStateException("context 不可用");
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://" + SELF_PACKAGE_NAME + ".provider/config"),
                    null, "all", null, null);
            if (cursor == null || cursor.getCount() == 0) {
                throw new IllegalStateException("provider 无数据");
            }
            Set<String> allowListTmp = new HashSet<>();
            boolean init = false;
            boolean disableAutoCleanNotification = false;
            boolean includeIceBoxDisableApp = false;
            boolean noResponseNotification = false;
            cursor.moveToFirst();
            do {
                String key = cursor.getString(cursor.getColumnIndex("key"));
                String value = cursor.getString(cursor.getColumnIndex("value"));
                if ("allowList".equals(key)) {
                    allowListTmp.add(value);
                } else if ("init".equals(key)) {
                    init = "1".equals(value);
                } else if ("disableAutoCleanNotification".equals(key)) {
                    disableAutoCleanNotification = "1".equals(value);
                } else if ("includeIceBoxDisableApp".equals(key)) {
                    includeIceBoxDisableApp = "1".equals(value);
                } else if ("noResponseNotification".equals(key)) {
                    noResponseNotification = "1".equals(value);
                }
            } while (cursor.moveToNext());
            if (!init) {
                throw new IllegalStateException("provider 未初始化");
            }
            allowList = allowListTmp;
            config.put("disableAutoCleanNotification", disableAutoCleanNotification);
            config.put("includeIceBoxDisableApp", includeIceBoxDisableApp);
            config.put("noResponseNotification", noResponseNotification);
            config.put("init", true);
            if ("android".equals(getSelfPackageName())) {
                printLog("[ContentProvider]onUpdateConfig allowList size: " + allowList.size());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static void onUninstallFcmfix() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmfix");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void initReceiver() {
        if (!isInitReceiver && context != null) {
            isInitReceiver = true;

            IntentFilter updateConfigIntentFilter = new IntentFilter();
            updateConfigIntentFilter.addAction("com.kooritea.fcmfix.update.config");
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                            // 輸出 hook 安裝狀態，便於通過 adb 廣播實時檢查
                            printLog("BroadcastFix " + BroadcastFix.getHookStatus(), true);
                        }
                    }
                }, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(new BroadcastReceiver() {
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if ("com.kooritea.fcmfix.update.config".equals(action)) {
                            onUpdateConfig();
                            printLog("BroadcastFix " + BroadcastFix.getHookStatus(), true);
                        }
                    }
                }, updateConfigIntentFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter();
            unInstallIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && SELF_PACKAGE_NAME.equals(intent.getData().getSchemeSpecificPart())) {
                        Bundle extras = intent.getExtras();
                        if (extras.containsKey(Intent.EXTRA_REPLACING) && extras.getBoolean(Intent.EXTRA_REPLACING)) {
                            return;
                        }
                        onUninstallFcmfix();
                        if ("android".equals(getSelfPackageName())) {
                            printLog("Fcmfix已卸载，重启后停止生效。");
                        }
                    }
                }
            }, unInstallIntentFilter);
        }

    }

    protected void sendNotification(String title) {
        sendNotification(title, null, null);
    }

    protected void sendNotification(String title, String content) {
        sendNotification(title, content, null);
    }

    @SuppressLint("MissingPermission")
    protected void sendNotification(String title, String content, PendingIntent pendingIntent) {
        printLog(title, false);
        title = "[fcmfix]" + title;
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        this.createFcmfixChannel(notificationManager);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmfix")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pendingIntent != null) {
            notification.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        notificationManager.notify((int) System.currentTimeMillis(), notification.build());
    }

    protected void createFcmfixChannel(NotificationManagerCompat notificationManager) {
        if (notificationManager.getNotificationChannel("fcmfix") == null) {
            NotificationChannel channel = new NotificationChannel("fcmfix", "fcmfix", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmfix");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected boolean isFCMAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE") ||
                "com.google.firebase.MESSAGING_EVENT".equals(action) ||
                "com.google.firebase.INSTANCE_ID_EVENT".equals(action));
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
