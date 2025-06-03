package com.kooritea.fcmfix;

import android.annotation.SuppressLint;

import com.kooritea.fcmfix.xposed.AutoStartFix;
import com.kooritea.fcmfix.xposed.BroadcastFix;
import com.kooritea.fcmfix.xposed.GMSRestrictFix;
import com.kooritea.fcmfix.xposed.KeepNotification;
import com.kooritea.fcmfix.xposed.MiuiLocalNotificationFix;
import com.kooritea.fcmfix.xposed.PowerkeeperFix;
import com.kooritea.fcmfix.xposed.ReconnectManagerFix;
import com.kooritea.fcmfix.xposed.XposedModule;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedMain implements IXposedHookLoadPackage {

    @SuppressLint("SdCardPath")
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if(fileIsExists("/sdcard/disable_fcmfix")){
            XposedBridge.log("[fcmfix] /sdcard/disable_fcmfix is exists, exit");
            return;
        }
        if(loadPackageParam.packageName.equals("android")){
            XposedModule.staticLoadPackageParam = loadPackageParam;
            XposedBridge.log("[fcmfix] start hook com.android.server.am.ActivityManagerService/com.android.server.am.BroadcastController");
            new BroadcastFix(loadPackageParam);

            XposedBridge.log("[fcmfix] start hook com.android.server.hans.scene.OplusBgSceneManager");
            new GMSRestrictFix(loadPackageParam);

            XposedBridge.log("[fcmfix] start hook com.android.server.notification.NotificationManagerServiceInjector");
            new MiuiLocalNotificationFix(loadPackageParam);

            XposedBridge.log("[fcmfix] com.android.server.am.BroadcastQueueInjector.checkApplicationAutoStart");
            new AutoStartFix(loadPackageParam);

            XposedBridge.log("[fcmfix] com.android.server.notification.NotificationManagerService");
            new KeepNotification(loadPackageParam);
        }

        if(loadPackageParam.packageName.equals("com.google.android.gms") && loadPackageParam.isFirstApplication){
            XposedModule.staticLoadPackageParam = loadPackageParam;
            XposedBridge.log("[fcmfix] start hook com.google.android.gms");
            new ReconnectManagerFix(loadPackageParam);
        }

        if(loadPackageParam.packageName.equals("com.miui.powerkeeper") && loadPackageParam.isFirstApplication){
            XposedModule.staticLoadPackageParam = loadPackageParam;
            XposedBridge.log("[fcmfix] start hook com.miui.powerkeeper");
            new PowerkeeperFix(loadPackageParam);
        }

    }
    private boolean fileIsExists(String strFile) {
        try {
            File f = new File(strFile);
            if(!f.exists()) {
                return false;
            }
        }
        catch (Throwable e) {
            return false;
        }
        return true;
    }
}
