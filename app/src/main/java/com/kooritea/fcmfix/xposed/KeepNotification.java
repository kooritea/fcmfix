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
                    // On Android 16 (API 36) the resolved cancelAllNotificationsInt overload may not
                    // follow the assumed layout: the pkg slot can hold an int (uid/userId) instead of a
                    // String when the ROM reorders or reshapes the parameters. Blindly casting threw
                    // "java.lang.ClassCastException: java.lang.Integer cannot be cast to java.lang.String"
                    // on every single call (72 occurrences captured in LSPosed logs on an obfuscated
                    // API 36 ROM; found via MiPushFramework issue #149 — the exception propagated up
                    // through other modules' NMS hooks and broke every cancel/delete flow reaching
                    // cancelAllNotificationsInt). Guard the cast and bail out instead of poisoning the
                    // hook chain; log once so the signature drift stays diagnosable.
                    if(finalPkg_args_index >= param.args.length){
                        if(!signatureMismatchLogged){
                            signatureMismatchLogged = true;
                            printLog("cancelAllNotificationsInt has only " + param.args.length + " args, expected index " + finalPkg_args_index + "; skip");
                        }
                        return;
                    }
                    Object pkgArg = param.args[finalPkg_args_index];
                    if(!(pkgArg instanceof String)){
                        if(pkgArg != null && !signatureMismatchLogged){
                            signatureMismatchLogged = true;
                            printLog("cancelAllNotificationsInt arg[" + finalPkg_args_index + "] is " + pkgArg.getClass().getName() + ", expected String (API signature drift); skip");
                        }
                        return;
                    }
                    if(getBooleanConfig("disableAutoCleanNotification",false) && targetIsAllow((String) pkgArg)){
                        if(finalReason_args_index >= param.args.length){
                            return;
                        }
                        Object reasonArg = param.args[finalReason_args_index];
                        if(!(reasonArg instanceof Integer)){
                            return;
                        }
                        int reason = (int)reasonArg;
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

    private static volatile boolean signatureMismatchLogged = false;
}
