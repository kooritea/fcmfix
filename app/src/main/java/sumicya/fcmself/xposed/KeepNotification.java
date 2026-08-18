package sumicya.fcmself.xposed;

import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

/**
 * Prevents auto-cleanup of notifications for allowed packages. Android 16 (API 36) layouts only.
 */
public class KeepNotification extends XposedModule {

    private static volatile boolean sDisableAutoClean = false;
    private static volatile boolean sConfigCached = false;

    public static void onConfigUpdated() {
        sConfigCached = false;
    }

    public KeepNotification(ClassLoader classLoader) {
        super(classLoader);
        boolean installed = false;
        try {
            this.startHook();
            installed = true;
        } catch (Throwable e) {
            printLog("KeepNotification hook miss: " + e.getMessage());
        }
        reportHookHealth(
                "KeepNotification", installed ? "通知保留 Hook 已安装" : "当前签名未命中");
    }

    private void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        Class<?> clazz = XposedHelpers.findClass(
                "com.android.server.notification.NotificationManagerService", classLoader);
        Method targetMethod = null;
        for (Method method : clazz.getDeclaredMethods()) {
            if ("cancelAllNotificationsInt".equals(method.getName())) {
                if (targetMethod == null
                        || targetMethod.getParameterTypes().length < method.getParameterTypes().length) {
                    targetMethod = method;
                }
            }
        }
        if (targetMethod == null) {
            throw new NoSuchMethodError("cancelAllNotificationsInt not found");
        }

        // API 36+: pkg at 2, reason typically at 7 (same family as 35+).
        final int pkgIndex = 2;
        final int reasonIndex = 7;
        if (pkgIndex >= targetMethod.getParameterTypes().length
                || reasonIndex >= targetMethod.getParameterTypes().length) {
            throw new NoSuchMethodError("cancelAllNotificationsInt arity too short for A16 layout");
        }

        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isBootComplete) {
                    return;
                }
                if (!sConfigCached) {
                    sDisableAutoClean = getBooleanConfig("disableAutoCleanNotification", false);
                    sConfigCached = true;
                }
                if (!sDisableAutoClean
                        || !(param.args[pkgIndex] instanceof String)
                        || !(param.args[reasonIndex] instanceof Integer)) {
                    return;
                }
                String packageName = (String) param.args[pkgIndex];
                int reason = (Integer) param.args[reasonIndex];
                if (targetIsAllow(packageName)
                        && (reason == NotificationListenerService.REASON_PACKAGE_CHANGED
                        || reason == 10020 || reason == 10021)) {
                    param.setResult(null);
                }
            }
        });
    }
}
