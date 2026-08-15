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

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** Single-thread executor for IceBox activation — avoids unbounded threads under FCM storms. */
    private static final ExecutorService ICEBOX_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fcmself-icebox");
        t.setDaemon(true);
        return t;
    });
    /** Packages currently being activated; prevents duplicate concurrent activations. */
    private static final Set<String> iceboxInFlight = ConcurrentHashMap.newKeySet();

    // Cached boolean config keys read once into volatiles for the absolute hottest path
    private static volatile boolean sIncludeIceBox = false;
    private static volatile boolean sIceBoxConfigCached = false;

    /** Call when remote config is refreshed so IceBox flag is re-read. */
    public static void onConfigUpdated() {
        sIceBoxConfigCached = false;
    }

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHookBroadcastIntentLocked();
        } catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
        try {
            this.startHookBroadcastIntentHighLevel();
        } catch (Throwable e) {
            printLog("hook error high-level broadcastIntent:" + e.getMessage());
        }
        try {
            this.startHookScheduleResultTo();
        } catch (Throwable e) {
            // Only modern broadcast queues expose this hook point. Keep the core FCM fix active
            // when an OEM or older Android release does not provide it.
            printLog("hook error scheduleResultTo:" + e.getMessage());
        }
    }

    protected void startHookBroadcastIntentLocked() {
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
                } else if (Build.VERSION.SDK_INT >= 34) {
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
        } else {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmself将不会工作。");
        }
    }

    protected void createBroadcastIntentLockedHooker(int intent_args_index, int appOp_args_index, Method method) {
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

                applyTemporaryAllowList(target);

                if (!sIceBoxConfigCached) {
                    sIncludeIceBox = getBooleanConfig("includeIceBoxDisableApp", false);
                    sIceBoxConfigCached = true;
                }
                if (sIncludeIceBox && !IceboxUtils.isAppEnabled(context, target)) {
                    printLog("Waiting for IceBox to activate the app: " + target, true);
                    methodHookParam.setResult(false);
                    if (iceboxInFlight.add(target)) {
                        final Object thisObject = methodHookParam.thisObject;
                        final Object[] args = methodHookParam.args;
                        final Member originalMethod = methodHookParam.method;
                        final String pkg = target;
                        ICEBOX_EXEC.execute(() -> {
                            try {
                                IceboxUtils.activeApp(context, pkg);
                                for (int i1 = 0; i1 < 300; i1++) {
                                    if (IceboxUtils.isAppEnabled(context, pkg)) {
                                        break;
                                    }
                                    try {
                                        Thread.sleep(100);
                                    } catch (Throwable e) {
                                        printLog("Send Forced Start Broadcast Error: " + pkg + " " + e.getMessage(), true);
                                        break;
                                    }
                                }
                                try {
                                    if (IceboxUtils.isAppEnabled(context, pkg)) {
                                        printLog("Send Forced Start Broadcast: " + pkg, true);
                                    } else {
                                        printLog("Waiting for IceBox to activate the app timed out: " + pkg, true);
                                    }
                                    XposedBridge.invokeOriginalMethod(originalMethod, thisObject, args);
                                } catch (Throwable e) {
                                    printLog("Send Forced Start Broadcast Error: " + pkg + " " + e.getMessage(), true);
                                }
                            } finally {
                                iceboxInFlight.remove(pkg);
                            }
                        });
                    }
                } else {
                    printLog("Send Forced Start Broadcast: " + target, true);
                }
                OplusProxyFix.unfreeze(target);
            }
        });
    }

    protected void startHookBroadcastIntentHighLevel() {
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
                        applyTemporaryAllowList(target);
                    }
                });
                printLog("hooked high-level: " + className + "#" + name);
            }
        }
    }

    private static void applyTemporaryAllowList(String packageName) {
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
                            sPemService = context.getSystemService(sPemClass);
                        } catch (Throwable ignored) {
                            sPemClass = null;
                            sPemAddMethod = null;
                            sPemService = null;
                        }
                        sPemResolved = true;
                    }
                }
            }
            if (sPemAddMethod == null || sPemService == null) {
                return;
            }
            sPemAddMethod.invoke(sPemService, packageName, 102, "GOOGLE_C2DM", 2000L);
        } catch (Throwable t) {
            printLog("PowerExemptionManager failed: " + t.getMessage(), true);
        }
    }

    @SuppressLint({"MissingPermission", "NotificationPermission"})
    protected void startHookScheduleResultTo() {
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
                                notificationManager.notify(
                                    (int) System.currentTimeMillis(),notification.build()
                                );
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
