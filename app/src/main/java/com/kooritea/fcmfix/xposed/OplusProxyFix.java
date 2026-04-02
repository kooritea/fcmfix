package com.kooritea.fcmfix.xposed;

import android.content.pm.PackageManager;
import android.os.WorkSource;

import com.kooritea.fcmfix.util.XposedUtils;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XC_MethodReplacement;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class OplusProxyFix extends XposedModule {

    private static Object s_oplusProxyWakeLock = null;
    private static volatile boolean s_useFourParams = false;
    private static volatile boolean s_signatureDetected = false;

    public OplusProxyFix(ClassLoader classLoader) {
        super(classLoader);
        try{
            this.startHookOplusProxyWakeLock();
            this.startHookOplusProxyBroadcast();
        }catch(Throwable e) {
            printLog("hook error OplusProxy:" + e.getMessage());
        }
        try {
            this.startHookRegisterGmsRestrictObserver(); // 阻止Hans监听GMS状态更新
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver:" + e.getMessage());
        }
        try {
            this.startHookUpdateGmsRestrict(); // 拦截Hans更新GMS限制状态
        } catch (Throwable e) {
            printLog("hook error updateGmsRestrict:" + e.getMessage());
        }
        try {
            this.startHookIsGoogleRestricInfoOn(); // 阻止判断GMS限制
        } catch (Throwable e) {
            printLog("hook error isGoogleRestricInfoOn:" + e.getMessage());
        }
        /*
        try {
            this.startHookIsGmsApp(); // 阻止Hans对GMS进行特殊处理
        } catch (Throwable e) {
            printLog("hook error isGmsApp:" + e.getMessage());
        }
        */
    }

    private void startHookOplusProxyBroadcast() throws Exception {
        Class<?> oplusProxyBroadcastClass = XposedHelpers.findClass("com.android.server.am.OplusProxyBroadcast", classLoader);
        Class<?> resultEnum = XposedHelpers.findClass("com.android.server.am.OplusProxyBroadcast$RESULT", classLoader);
        Object notIncludeValue = XposedHelpers.getStaticObjectField(resultEnum, "NOT_INCLUDE");
        Object proxyValue = XposedHelpers.getStaticObjectField(resultEnum, "PROXY");

        /*
        XXX only tested on OnePlus13T ColorOS 15
        private RESULT shouldProxy( 8 args
            00 Intent intent,
            01 int callingPid,
            02 int callingUid,
            03 String callingPkg,
            04 int uid,
            05 String pkgName,
            06 String action,
            07 int appType) {
         */

        XposedUtils.findAndHookMethod(oplusProxyBroadcastClass, "shouldProxy", 8, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String callingPkg = (String)param.args[3];
                String pkgName = (String)param.args[5];
                String action = (String)param.args[6];
                // positive sample: caller=com.google.android.gms, action=com.google.android.c2dm.intent.RECEIVE
                if (isFCMAction(action) && targetIsAllow(pkgName)) {
                    printLog("shouldProxy: bypass pkg="+pkgName+", caller="+callingPkg+", action="+action);
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
        // Convert package name to UID
        if (packageName != null) {
            try {
                PackageManager pm = context.getPackageManager();
                return pm.getPackageUid(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                printLog("error: Package not found: " + packageName);
            }
        }

        // Default to an invalid UID if we couldn't determine the target
        return -1;
    }

    public static void unfreeze(String target) {
        if (s_oplusProxyWakeLock == null) {
            return;
        }

        int uid = getTargetUidFromPackageName(target);
        if (uid < 0) {
            return;
        }

        /*
        XXX only tested on OnePlus13T ColorOS 15
        unfreezeIfNeed: 3 args
            00 int uid,
            01 WorkSource ws,
            02 String tag
         */

        WorkSource ws = new WorkSource();
        String tag = "FCMXX";

        if (!s_signatureDetected) {
            try {
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                s_useFourParams = true;
            } catch (Throwable e) {
                // 降级用3参
                XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                s_useFourParams = false;
            }
            s_signatureDetected = true;
        } else {
            // 后续调用直接用缓存的
            try {
                if (s_useFourParams) {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag, "FCMFix");
                    printLog("unfreeze " + target + ", uid=" + uid);
                } else {
                    XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, tag);
                    printLog("unfreeze " + target + ", uid=" + uid);
                }
            } catch (Throwable ignored) {
                // 静默或log
            }
        }
    }

    private void startHookRegisterGmsRestrictObserver() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", classLoader, "registerGmsRestrictObserver", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookUpdateGmsRestrict() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", classLoader, "updateGmsRestrict", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookIsGoogleRestricInfoOn() {
        XposedHelpers.findAndHookMethod("com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", classLoader, "isGoogleRestricInfoOn", int.class, XC_MethodReplacement.returnConstant(false));
    }

    private void startHookIsGmsApp() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.OplusHansDBConfig", classLoader, "isGmsApp", int.class, XC_MethodReplacement.returnConstant(false));
    }

}
