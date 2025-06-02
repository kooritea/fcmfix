package com.kooritea.fcmfix.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class GMSRestrictFix extends XposedModule {
    public GMSRestrictFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        try {
            this.startHookGMSRestrict();
        } catch (Throwable ignored) {

        }
    }

    private void startHookGMSRestrict() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", loadPackageParam.classLoader, "registerGmsRestrictObserver", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
            }
        });
    }
}
