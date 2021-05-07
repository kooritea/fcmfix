package com.kooritea.fcmfix.xposed;

import android.app.AndroidAppHelper;
import android.content.Intent;
import android.os.Build;

import com.kooritea.fcmfix.util.ContentProviderHelper;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MiuiLocalNotificationFix extends XposedModule  {

    protected ContentProviderHelper contentProviderHelper;

    public MiuiLocalNotificationFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        contentProviderHelper = new ContentProviderHelper(AndroidAppHelper.currentApplication().getApplicationContext(),"content://com.kooritea.fcmfix.provider/config");
        this.startHook();
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
        Set<String> allowList = this.contentProviderHelper.getStringSet("allowList");
        for (String item : allowList) {
            if (item.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
}
