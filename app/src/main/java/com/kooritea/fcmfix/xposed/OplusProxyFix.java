package com.kooritea.fcmfix.xposed;

import android.content.pm.PackageManager;
import android.os.WorkSource;

import com.kooritea.fcmfix.util.XposedUtils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class OplusProxyFix extends XposedModule {

    private static Object s_oplusProxyWakeLock = null;

    public OplusProxyFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        try{
            this.startHookOplusProxyWakeLock();
            this.startHookOplusProxyBroadcast();
        }catch(Throwable e) {
            printLog("hook error OplusProxy:" + e.getMessage());
        }
    }

    private void startHookOplusProxyBroadcast() throws Exception {
        Class<?> oplusProxyBroadcastClass = XposedHelpers.findClass("com.android.server.am.OplusProxyBroadcast", loadPackageParam.classLoader);
        Class<?> resultEnum = XposedHelpers.findClass("com.android.server.am.OplusProxyBroadcast$RESULT", loadPackageParam.classLoader);
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
        Class<?> oplusWakelockClass = XposedHelpers.findClass("com.android.server.power.OplusProxyWakeLock", loadPackageParam.classLoader);

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

        printLog("unfreeze " + target + ", uid=" + uid);
        WorkSource ws = new WorkSource();
        XposedHelpers.callMethod(s_oplusProxyWakeLock, "unfreezeIfNeed", uid, ws, "FCMXX"); // 5 chars tag
    }

}
