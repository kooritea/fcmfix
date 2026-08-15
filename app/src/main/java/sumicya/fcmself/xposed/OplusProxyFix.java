package sumicya.fcmself.xposed;

import android.content.pm.PackageManager;
import android.os.WorkSource;

import java.util.concurrent.ConcurrentHashMap;

import sumicya.fcmself.util.XposedUtils;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XC_MethodReplacement;
import sumicya.fcmself.libxposed.XposedHelpers;

public class OplusProxyFix extends XposedModule {

    private static Object s_oplusProxyWakeLock = null;
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;

    /** UID cache — PackageManager lookup is relatively expensive on the broadcast hot path. */
    private static final ConcurrentHashMap<String, Integer> sUidCache = new ConcurrentHashMap<>();
    /** Reusable empty WorkSource to avoid allocation on every unfreeze. */
    private static final WorkSource EMPTY_WS = new WorkSource();

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHookOplusProxyWakeLock();
            this.startHookOplusProxyBroadcast();
        } catch (Throwable e) {
            printLog("hook error OplusProxy:" + e.getMessage());
        }
        try {
            this.startHookRegisterGmsRestrictObserver();
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver:" + e.getMessage());
        }
        try {
            this.startHookUpdateGmsRestrict();
        } catch (Throwable e) {
            printLog("hook error updateGmsRestrict:" + e.getMessage());
        }
        try {
            this.startHookIsGoogleRestricInfoOn();
        } catch (Throwable e) {
            printLog("hook error isGoogleRestricInfoOn:" + e.getMessage());
        }
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

        XposedUtils.findAndHookConstructorAnyParam(oplusWakelockClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (s_oplusProxyWakeLock != null) {
                    printLog("warn: OplusProxyWakeLock constructed multiple times!");
                    return;
                }
                s_oplusProxyWakeLock = param.thisObject;
            }
        });
    }

    private static int getTargetUidFromPackageName(String packageName) {
        if (packageName == null || context == null) {
            return -1;
        }
        Integer cached = sUidCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        try {
            int uid = context.getPackageManager().getPackageUid(packageName, 0);
            sUidCache.put(packageName, uid);
            return uid;
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

        // Reuse empty WorkSource — allocation-free on the FCM hot path
        final WorkSource ws = EMPTY_WS;
        final String tag = "FCMXX";

        if (!s_signatureDetected) {
            try {
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                s_useFourParams = true;
            } catch (Throwable e) {
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                s_useFourParams = false;
            }
            s_signatureDetected = true;
            printLog("unfreeze signature detected, fourParams=" + s_useFourParams, true);
        } else {
            try {
                if (s_useFourParams) {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                } else {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                }
                // No per-call log — runs on every allowed FCM on ColorOS
            } catch (Throwable ignored) {
            }
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
