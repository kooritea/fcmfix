package com.kooritea.fcmfix.xposed;

import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class KeepNotification extends XposedModule{

    public KeepNotification(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
    }

    @Override
    protected void onCanReadConfig() {
        try {
            this.startHook();
        } catch (XposedHelpers.ClassNotFoundError | NoSuchMethodError e) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt", false);
        }
    }

    protected void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService",loadPackageParam.classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        for(Method method : declareMethods){
            if(method.getName().equals("cancelAllNotificationsInt")){
                if(targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length){
                    targetMethod = method;
                }
            }
        }
        if(targetMethod != null){
            int pkg_args_index = 2;
            int reason_args_index = 8;
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if(targetIsAllow((String) param.args[pkg_args_index]) && (int)param.args[reason_args_index] == 5){
                        param.setResult(null);
                    }
                }
            });
        }else{
            throw new NoSuchMethodError();
        }
    }
}
