package sumicya.fcmself.xposed;

import java.lang.reflect.Method;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

public class MiuiLocalNotificationFix extends XposedModule {

    public MiuiLocalNotificationFix(ClassLoader classLoader) {
        super(classLoader);
        this.startHook();
    }

    private void startHook() {
        try {
            Class<?> clazz;
            try {
                clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerServiceInjector", classLoader);
            } catch (XposedHelpers.ClassNotFoundError e) {
                clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerServiceImpl", classLoader);
            }
            final Method[] declareMethods = clazz.getDeclaredMethods();
            Method targetMethod = null;
            for (Method method : declareMethods) {
                if ("isAllowLocalNotification".equals(method.getName()) || "isDeniedLocalNotification".equals(method.getName())) {
                    targetMethod = method;
                    break;
                }
            }
            if (targetMethod != null) {
                Method finalTargetMethod = targetMethod;
                XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam methodHookParam) {
                        if (!isBootComplete) {
                            return;
                        }
                        if (methodHookParam.args.length > 3
                                && methodHookParam.args[3] instanceof String
                                && targetIsAllow((String) methodHookParam.args[3])) {
                            methodHookParam.setResult(
                                    "isAllowLocalNotification".equals(finalTargetMethod.getName()));
                        }
                    }
                });
            } else {
                printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]");
            }
        } catch (XposedHelpers.ClassNotFoundError e) {
            printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]");
        }
    }
}
