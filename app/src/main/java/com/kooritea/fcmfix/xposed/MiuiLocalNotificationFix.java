package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.ArraySet;

import com.kooritea.fcmfix.util.ContentProviderHelper;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MiuiLocalNotificationFix extends XposedModule  {

    private Set<String> allowList = null;

    public MiuiLocalNotificationFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHook();
    }

    @Override
    protected void onCanReadConfig() throws Exception {
        this.onUpdateConfig();
        this.initUpdateConfigReceiver();
    }

    protected void startHook(){
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerServiceInjector",loadPackageParam.classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        for(Method method : declareMethods){
            if(method.getName().equals("isAllowLocalNotification")){
                targetMethod = method;
                break;
            }
        }
        if(targetMethod != null){
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    if(targetIsAllow((String)methodHookParam.args[3])){
                        methodHookParam.setResult(true);
                        printLog("Allow LocalNotification " + methodHookParam.args[3]);
                    }
                }
            });
        }else{
            printLog("Not found isAllowLocalNotification in com.android.server.notification.NotificationManagerServiceInjector");
        }
    }

    private boolean targetIsAllow(String packageName){
        if(this.allowList == null){
            this.checkUserDeviceUnlock(AndroidAppHelper.currentApplication().getApplicationContext());
        }
        if(this.allowList != null){
            for (String item : this.allowList) {
                if (item.equals(packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void onUpdateConfig(){
        ContentProviderHelper contentProviderHelper = new ContentProviderHelper(AndroidAppHelper.currentApplication().getApplicationContext(),"content://com.kooritea.fcmfix.provider/config");
        this.allowList = contentProviderHelper.getStringSet("allowList");
    }

    private  void initUpdateConfigReceiver(){
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("com.kooritea.fcmfix.update.config");
        AndroidAppHelper.currentApplication().getApplicationContext().registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.kooritea.fcmfix.update.config".equals(action)) {
                    onUpdateConfig();
                }
            }
        }, intentFilter);
    }
}
