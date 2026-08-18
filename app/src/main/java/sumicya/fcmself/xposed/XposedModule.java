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
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import sumicya.fcmself.core.Contract;
import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static android.content.Context.NOTIFICATION_SERVICE;

public abstract class XposedModule {
    private static volatile String selfPackageName = "UNKNOWN";

    protected final ClassLoader classLoader;
    public static volatile Set<String> allowList = null;
    static final String TAG = "fcmself";
    // Wire protocol shared with the app process; see sumicya.fcmself.core.Contract.
    private static final String UPDATE_CONFIG_ACTION = Contract.UPDATE_CONFIG_ACTION;
    private static final String HEALTH_REQUEST_ACTION = Contract.HEALTH_REQUEST_ACTION;
    private static final String HEALTH_RESPONSE_ACTION = Contract.HEALTH_RESPONSE_ACTION;
    private static final String UPDATE_CONFIG_PERMISSION = Contract.UPDATE_CONFIG_PERMISSION;
    /** Atomically published so hook threads never observe a mixture of two config versions. */
    private static volatile ConfigSnapshot configSnapshot = ConfigSnapshot.EMPTY;

    private static final class ConfigSnapshot {
        static final ConfigSnapshot EMPTY = new ConfigSnapshot(
                Collections.emptySet(), false, false, false, false);

        final Set<String> allowList;
        final boolean disableAutoCleanNotification;
        final boolean includeIceBoxDisableApp;
        final boolean noResponseNotification;
        final boolean ready;

        ConfigSnapshot(
                Set<String> allowList,
                boolean disableAutoCleanNotification,
                boolean includeIceBoxDisableApp,
                boolean noResponseNotification,
                boolean ready) {
            this.allowList = allowList;
            this.disableAutoCleanNotification = disableAutoCleanNotification;
            this.includeIceBoxDisableApp = includeIceBoxDisableApp;
            this.noResponseNotification = noResponseNotification;
            this.ready = ready;
        }

        boolean getBoolean(String key, boolean defaultValue) {
            switch (key) {
                case Contract.KEY_DISABLE_AUTO_CLEAN:
                    return disableAutoCleanNotification;
                case Contract.KEY_INCLUDE_ICEBOX:
                    return includeIceBoxDisableApp;
                case Contract.KEY_NO_RESPONSE_NOTIFICATION:
                    return noResponseNotification;
                default:
                    return defaultValue;
            }
        }
    }

    /** Min interval between diagnostic broadcast logs (ms) — reduces IPC storm */
    private static final long DIAG_LOG_MIN_INTERVAL_MS = 200L;
    private static final AtomicLong lastDiagLogElapsed = new AtomicLong(0);

    /** Process-local Hook installation details, queried on demand by the app health panel. */
    private static final Map<String, String> HEALTH_DETAILS = new ConcurrentHashMap<>();

    /** True once callAllOnCanReadConfig() has run; dedupes the multiple context paths. */
    private static final AtomicBoolean configInitiated = new AtomicBoolean(false);

    /**
     * Monotonic notification ID shared by every fix. System.currentTimeMillis() overflows int in
     * 2038 and two modules notifying in the same millisecond could collide.
     */
    private static final AtomicInteger NOTIFY_ID_SEQ = new AtomicInteger();

