package com.kooritea.fcmfix.xposed;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class GMSRestrictFix extends XposedModule {
    public GMSRestrictFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
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
        /*try {
            this.startHookIsGmsApp(); // 阻止Hans对GMS进行特殊处理
        } catch (Throwable e) {
            printLog("hook error isGmsApp:" + e.getMessage());
        }*/
    }

    private void startHookRegisterGmsRestrictObserver() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", loadPackageParam.classLoader, "registerGmsRestrictObserver", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookUpdateGmsRestrict() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.scene.OplusBgSceneManager", loadPackageParam.classLoader, "updateGmsRestrict", XC_MethodReplacement.DO_NOTHING);
    }

    private void startHookIsGoogleRestricInfoOn() {
        XposedHelpers.findAndHookMethod("com.android.server.am.OplusAppStartupManager$OplusStartupStrategy", loadPackageParam.classLoader, "isGoogleRestricInfoOn", int.class, XC_MethodReplacement.returnConstant(false));
    }

    private void startHookIsGmsApp() {
        XposedHelpers.findAndHookMethod("com.android.server.hans.OplusHansDBConfig", loadPackageParam.classLoader, "isGmsApp", int.class, XC_MethodReplacement.returnConstant(false));
    }
}
