package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

import java.util.Timer;
import java.util.TimerTask;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ReconnectManagerFix extends XposedModule{

    private Class<?> GcmChimeraService;

    public ReconnectManagerFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
    }

    protected void startHook(){
        this.GcmChimeraService = XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService",loadPackageParam.classLoader);
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("abbo",loadPackageParam.classLoader),"a", long.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                new Timer("ReconnectManagerFix").schedule(new TimerTask() {
                    @Override
                    public void run() {
                        long nextConnectionTime = XposedHelpers.getLongField(param.thisObject,"g");
                        if(nextConnectionTime !=0 && nextConnectionTime - SystemClock.elapsedRealtime() < 0){
                            AndroidAppHelper.currentApplication().getApplicationContext().sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT"));
                            printLog("Send broadcast GCM_RECONNECT");
                        }
                    }
                }, (long)param.args[0]+5000);
            }
        });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService",loadPackageParam.classLoader),"onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction("com.kooritea.fcmfix.log");
                AndroidAppHelper.currentApplication().getApplicationContext().registerReceiver(logBroadcastReceive,intentFilter);
            }
        });
        XposedHelpers.findAndHookMethod(XposedHelpers.findClass("com.google.android.gms.gcm.GcmChimeraService",loadPackageParam.classLoader),"onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                AndroidAppHelper.currentApplication().getApplicationContext().unregisterReceiver(logBroadcastReceive);
            }
        });
    }

    private BroadcastReceiver logBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if("com.kooritea.fcmfix.log".equals(action)) {
                XposedHelpers.callStaticMethod(GcmChimeraService,"a",new Class<?>[]{String.class,Object[].class},"[fcmfix] "+ intent.getStringExtra("text"),null);
            }
        }
    };
}
