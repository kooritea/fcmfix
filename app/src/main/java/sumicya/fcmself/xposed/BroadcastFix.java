package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

import sumicya.fcmself.util.IceboxUtils;
import sumicya.fcmself.util.XposedUtils;

public class BroadcastFix extends XposedModule {

    // Cached reflection for PowerExemptionManager (hot path on Android 12+)
    private static volatile Class<?> sPemClass;
    private static volatile Method sPemAddMethod;
    private static volatile Object sPemService;
    private static volatile boolean sPemResolved;
    private static final long TEMP_ALLOWLIST_DURATION_MS = 5_000L;
    private static final long TEMP_ALLOWLIST_REAPPLY_MS = 2_500L;
    /** Small delay so AMS critical section is likely released before DeviceIdle work. */
    private static final long TEMP_ALLOWLIST_POST_DELAY_MS = 50L;
    /** Suppress redundant reflection/binder work during a same-package FCM burst. */
    private static final ConcurrentHashMap<String, Long> LAST_TEMP_ALLOWLIST =
            new ConcurrentHashMap<>();

    /**
     * Dedicated thread for PowerExemptionManager / DeviceIdleController work.
     * Must never run while a broadcast hook still holds AMS locks (AB/BA deadlock).
     */
    private static final HandlerThread TEMP_ALLOWLIST_THREAD;
    private static final Handler TEMP_ALLOWLIST_HANDLER;

    static {
        TEMP_ALLOWLIST_THREAD = new HandlerThread(
                "fcmself-temp-allowlist", Process.THREAD_PRIORITY_BACKGROUND);
        TEMP_ALLOWLIST_THREAD.start();
        TEMP_ALLOWLIST_HANDLER = new Handler(TEMP_ALLOWLIST_THREAD.getLooper());
    }

