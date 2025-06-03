package com.kooritea.fcmfix.xposed;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class GMSRestrictFix extends XposedModule {
    public GMSRestrictFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        try {
            this.startHookGMSRestrict();
        } catch (Throwable e) {
            printLog("hook error registerGmsRestrictObserver:" + e.getMessage());
        }
    }

    private void startHookGMSRestrict() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", loadPackageParam.classLoader, "registerGmsRestrictObserver", XC_MethodReplacement.DO_NOTHING);

        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", loadPackageParam.classLoader, "updateGmsRestrict", XC_MethodReplacement.DO_NOTHING);

        XposedHelpers.findAndHookMethod("com.android.server.hans.OplusHansDBConfig", loadPackageParam.classLoader, "isGmsApp", int.class, XC_MethodReplacement.returnConstant(false));
    }
}
