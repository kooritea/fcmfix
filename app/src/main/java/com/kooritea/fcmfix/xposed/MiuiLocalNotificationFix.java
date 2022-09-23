package com.kooritea.fcmfix.xposed;

import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MiuiLocalNotificationFix extends XposedModule  {

    public MiuiLocalNotificationFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
        this.startHook();
    }

    protected void startHook(){
        try{
            Class<?> clazz;
            try{
                clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerServiceInjector",loadPackageParam.classLoader);
            } catch (XposedHelpers.ClassNotFoundError e) {
                clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerServiceImpl",loadPackageParam.classLoader);
            }
            final Method[] declareMethods = clazz.getDeclaredMethods();
            Method targetMethod = null;
            for(Method method : declareMethods){
                if(method.getName().equals("isAllowLocalNotification") || method.getName().equals("isDeniedLocalNotification")){
                    targetMethod = method;
                    break;
                }
            }
            if(targetMethod != null){
                Method finalTargetMethod = targetMethod;
                XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                        if(targetIsAllow((String)methodHookParam.args[3])){
                            methodHookParam.setResult(finalTargetMethod.getName().equals("isAllowLocalNotification"));
                        }
                    }
                });
            }else{
                printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]", false);
            }
        }catch (XposedHelpers.ClassNotFoundError e){
            printLog("Not found [isAllowLocalNotification/isDeniedLocalNotification] in com.android.server.notification.[NotificationManagerServiceInjector/NotificationManagerServiceImpl]", false);
        }

    }
}
