package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    private static String selfPackageName = "UNKNOWN";

    protected final ClassLoader classLoader;
    public static Set<String> allowList = null;
    static final String TAG = "fcmself";
    private static final HashMap<String, Object> config = new HashMap<>();

    /** Min interval between diagnostic broadcast logs (ms) — reduces IPC storm */
    private static final long DIAG_LOG_MIN_INTERVAL_MS = 200L;
    private static final AtomicLong lastDiagLogElapsed = new AtomicLong(0);

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static Boolean isInitReceiver = false;
    public static Boolean isBootComplete = false;
    private static Thread loadConfigThread = null;

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        instances.add(this);
        if (instances.size() == 1) {
            initContext(classLoader);
        } else if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    private static void initContext(final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) {
                if (context == null) {
                    context = (Context) methodHookParam.thisObject;
                    if (context.getSystemService(UserManager.class).isUserUnlocked()) {
                        callAllOnCanReadConfig();
                    } else {
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                    }
                }
            }
        });
    }

    private static void callAllOnCanReadConfig() {
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
            }, "fcmself-boot").start();
        } else {
            isBootComplete = true;
        }
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

    /**
     * @param isDiagnosticsLog when true, also mirrors to GMS diagnostics via broadcast (throttled).
     */
    protected static void printLog(String text, Boolean isDiagnosticsLog) {
        if (text == null) {
            return;
        }
        if (Boolean.TRUE.equals(isDiagnosticsLog)) {
            // Diagnostics path: throttle IPC; still keep a local log line
            long now = SystemClock.elapsedRealtime();
            long prev = lastDiagLogElapsed.get();
            if (now - prev >= DIAG_LOG_MIN_INTERVAL_MS && lastDiagLogElapsed.compareAndSet(prev, now)) {
                String msg = "[" + getSelfPackageName() + "]" + text;
                Log.d(TAG, msg);
                if (context != null) {
                    try {
                        Intent log = new Intent("sumicya.fcmself.log");
                        log.putExtra("text", msg);
                        context.sendBroadcast(log);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
                XposedBridge.log("[fcmself] " + msg);
            } else {
                // Dropped by throttle — still log locally at verbose level only
                Log.v(TAG, text);
            }
        } else {
            Log.d(TAG, text);
            XposedBridge.log("[fcmself] [" + getSelfPackageName() + "]" + text);
        }
    }

    protected void checkUserDeviceUnlockAndUpdateConfig() {
        if (context != null && context.getSystemService(UserManager.class).isUserUnlocked()) {
            try {
                onUpdateConfig();
            } catch (Throwable e) {
                printLog("更新配置文件失败: " + e.getMessage());
            }
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
        if (packageName == null) {
            return false;
        }
        if ("sumicya.fcmself".equals(packageName)) {
            return true;
        }
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
        }
        Set<String> list = allowList;
        return list != null && list.contains(packageName);
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        if (config.get("init") == null) {
            this.checkUserDeviceUnlockAndUpdateConfig();
            if (config.get("init") == null) {
                return defaultValue;
            }
        }
        Object value = config.get(key);
        return value == null ? defaultValue : (Boolean) value;
    }

    protected static void onUpdateConfig() {
        if (loadConfigThread == null) {
            loadConfigThread = new Thread(() -> {
                try {
                    SharedPreferences remotePreferences = XposedBridge.getRemotePreferences("config");
                    if (remotePreferences == null) {
                        throw new IllegalStateException("remotePreferences 不可用");
                    }
                    Set<String> loaded = remotePreferences.getStringSet("allowList", null);
                    if (loaded != null) {
                        // Snapshot into a new HashSet so callers see a stable, non-shared instance
                        allowList = new HashSet<>(loaded);
                    } else if (allowList == null) {
                        allowList = new HashSet<>();
                    }
                    if ("android".equals(getSelfPackageName())) {
                        printLog("[Modern Xposed API]onUpdateConfig allowList size: " + allowList.size());
                    }
                    config.put("disableAutoCleanNotification", remotePreferences.getBoolean("disableAutoCleanNotification", false));
                    config.put("includeIceBoxDisableApp", remotePreferences.getBoolean("includeIceBoxDisableApp", false));
                    config.put("noResponseNotification", remotePreferences.getBoolean("noResponseNotification", false));
                    config.put("init", true);
                } catch (Throwable e) {
                    printLog("通过现代Xposed API读取配置失败: " + e.getMessage());
                } finally {
                    loadConfigThread = null;
                }
            }, "fcmself-config");
            loadConfigThread.start();
        }
    }

    private static void onUninstallFcmself() {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmself");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void initReceiver() {
        if (!isInitReceiver && context != null) {
            isInitReceiver = true;

            IntentFilter updateConfigIntentFilter = new IntentFilter();
            updateConfigIntentFilter.addAction("sumicya.fcmself.update.config");
            BroadcastReceiver configReceiver = new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    if ("sumicya.fcmself.update.config".equals(intent.getAction())) {
                        onUpdateConfig();
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(configReceiver, updateConfigIntentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(configReceiver, updateConfigIntentFilter);
            }

            IntentFilter unInstallIntentFilter = new IntentFilter();
            unInstallIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            unInstallIntentFilter.addDataScheme("package");
            context.registerReceiver(new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                            && intent.getData() != null
                            && "sumicya.fcmself".equals(intent.getData().getSchemeSpecificPart())) {
                        Bundle extras = intent.getExtras();
                        if (extras != null && extras.containsKey(Intent.EXTRA_REPLACING)
                                && extras.getBoolean(Intent.EXTRA_REPLACING)) {
                            return;
                        }
                        onUninstallFcmself();
                        if ("android".equals(getSelfPackageName())) {
                            printLog("fcmself已卸载，重启后停止生效。");
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
        title = "[fcmself]" + title;
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        this.createFcmselfChannel(notificationManager);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmself")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pendingIntent != null) {
            notification.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        notificationManager.notify((int) System.currentTimeMillis(), notification.build());
    }

    protected void createFcmselfChannel(NotificationManagerCompat notificationManager) {
        if (notificationManager.getNotificationChannel("fcmself") == null) {
            NotificationChannel channel = new NotificationChannel("fcmself", "fcmself", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmself");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected boolean isFCMAction(String action) {
        return action != null && (action.endsWith(".android.c2dm.intent.RECEIVE")
                || "com.google.firebase.MESSAGING_EVENT".equals(action)
                || "com.google.firebase.INSTANCE_ID_EVENT".equals(action));
    }

    protected boolean isFCMIntent(Intent intent) {
        return intent != null && isFCMAction(intent.getAction());
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
