package com.kooritea.fcmfix.xposed;

import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;
import com.kooritea.fcmfix.libxposed.XC_MethodHook;
import com.kooritea.fcmfix.libxposed.XposedBridge;
import com.kooritea.fcmfix.libxposed.XposedHelpers;

public class KeepNotification extends XposedModule{

    public KeepNotification(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt");
        }
    }
    
    protected void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService",classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        for(Method method : declareMethods){
            if("cancelAllNotificationsInt".equals(method.getName())){
                if(targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length){
                    targetMethod = method;
                }
            }
        }
        if(targetMethod != null){
            int pkg_args_index = 0;
            int reason_args_index = 0;
            if(Build.VERSION.SDK_INT == 30){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 31){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 32){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 33){
                pkg_args_index = 2;
                reason_args_index = 8;
            }
            if(Build.VERSION.SDK_INT == 34){
                if(targetMethod.getParameterTypes().length == 10){
                    pkg_args_index = 2;
                    reason_args_index = 8;
                }else if(targetMethod.getParameterTypes().length == 8){
                    pkg_args_index = 2;
                    reason_args_index = 7;
                }
            }
            if(Build.VERSION.SDK_INT == 35){
                pkg_args_index = 2;
                reason_args_index = 7;
            }
            if(Build.VERSION.SDK_INT > 35){
                pkg_args_index = 2;
                reason_args_index = 7;
            }
            if(pkg_args_index == 0 || reason_args_index == 0){
                throw new NoSuchMethodError();
            }
            int finalPkg_args_index = pkg_args_index;
            int finalReason_args_index = reason_args_index;
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if(!isBootComplete){
                        return;
                    }
                    if(getBooleanConfig("disableAutoCleanNotification",false) && targetIsAllow((String) param.args[finalPkg_args_index])){
                        int reason = (int)param.args[finalReason_args_index];
                        if(reason == NotificationListenerService.REASON_PACKAGE_CHANGED){
                            param.setResult(null);
                        }
                        if(reason == 10020 || reason == 10021){ // cos15/oos15
                            param.setResult(null);
                        }
                    }
                }
            });
        }else{
            throw new NoSuchMethodError();
        }
    }
}
