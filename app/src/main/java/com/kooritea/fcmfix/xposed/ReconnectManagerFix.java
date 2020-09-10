package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.Intent;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ReconnectManagerFix extends XposeModule{
    public ReconnectManagerFix(final XC_LoadPackage.LoadPackageParam loadPackageParam){
        final Class<?> clazz = XposedHelpers.findClass("abbo",loadPackageParam.classLoader);
        XposedBridge.log("[fcmfix] ReconnectManagerFix start hook");
        XposedHelpers.findAndHookMethod(clazz,"a", long.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                new Timer("ReconnectManagerFix").schedule(new TimerTask() {
                    @Override
                    public void run() {
                        long nextConnectionTime = XposedHelpers.getLongField(param.thisObject,"g");
                        if(nextConnectionTime !=0 && nextConnectionTime - SystemClock.elapsedRealtime() < 0){
                            AndroidAppHelper.currentApplication().getApplicationContext().sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                        }
                    }
                }, (long)param.args[0]+5000);
            }
        });
    }
}