    @SuppressLint("StaticFieldLeak")
    protected static Context context = null;
    /** Written while hooks install; read by callAllOnCanReadConfig — both must be synchronized. */
    private static final ArrayList<XposedModule> instances = new ArrayList<>();
    private static volatile boolean isInitReceiver = false;
    public static volatile boolean isBootComplete = false;
    /** Coalesce bursts without losing a refresh that arrives while a load is running. */
    private static final AtomicBoolean configLoadRunning = new AtomicBoolean(false);
    private static final AtomicBoolean configReloadPending = new AtomicBoolean(false);
    /** Reuse one worker instead of creating a new Thread after every settings change. */
    private static final ExecutorService CONFIG_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "fcmself-config");
        thread.setDaemon(true);
        return thread;
    });

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Registers a fully constructed module. The entry point calls this only after the constructor
     * returns, so config callbacks can never observe partially initialized hook state.
     */
    public final void activate() {
        final boolean first;
        synchronized (instances) {
            if (instances.contains(this)) {
                return;
            }
            instances.add(this);
            first = instances.size() == 1;
        }
        HEALTH_DETAILS.putIfAbsent(getClass().getSimpleName(), "模块已激活");
        if (first) {
            initContext(classLoader);
        } else if (context != null) {
            try {
                UserManager um = context.getSystemService(UserManager.class);
                if (um != null && um.isUserUnlocked()) {
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

    public static void reportHookHealth(String module, String status) {
        if (module != null && status != null) {
            HEALTH_DETAILS.put(module, status);
        }
    }

    private static void initContext(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ActivityThread", classLoader,
                    "attach", boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (context != null || !(param.args[0] instanceof Boolean)) {
                                return;
                            }
                            if (!((Boolean) param.args[0])) {
                                return;
                            }
                            try {
                                Object sysCtx = XposedHelpers.callMethod(param.thisObject, "getSystemContext");
                                if (sysCtx instanceof Context) {
                                    context = (Context) sysCtx;
                                    onContextAvailable();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader,
                    "attachBaseContext", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam methodHookParam) {
                            if (context == null && methodHookParam.thisObject instanceof Context) {
                                context = (Context) methodHookParam.thisObject;
                                onContextAvailable();
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static void onContextAvailable() {
        if (tryLoadConfigNow()) {
            return;
        }
        try {
            IntentFilter userUnlockIntentFilter = new IntentFilter();
            userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
            context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
        } catch (Throwable ignored) {
        }
        scheduleConfigRetry();
    }

    private static boolean tryLoadConfigNow() {
        try {
            UserManager um = context.getSystemService(UserManager.class);
            if (um != null && um.isUserUnlocked()) {
                callAllOnCanReadConfig();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void scheduleConfigRetry() {
        new Thread(() -> {
            try {
                Thread.sleep(5_000);
                if (context != null && tryLoadConfigNow()) {
                    return;
                }
                Thread.sleep(10_000);
                if (context != null) {
                    tryLoadConfigNow();
                }
            } catch (Throwable ignored) {
            }
        }, "fcmself-context").start();
    }

    private static void callAllOnCanReadConfig() {
        if (!configInitiated.compareAndSet(false, true)) {
            return;
        }
        initReceiver();
        onUpdateConfig();
        if ("android".equals(getSelfPackageName())) {
            new Thread(() -> {
                try {
                    Thread.sleep(5_000);
                    isBootComplete = true;
                    printLog("Boot Complete");
                } catch (Throwable e) {
                    printLog(e.getMessage());
                }
            }, "fcmself-boot").start();
        } else {
            isBootComplete = true;
        }
        final List<XposedModule> snapshot;
        synchronized (instances) {
            snapshot = new ArrayList<>(instances);
        }
        for (XposedModule instance : snapshot) {
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

    protected static void printLog(String text, boolean isDiagnosticsLog) {
        if (text == null) {
            return;
        }
        if (isDiagnosticsLog) {
            long now = SystemClock.elapsedRealtime();
            long prev = lastDiagLogElapsed.get();
            if (now - prev >= DIAG_LOG_MIN_INTERVAL_MS && lastDiagLogElapsed.compareAndSet(prev, now)) {
                String msg = "[" + getSelfPackageName() + "]" + text;
                Log.d(TAG, msg);
                if (context != null) {
                    try {
                        Intent log = new Intent(Contract.LOG_ACTION);
                        log.setPackage("com.google.android.gms");
                        log.putExtra("text", msg);
                        context.sendBroadcast(log);
                        return;
                    } catch (Throwable ignored) {
                    }
                }
                XposedBridge.log("[fcmself] " + msg);
            }
        } else {
            Log.d(TAG, text);
            XposedBridge.log("[fcmself] [" + getSelfPackageName() + "]" + text);
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
        if (packageName == null) {
            return false;
        }
        if ("sumicya.fcmself".equals(packageName)) {
            return true;
        }
        ConfigSnapshot snapshot = configSnapshot;
        if (!snapshot.ready) {
            this.checkUserDeviceUnlockAndUpdateConfig();
            snapshot = configSnapshot;
        }
        return snapshot.ready && snapshot.allowList.contains(packageName);
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        ConfigSnapshot snapshot = configSnapshot;
        if (!snapshot.ready) {
            this.checkUserDeviceUnlockAndUpdateConfig();
            snapshot = configSnapshot;
        }
        return snapshot.ready ? snapshot.getBoolean(key, defaultValue) : defaultValue;
    }

    protected static void onUpdateConfig() {
        configReloadPending.set(true);
        if (!configLoadRunning.compareAndSet(false, true)) {
            return;
        }
        CONFIG_EXECUTOR.execute(() -> {
            try {
                do {
                    configReloadPending.set(false);
                    loadConfigOnce();
                } while (configReloadPending.get());
            } finally {
                configLoadRunning.set(false);
                if (configReloadPending.get()) {
                    onUpdateConfig();
                }
            }
        });
    }

    private static void loadConfigOnce() {
        try {
            SharedPreferences remotePreferences =
                    XposedBridge.getRemotePreferences(Contract.PREFERENCES_NAME);
            if (remotePreferences == null) {
                throw new IllegalStateException("remotePreferences 不可用");
            }

            Set<String> loaded = remotePreferences.getStringSet(Contract.KEY_ALLOW_LIST, null);
            boolean initialized = remotePreferences.getBoolean(Contract.KEY_INIT, false);
            ConfigSnapshot currentSnapshot = configSnapshot;
            Set<String> currentAllowList = currentSnapshot.ready
                    ? currentSnapshot.allowList
                    : allowList;
            Set<String> newAllowList;
            if (loaded != null) {
                newAllowList = Collections.unmodifiableSet(new HashSet<>(loaded));
            } else if (initialized || currentAllowList == null) {
                newAllowList = Collections.emptySet();
            } else {
                newAllowList = currentAllowList;
            }
            boolean disableAutoClean = remotePreferences.getBoolean(
                    Contract.KEY_DISABLE_AUTO_CLEAN, false);
            boolean includeIceBox = remotePreferences.getBoolean(
                    Contract.KEY_INCLUDE_ICEBOX, false);
            boolean noResponseNotification = remotePreferences.getBoolean(
                    Contract.KEY_NO_RESPONSE_NOTIFICATION, false);

            configSnapshot = new ConfigSnapshot(
                    newAllowList,
                    disableAutoClean,
                    includeIceBox,
                    noResponseNotification,
                    true);
            allowList = newAllowList;

            reportHookHealth("Config", "已加载，允许 " + newAllowList.size() + " 个应用");
            if ("android".equals(getSelfPackageName())) {
                printLog("[Modern Xposed API]onUpdateConfig allowList size: " + newAllowList.size());
            }
            BroadcastFix.onConfigUpdated();
            KeepNotification.onConfigUpdated();
        } catch (Throwable e) {
            reportHookHealth("Config", "读取失败：" + e.getClass().getSimpleName());
            printLog("通过现代Xposed API读取配置失败: " + e.getMessage());
        }
    }

    private static void onUninstallFcmself() {
        if (context == null) {
            return;
        }
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = notificationManager.getNotificationChannel("fcmself");
        if (channel != null) {
            notificationManager.deleteNotificationChannel(channel.getId());
        }
    }

    private static void sendHealthResponse(String nonce) {
        if (context == null || nonce == null) {
            return;
        }
        ArrayList<String> modules = new ArrayList<>();
        synchronized (instances) {
            for (XposedModule instance : instances) {
                modules.add(instance.getClass().getSimpleName());
            }
        }
        Intent response = new Intent(HEALTH_RESPONSE_ACTION);
        response.setPackage(Contract.PACKAGE_SELF);
        response.putExtra(Contract.EXTRA_NONCE, nonce);
        response.putExtra(Contract.EXTRA_PROCESS, getSelfPackageName());
        response.putExtra(Contract.EXTRA_BOOT_COMPLETE, isBootComplete);
        response.putExtra(Contract.EXTRA_CONFIG_READY, configSnapshot.ready);
        response.putStringArrayListExtra(Contract.EXTRA_MODULES, modules);
        ArrayList<String> details = new ArrayList<>();
        for (Map.Entry<String, String> entry : HEALTH_DETAILS.entrySet()) {
            details.add(entry.getKey() + Contract.DETAIL_SEPARATOR + entry.getValue());
        }
        Collections.sort(details);
        response.putStringArrayListExtra(Contract.EXTRA_DETAILS, details);
        try {
            context.sendBroadcast(response);
        } catch (Throwable e) {
            printLog("发送 Hook 健康状态失败: " + e.getMessage());
        }
    }

    private static synchronized void initReceiver() {
        if (isInitReceiver || context == null) {
            return;
        }

        IntentFilter moduleIntentFilter = new IntentFilter(UPDATE_CONFIG_ACTION);
        moduleIntentFilter.addAction(HEALTH_REQUEST_ACTION);
        BroadcastReceiver moduleReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (UPDATE_CONFIG_ACTION.equals(action)) {
                    onUpdateConfig();
                } else if (HEALTH_REQUEST_ACTION.equals(action)) {
                    sendHealthResponse(intent.getStringExtra(Contract.EXTRA_NONCE));
                }
            }
        };
        try {
            // minSdk 36: always use the Tiramisu+ overload.
            context.registerReceiver(
                    moduleReceiver,
                    moduleIntentFilter,
                    UPDATE_CONFIG_PERMISSION,
                    null,
                    Context.RECEIVER_EXPORTED);
            isInitReceiver = true;
        } catch (Throwable e) {
            printLog("注册配置更新广播失败: " + e.getMessage());
            return;
        }

        try {
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
        } catch (Throwable e) {
            printLog("注册卸载监听失败: " + e.getMessage());
        }
    }

    protected void sendNotification(String title) {
        sendNotification(title, null, null);
    }

    protected void sendNotification(String title, String content) {
        sendNotification(title, content, null);
    }

    @SuppressLint({"MissingPermission", "NotificationPermission"})
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
        notificationManager.notify(nextNotificationId(), notification.build());
    }

    protected static int nextNotificationId() {
        return NOTIFY_ID_SEQ.updateAndGet(
                current -> current == Integer.MAX_VALUE ? 1 : current + 1);
    }

    protected void createFcmselfChannel(NotificationManagerCompat notificationManager) {
        if (notificationManager.getNotificationChannel("fcmself") == null) {
            NotificationChannel channel = new NotificationChannel("fcmself", "fcmself", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("[xposed] fcmself");
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected boolean isFCMAction(String action) {
        if (action == null) {
            return false;
        }
        if ("com.google.firebase.MESSAGING_EVENT".equals(action)
                || "com.google.firebase.INSTANCE_ID_EVENT".equals(action)) {
            return true;
        }
        return action.endsWith(".android.c2dm.intent.RECEIVE");
    }

    protected boolean isFCMIntent(Intent intent) {
        return intent != null && isFCMAction(intent.getAction());
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
