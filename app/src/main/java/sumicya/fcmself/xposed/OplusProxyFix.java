package sumicya.fcmself.xposed;

import android.content.pm.PackageManager;
import android.os.WorkSource;

import java.lang.reflect.Constructor;

import sumicya.fcmself.util.XposedUtils;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XC_MethodReplacement;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

public class OplusProxyFix extends XposedModule {

    private static volatile Object s_oplusProxyWakeLock = null;
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        int installed = 0;
        // ColorOS internals vary by release. Install every independent hook even if an unrelated
        // class is missing; in particular, absence of the wake-lock proxy must not disable the
        // broadcast bypass.
        try {
            this.startHookOplusProxyWakeLock();
            installed++;
        } catch (Throwable e) {
            printLog("hook error OplusProxyWakeLock:" + e.getMessage());
        }
        try {
            this.startHookOplusProxyBroadcast();
            installed++;
        } catch (Throwable e) {
            printLog("hook error OplusProxyBroadcast:" + e.getMessage());
        }
        try {
            this.startHookRegisterGmsRestrictObserver();
            installed++;
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver:" + e.getMessage());
        }
        try {
            this.startHookUpdateGmsRestrict();
            installed++;
        } catch (Throwable e) {
            printLog("hook error updateGmsRestrict:" + e.getMessage());
        }
        try {
            this.startHookIsGoogleRestricInfoOn();
            installed++;
        } catch (Throwable e) {
            printLog("hook error isGoogleRestricInfoOn:" + e.getMessage());
        }
        try {
            this.startHookIsGmsApp();
            installed++;
        } catch (Throwable e) {
            printLog("hook error isGmsApp:" + e.getMessage());
        }
        reportHookHealth("OplusProxyFix", "ColorOS Hook 已安装 " + installed + "/6");
    }

    private void startHookOplusProxyBroadcast() throws Exception {
        Class<?> oplusProxyBroadcastClass = XposedHelpers.findClass("com.android.server.am.OplusProxyBroadcast", classLoader);
        Class<?> resultEnum = XposedHelpers.findClass("com.android.server.am.OplusProxyBroadcast$RESULT", classLoader);
        Object notIncludeValue = XposedHelpers.getStaticObjectField(resultEnum, "NOT_INCLUDE");

        XposedUtils.findAndHookMethod(oplusProxyBroadcastClass, "shouldProxy", 8, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length <= 6
                        || !(param.args[5] instanceof String)
                        || !(param.args[6] instanceof String)) {
                    return;
                }
                String callingPkg = param.args[3] instanceof String
                        ? (String) param.args[3]
                        : "unknown";
                String pkgName = (String) param.args[5];
                String action = (String) param.args[6];
                if (isFCMAction(action) && targetIsAllow(pkgName)) {
                    // diagnostics-only (throttled) — ColorOS path can be very frequent
                    printLog("shouldProxy: bypass pkg=" + pkgName + ", caller=" + callingPkg, true);
                    param.setResult(notIncludeValue);
                }
            }
        });
    }

    private void startHookOplusProxyWakeLock() throws Exception {
        Class<?> oplusWakelockClass = XposedHelpers.findClass("com.android.server.power.OplusProxyWakeLock", classLoader);

        XC_MethodHook captureInstance = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (s_oplusProxyWakeLock != null) {
                    if (s_oplusProxyWakeLock != param.thisObject) {
                        printLog("warn: OplusProxyWakeLock constructed multiple times!");
                    }
                    return;
                }
                s_oplusProxyWakeLock = param.thisObject;
            }
        };
        Constructor<?>[] constructors = oplusWakelockClass.getDeclaredConstructors();
        if (constructors.length == 0) {
            throw new NoSuchMethodException(oplusWakelockClass.getName() + " has no constructor");
        }
        // OEM builds often expose several overloads and may instantiate a shorter one. Hooking
        // only the longest constructor can miss the singleton entirely.
        for (Constructor<?> constructor : constructors) {
            XposedBridge.hookMethod(constructor, captureInstance);
        }
    }

    private static int getTargetUidFromPackageName(String packageName) {
        if (packageName == null || context == null) {
            return -1;
        }
        try {
            // Do not cache UIDs in system_server: uninstalling and reinstalling an app can assign a
            // different UID while this process (and the Xposed module) stays alive.
            return context.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static void unfreeze(String target) {
        if (s_oplusProxyWakeLock == null) {
            return;
        }

        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            return;
        }

        // WorkSource is mutable and OEM implementations may modify it. A per-call instance avoids
        // sharing mutable state when broadcasts are delivered concurrently.
        final WorkSource ws = new WorkSource();
        final String tag = "FCMXX";

        if (!s_signatureDetected) {
            synchronized (OplusProxyFix.class) {
                if (!s_signatureDetected) {
                    try {
                        XposedHelpers.callMethod(
                                s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                        s_useFourParams = true;
                    } catch (Throwable fourParamError) {
                        try {
                            XposedHelpers.callMethod(
                                    s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                            s_useFourParams = false;
                        } catch (Throwable threeParamError) {
                            printLog("unfreezeIfNeed signature unavailable", true);
                            return;
                        }
                    }
                    s_signatureDetected = true;
                    printLog("unfreeze signature detected, fourParams=" + s_useFourParams, true);
                    return;
                }
            }
        }

        try {
            if (s_useFourParams) {
                XposedHelpers.callMethod(
                        s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
            } else {
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
            }
            // No per-call log — runs on every allowed FCM on ColorOS
        } catch (Throwable ignored) {
        }
    }

    private void startHookRegisterGmsRestrictObserver() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", classLoader,
                "registerGmsRestrictObserver", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookUpdateGmsRestrict() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", classLoader,
                "updateGmsRestrict", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookIsGoogleRestricInfoOn() {
        XposedHelpers.findAndHookMethod("com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", classLoader,
                "isGoogleRestricInfoOn", int.class, XC_MethodReplacement.returnConstant(false));
    }

    private void startHookIsGmsApp() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.OplusHansDBConfig", classLoader,
                "isGmsApp", int.class, XC_MethodReplacement.returnConstant(false));
    }
}
