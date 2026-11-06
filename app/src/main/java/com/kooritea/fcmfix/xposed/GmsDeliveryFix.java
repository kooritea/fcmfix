package com.kooritea.fcmfix.xposed;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedHelpers;
import com.kooritea.fcmfix.util.XposedUtils;

/**
 * GMS 進程兜底修復：在 GMS 發送 FCM 投遞廣播的源頭直接加上 FLAG_INCLUDE_STOPPED_PACKAGES。
 * <p>
 * 部分 ROM（如 ColorOS 16）會把 system_server 中 broadcastIntentLocked 內聯，
 * 導致系統側 hook 不觸發。此類直接 hook GMS 的 ContextImpl.sendBroadcast/sendOrderedBroadcast，
 * 從源頭上讓廣播帶上 INCLUDE_STOPPED 標誌，系統即會正常投遞給已停止的應用。
 * <p>
 * 需要模塊作用域包含 com.google.android.gms。
 */
public class GmsDeliveryFix extends XposedModule {

    public GmsDeliveryFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHook();
        } catch (Throwable e) {
            printLog("GmsDeliveryFix hook error: " + e.getMessage());
        }
    }

    private void startHook() {
        // 不同 Android 版本上 sendBroadcast/sendOrderedBroadcast 的聲明位置不同：
        // 部分版本在 ContextWrapper（基類，所有 Context 都經過），部分在 ContextImpl。
        // 兩個都嘗試掛接，失敗的忽略。
        for (String className : new String[]{"android.content.ContextWrapper", "android.content.ContextImpl"}) {
            try {
                XposedUtils.findAndHookMethodAnyParam(className, classLoader, "sendBroadcast",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                onBroadcastSent((Intent) param.args[0]);
                            }
                        }, Intent.class);
            } catch (Throwable e) {
                printLog("GmsDeliveryFix sendBroadcast 挂接失败 (" + className + "): " + e.getMessage());
            }
            try {
                XposedUtils.findAndHookMethodAnyParam(className, classLoader, "sendOrderedBroadcast",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                onBroadcastSent((Intent) param.args[0]);
                            }
                        }, Intent.class, String.class, Bundle.class, BroadcastReceiver.class, Handler.class, int.class, String.class, Bundle.class);
            } catch (Throwable e) {
                printLog("GmsDeliveryFix sendOrderedBroadcast 挂接失败 (" + className + "): " + e.getMessage());
            }
            try {
                XposedUtils.findAndHookMethodAnyParam(className, classLoader, "sendOrderedBroadcast",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                onBroadcastSent((Intent) param.args[0]);
                            }
                        }, Intent.class, String.class, BroadcastReceiver.class, Handler.class, int.class, String.class, Bundle.class);
            } catch (Throwable e) {
                printLog("GmsDeliveryFix sendOrderedBroadcast7 挂接失败 (" + className + "): " + e.getMessage());
            }
        }
    }

    private void onBroadcastSent(Intent intent) {
        try {
            if (!isBootComplete) {
                return;
            }
            if (intent == null) {
                return;
            }
            if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0) {
                return;
            }
            if (!isFCMIntent(intent)) {
                return;
            }
            String target = intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
            if (target == null) {
                return;
            }
            if (!targetIsAllow(target)) {
                return;
            }
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            printLog("GMS 侧加入 FLAG_INCLUDE_STOPPED_PACKAGES: " + target, true);
        } catch (Throwable e) {
            printLog("GmsDeliveryFix onBroadcastSent error: " + e.getMessage());
        }
    }
}
