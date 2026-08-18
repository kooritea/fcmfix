package sumicya.fcmself.xposed;

import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

/**
 * Prevents auto-cleanup of notifications for allowed packages when the reason is package change
 * or certain OEM codes. Requires Android 13+ (minSdk 33).
 */
public class KeepNotification extends XposedModule {

    private static volatile boolean sDisableAutoClean = false;
    private static volatile boolean sConfigCached = false;

    /** Called after remote preferences are refreshed. */
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

        final int sdk = Build.VERSION.SDK_INT;
        final int paramCount = targetMethod.getParameterTypes().length;
        final int pkgIndex = 2;
        final int reasonIndex;

        // minSdk 33. API 33, 34 variants, 35+.
        if (sdk == 33) {
            reasonIndex = 8;
        } else if (sdk == 34) {
            if (paramCount == 10) {
                reasonIndex = 8;
            } else if (paramCount == 8) {
                reasonIndex = 7;
            } else {
                throw new NoSuchMethodError("unexpected cancelAllNotificationsInt arity on API 34: " + paramCount);
            }
        } else { // 35+
            reasonIndex = 7;
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
                        || pkgIndex >= param.args.length
                        || reasonIndex >= param.args.length
                        || !(param.args[pkgIndex] instanceof String)
                        || !(param.args[reasonIndex] instanceof Integer)) {
                    return;
                }
                String packageName = (String) param.args[pkgIndex];
                int reason = (Integer) param.args[reasonIndex];
                if (targetIsAllow(packageName)
                        && (reason == NotificationListenerService.REASON_PACKAGE_CHANGED
                        || reason == 10020 || reason == 10021)) { // cos15/oos15
                    param.setResult(null);
                }
            }
        });
    }
}