    /** Two bounded workers prevent one 30s IceBox timeout from blocking every other package. */
    private static final ExecutorService ICEBOX_EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fcmself-icebox");
        t.setDaemon(true);
        return t;
    });
    /** Bound retained hook arguments if a frozen app receives a burst before IceBox enables it. */
    private static final int MAX_DEFERRED_BROADCASTS_PER_PACKAGE = 64;
    private static final ConcurrentHashMap<String, DeferredBroadcastQueue> DEFERRED_BROADCASTS =
            new ConcurrentHashMap<>();

    private static final class DeferredBroadcast {
        final Member method;
        final Object receiver;
        final Object[] args;

        DeferredBroadcast(Member method, Object receiver, Object[] args) {
            this.method = method;
            this.receiver = receiver;
            // Hook args are mutable. Keep a private snapshot until the asynchronous replay, and
            // copy Intent values so a caller reusing its instance cannot change the queued message.
            this.args = args.clone();
            for (int i = 0; i < this.args.length; i++) {
                if (this.args[i] instanceof Intent) {
                    this.args[i] = new Intent((Intent) this.args[i]);
                }
            }
        }
    }

    private static final class DeferredBroadcastQueue {
        final ConcurrentLinkedQueue<DeferredBroadcast> broadcasts = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();
        final AtomicBoolean workerRunning = new AtomicBoolean();
    }

    // Cached boolean config keys read once into volatiles for the absolute hottest path
    private static volatile boolean sIncludeIceBox = false;
    private static volatile boolean sIceBoxConfigCached = false;

    /** Call when remote config is refreshed so IceBox flag is re-read. */
    public static void onConfigUpdated() {
        sIceBoxConfigCached = false;
    }

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        boolean lockedInstalled = false;
        try {
            lockedInstalled = this.startHookBroadcastIntentLocked();
        } catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
        // The locked hook is the innermost entry point every high-level broadcast passes through,
        // so on devices where it installed successfully the high-level broadcastIntent hook would
        // only double-intercept each broadcast (an extra allocation + callback per send). Install it
        // only as a fallback for OEM/older builds that lack the locked hook.
        int fallbackHookCount = 0;
        if (!lockedInstalled) {
            try {
                fallbackHookCount = this.startHookBroadcastIntentHighLevel();
            } catch (Throwable e) {
                printLog("hook error high-level broadcastIntent:" + e.getMessage());
            }
        }
        // TODO(experimental): 目前策略是「锁定钩子为主，高层钩子仅在锁定钩子装不上时兜底」。
        //   若想保留双保险（两者都装，以韧性优先于省一次拦截开销），可改为无条件都装并观察。
        boolean resultHookInstalled = false;
        try {
            this.startHookScheduleResultTo();
            resultHookInstalled = true;
        } catch (Throwable e) {
            // Only modern broadcast queues expose this hook point. Keep the core FCM fix active
            // when an OEM or older Android release does not provide it.
            printLog("hook error scheduleResultTo:" + e.getMessage());
        }
        String core = lockedInstalled
                ? "核心 locked Hook"
                : fallbackHookCount > 0
                ? "高层 fallback ×" + fallbackHookCount
                : "核心 Hook 未命中";
        reportHookHealth(
                "BroadcastFix",
                core
                        + (resultHookInstalled ? "；结果回调已安装" : "；结果回调不适用")
                        + "；临时豁免 5 秒（异步）");
    }

    /** @return true when a usable broadcastIntentLocked hook was installed. */
    private boolean startHookBroadcastIntentLocked() {
        Method targetMethod = null;
        int intent_args_index = 0;
        int appOp_args_index = 0;
        if (Build.VERSION.SDK_INT >= 35) {
            targetMethod = XposedUtils.tryFindMethodMostParam(classLoader, "com.android.server.am.BroadcastController", "broadcastIntentLocked");
            if (targetMethod != null) {
                intent_args_index = 3;
                appOp_args_index = 13;
            }
        }
        if (targetMethod == null) {
            targetMethod = XposedUtils.tryFindMethodMostParam(classLoader, "com.android.server.am.ActivityManagerService", "broadcastIntentLocked");
            if (targetMethod != null) {
                Parameter[] parameters = targetMethod.getParameters();
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    intent_args_index = 2;
                    appOp_args_index = 9;
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    intent_args_index = 3;
                    appOp_args_index = 10;
                } else if (Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 32) {
                    intent_args_index = 3;
                    if (parameters.length > 12 && parameters[12].getType() == int.class) {
                        appOp_args_index = 12;
                    } else if (parameters.length > 11 && parameters[11].getType() == int.class) {
                        appOp_args_index = 11;
                    }
                } else if (Build.VERSION.SDK_INT == 33) {
                    intent_args_index = 3;
                    appOp_args_index = 12;
                } else {
                    // All remaining supported releases are Android 14+ (minSdk is Android 10).
                    intent_args_index = 3;
                    if (parameters.length > 13 && parameters[13].getType() == int.class) {
                        appOp_args_index = 13;
                    } else if (parameters.length > 12 && parameters[12].getType() == int.class) {
                        appOp_args_index = 12;
                    }
                }
                if (intent_args_index == 0 || appOp_args_index == 0) {
                    intent_args_index = 0;
                    appOp_args_index = 0;
                    for (int i = 0; i < parameters.length; i++) {
                        if ("appOp".equals(parameters[i].getName()) && parameters[i].getType() == int.class) {
                            appOp_args_index = i;
                        }
                        if ("intent".equals(parameters[i].getName()) && parameters[i].getType() == Intent.class) {
                            intent_args_index = i;
                        }
                    }
                }
            }
        }
        if (targetMethod != null && intent_args_index != 0 && appOp_args_index != 0
                && targetMethod.getParameters()[intent_args_index].getType() == Intent.class
                && targetMethod.getParameters()[appOp_args_index].getType() == int.class) {
            createBroadcastIntentLockedHooker(intent_args_index, appOp_args_index, targetMethod);
            return true;
        }
        printLog("broadcastIntentLocked hook 位置查找失败，fcmself将不会工作。");
        return false;
    }

    private void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method) {
        printLog("Android API: " + Build.VERSION.SDK_INT);
        printLog("appOp_args_index: " + appOp_args_index);
        printLog("intent_args_index: " + intent_args_index);
        printLog("hook target: " + method.getDeclaringClass().getName());
        final int finalIntent_args_index = intent_args_index;
        final int finalAppOp_args_index = appOp_args_index;

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if (!isBootComplete) {
                    return;
                }
                Object intentArg = methodHookParam.args[finalIntent_args_index];
                if (!(intentArg instanceof Intent)) {
                    return;
                }
                Intent intent = (Intent) intentArg;
                final int flags = intent.getFlags();
                if ((flags & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0) {
                    return;
                }
                if (!isFCMIntent(intent)) {
                    return;
                }
                String target = intent.getComponent() != null
                        ? intent.getComponent().getPackageName()
                        : intent.getPackage();
                if (target == null || !targetIsAllow(target)) {
                    return;
                }

                Object appOpArg = methodHookParam.args[finalAppOp_args_index];
                if (appOpArg instanceof Integer && ((Integer) appOpArg) == -1) {
                    methodHookParam.args[finalAppOp_args_index] = 11;
                }
                int newFlags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES;
                if ((flags & Intent.FLAG_RECEIVER_FOREGROUND) == 0) {
                    newFlags |= Intent.FLAG_RECEIVER_FOREGROUND;
                }
                intent.addFlags(newFlags);

                // Never call PowerExemptionManager while AMS locks may still be held.
                scheduleTemporaryAllowList(target);

                if (!sIceBoxConfigCached) {
                    sIncludeIceBox = getBooleanConfig("includeIceBoxDisableApp", false);
                    sIceBoxConfigCached = true;
                }
                if (sIncludeIceBox && !IceboxUtils.isAppEnabled(context, target)) {
                    printLog("Waiting for IceBox to activate the app: " + target, true);
                    // Every intercepted delivery must be replayed. The old in-flight Set retained
                    // only the first broadcast and silently dropped later messages for the same
                    // package while IceBox was still enabling it.
                    if (deferUntilIceBoxEnabled(
                            target,
                            methodHookParam.method,
                            methodHookParam.thisObject,
                            methodHookParam.args)) {
                        methodHookParam.setResult(defaultReturnValue(methodHookParam.method));
                    }
                } else {
                    printLog("Send Forced Start Broadcast: " + target, true);
                }
                OplusProxyFix.unfreeze(target);
            }
        });
    }

    private static boolean deferUntilIceBoxEnabled(
            String packageName, Member method, Object receiver, Object[] args) {
        DeferredBroadcastQueue state = DEFERRED_BROADCASTS.computeIfAbsent(
                packageName, ignored -> new DeferredBroadcastQueue());
        int queued = state.size.incrementAndGet();
        if (queued > MAX_DEFERRED_BROADCASTS_PER_PACKAGE) {
            state.size.decrementAndGet();
            // Do not cancel this delivery when the queue is full. Let the original framework path
            // run synchronously rather than trading bounded memory for a guaranteed message loss.
            printLog("IceBox deferred queue full, sending directly: " + packageName, true);
            return false;
        }
        state.broadcasts.offer(new DeferredBroadcast(method, receiver, args));
        startIceBoxWorker(packageName, state);
        return true;
    }

    private static void startIceBoxWorker(String packageName, DeferredBroadcastQueue state) {
        if (!state.workerRunning.compareAndSet(false, true)) {
            return;
        }
        ICEBOX_EXEC.execute(() -> {
            try {
                if (!IceboxUtils.isAppEnabled(context, packageName)) {
                    IceboxUtils.activeApp(context, packageName);
                    long startedAt = SystemClock.elapsedRealtime();
                    long deadline = startedAt + 30_000L;
                    while (!IceboxUtils.isAppEnabled(context, packageName)) {
                        long now = SystemClock.elapsedRealtime();
                        if (now >= deadline) {
                            break;
                        }
                        long elapsed = now - startedAt;
                        long pollInterval = elapsed < 2_000L
                                ? 100L
                                : elapsed < 10_000L ? 250L : 500L;
                        try {
                            Thread.sleep(Math.min(pollInterval, deadline - now));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (IceboxUtils.isAppEnabled(context, packageName)) {
                    printLog("Send deferred forced-start broadcasts: " + packageName, true);
                } else {
                    printLog("Waiting for IceBox to activate the app timed out: "
                            + packageName, true);
                }

                DeferredBroadcast deferred;
                while ((deferred = state.broadcasts.poll()) != null) {
                    state.size.decrementAndGet();
                    try {
                        XposedBridge.invokeOriginalMethod(
                                deferred.method, deferred.receiver, deferred.args);
                    } catch (Throwable e) {
                        printLog("Send Forced Start Broadcast Error: " + packageName + " "
                                + e.getMessage(), true);
                    }
                }
            } finally {
                state.workerRunning.set(false);
                // Close the enqueue-vs-drain race: a delivery may arrive after poll() returned null
                // but before workerRunning was cleared.
                if (!state.broadcasts.isEmpty()) {
                    startIceBoxWorker(packageName, state);
                }
            }
        });
    }

    /** Return a type-correct placeholder while an intercepted method is replayed asynchronously. */
    private static Object defaultReturnValue(Member member) {
        if (!(member instanceof Method)) {
            return null;
        }
        Class<?> type = ((Method) member).getReturnType();
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private int startHookBroadcastIntentHighLevel() {
        int hookCount = 0;
        String[] classNames = {
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        for (String className : classNames) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                String name = method.getName();
                if (!"broadcastIntent".equals(name) && !"broadcastIntentWithFeature".equals(name)) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                int intentIndex = -1;
                for (int i = 0; i < params.length; i++) {
                    if (Intent.class.equals(params[i])) {
                        intentIndex = i;
                        break;
                    }
                }
                if (intentIndex < 0) {
                    continue;
                }
                final int finalIntentIndex = intentIndex;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBootComplete) {
                            return;
                        }
                        Object arg = param.args[finalIntentIndex];
                        if (!(arg instanceof Intent)) {
                            return;
                        }
                        Intent intent = (Intent) arg;
                        final int flags = intent.getFlags();
                        // broadcastIntentLocked marks handled FCM intents; do not repeat the expensive work here.
                        if ((flags & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0) {
                            return;
                        }
                        if (!isFCMIntent(intent)) {
                            return;
                        }
                        String target = intent.getComponent() != null
                                ? intent.getComponent().getPackageName()
                                : intent.getPackage();
                        if (target == null || !targetIsAllow(target)) {
                            return;
                        }
                        int nf = Intent.FLAG_INCLUDE_STOPPED_PACKAGES;
                        if ((flags & Intent.FLAG_RECEIVER_FOREGROUND) == 0) {
                            nf |= Intent.FLAG_RECEIVER_FOREGROUND;
                        }
                        intent.addFlags(nf);
                        scheduleTemporaryAllowList(target);
                    }
                });
                hookCount++;
                printLog("hooked high-level: " + className + "#" + name);
            }
        }
        return hookCount;
    }

    /**
     * Schedule temporary allow-list work off the AMS/broadcast hook thread.
     * Dedup happens here so we never post redundant DeviceIdle binder calls.
     */
    private static void scheduleTemporaryAllowList(String packageName) {
        if (packageName == null || context == null || Build.VERSION.SDK_INT < 31) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long previous = LAST_TEMP_ALLOWLIST.get(packageName);
        if (previous != null && now - previous < TEMP_ALLOWLIST_REAPPLY_MS) {
            return;
        }
        LAST_TEMP_ALLOWLIST.put(packageName, now);
        final long markedAt = now;
        TEMP_ALLOWLIST_HANDLER.postDelayed(() -> {
            try {
                applyTemporaryAllowListInternal(packageName);
            } catch (Throwable t) {
                LAST_TEMP_ALLOWLIST.remove(packageName, markedAt);
                printLog("PowerExemptionManager async failed: " + t.getMessage(), true);
            }
        }, TEMP_ALLOWLIST_POST_DELAY_MS);
    }

    @SuppressLint("PrivateApi")
    private static void applyTemporaryAllowListInternal(String packageName) {
        if (packageName == null || context == null || Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            if (!sPemResolved) {
                synchronized (BroadcastFix.class) {
                    if (!sPemResolved) {
                        try {
                            sPemClass = Class.forName("android.os.PowerExemptionManager");
                            sPemAddMethod = sPemClass.getMethod(
                                    "addToTemporaryAllowList",
                                    String.class, int.class, String.class, long.class);
                        } catch (Throwable ignored) {
                            sPemClass = null;
                            sPemAddMethod = null;
                        }
                        sPemResolved = true;
                    }
                }
            }
            if (sPemAddMethod == null || sPemClass == null) {
                return;
            }
            // Service publication can lag class resolution during boot. Retry only this cheap
            // lookup when it was temporarily null instead of permanently disabling exemptions.
            Object service = sPemService;
            if (service == null) {
                synchronized (BroadcastFix.class) {
                    service = sPemService;
                    if (service == null) {
                        service = context.getSystemService(sPemClass);
                        sPemService = service;
                    }
                }
            }
            if (service == null) {
                return;
            }
            // User id of the system process (USER_SYSTEM = 0). A hardcoded 102 names a user that
            // does not exist on single-user devices, silently making the temporary allow list a
            // no-op. Process.myUserHandle().hashCode() is the public-API equivalent of the hidden
            // Context.getUserId() on recent SDKs (the latter fails to compile on compileSdk 37).
            sPemAddMethod.invoke(service, packageName, Process.myUserHandle().hashCode(),
                    "GOOGLE_C2DM", TEMP_ALLOWLIST_DURATION_MS);
        } catch (Throwable t) {
            printLog("PowerExemptionManager failed: " + t.getMessage(), true);
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }

    @SuppressLint({"MissingPermission", "NotificationPermission"})
    private void startHookScheduleResultTo() {
        // This code is injected into system_server, so notification permission is determined by
        // that host process rather than this module APK. Keep the runtime SecurityException guard
        // below for OEM implementations that apply additional notification restrictions.
        Method method = XposedUtils.findMethod(
                XposedHelpers.findClass("com.android.server.am.BroadcastQueueModernImpl", classLoader),
                "scheduleResultTo", 1);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                if (!isBootComplete) {
                    return;
                }
                if (methodHookParam.args.length == 0 || methodHookParam.args[0] == null) {
                    return;
                }
                Object broadcastRecord = methodHookParam.args[0];
                Object resultTo = XposedHelpers.getObjectField(broadcastRecord, "resultTo");
                Object intentValue = XposedHelpers.getObjectField(broadcastRecord, "intent");
                Object resultCodeValue = XposedHelpers.getObjectField(broadcastRecord, "resultCode");
                if (resultTo == null
                        || !(intentValue instanceof Intent)
                        || !(resultCodeValue instanceof Integer)) {
                    return;
                }
                Intent intent = (Intent) intentValue;
                int resultCode = (Integer) resultCodeValue;
                String packageName = intent.getComponent() != null
                        ? intent.getComponent().getPackageName()
                        : intent.getPackage();
                if (resultCode != -1 && getBooleanConfig("noResponseNotification", false) && targetIsAllow(packageName)) {
                    try {
                        Intent notifyIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                        if (notifyIntent != null) {
                            notifyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            PendingIntent pendingIntent = PendingIntent.getActivity(
                                    context, 0, notifyIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                            createFcmselfChannel(notificationManager);
                            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, "fcmself")
                                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                                    .setContentTitle("FCM Message")
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
                            Bitmap icon = getAppIcon(packageName);
                            if (icon != null) {
                                notification.setLargeIcon(icon);
                            }
                            notification.setContentIntent(pendingIntent).setAutoCancel(true);
                            try {
                                notificationManager.notify(nextNotificationId(), notification.build());
                            } catch (SecurityException ignored) {}
                        } else {
                            printLog("无法获取目标应用active: " + packageName, false);
                        }
                    } catch (Throwable e) {
                        printLog(e.getMessage(), false);
                    }
                }
            }
        });
    }

    private static Bitmap getAppIcon(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable drawable = pm.getApplicationIcon(appInfo);
            if (drawable instanceof BitmapDrawable) {
                return ((BitmapDrawable) drawable).getBitmap();
            } else {
                if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
                    return null;
                }
                Bitmap bitmap = Bitmap.createBitmap(
                        drawable.getIntrinsicWidth(),
                        drawable.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
                drawable.draw(new android.graphics.Canvas(bitmap));
                return bitmap;
            }
        } catch (Throwable e) {
            return null;
        }
    }
}
