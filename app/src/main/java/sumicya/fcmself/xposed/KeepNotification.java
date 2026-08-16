package sumicya.fcmself.xposed;

import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

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
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt");
        }
        reportHookHealth(
                "KeepNotification", installed ? "通知保留 Hook 已安装" : "当前签名未命中");
    }

    private void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        for (Method method : declareMethods) {
            if ("cancelAllNotificationsInt".equals(method.getName())) {
                if (targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length) {
                    targetMethod = method;
                }
            }
        }
        if (targetMethod != null) {
            int pkg_args_index;
            int reason_args_index;
            final int sdk = Build.VERSION.SDK_INT;
            final int paramCount = targetMethod.getParameterTypes().length;
            if (sdk >= 30 && sdk <= 33) {
                pkg_args_index = 2;
                reason_args_index = 8;
            } else if (sdk == 34) {
                if (paramCount == 10) {
                    pkg_args_index = 2;
                    reason_args_index = 8;
                } else if (paramCount == 8) {
                    pkg_args_index = 2;
                    reason_args_index = 7;
                } else {
                    throw new NoSuchMethodError();
                }
            } else if (sdk >= 35) {
                pkg_args_index = 2;
                reason_args_index = 7;
            } else {
                // Signature unknown below Android 11 — leave the fix unhooked.
                throw new NoSuchMethodError();
            }
            if (pkg_args_index == 0 || reason_args_index == 0) {
                throw new NoSuchMethodError();
            }
            int finalPkg_args_index = pkg_args_index;
            int finalReason_args_index = reason_args_index;
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
                            || finalPkg_args_index >= param.args.length
                            || finalReason_args_index >= param.args.length
                            || !(param.args[finalPkg_args_index] instanceof String)
                            || !(param.args[finalReason_args_index] instanceof Integer)) {
                        return;
                    }
                    String packageName = (String) param.args[finalPkg_args_index];
                    int reason = (Integer) param.args[finalReason_args_index];
                    if (targetIsAllow(packageName)
                            && (reason == NotificationListenerService.REASON_PACKAGE_CHANGED
                            || reason == 10020 || reason == 10021)) { // cos15/oos15
                        param.setResult(null);
                    }
                }
            });
        } else {
            throw new NoSuchMethodError();
        }
    }
}
