package com.kooritea.fcmfix.xposed;

import android.content.Intent;
import android.os.Build;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class BroadcastFix extends XposedModule {

    public BroadcastFix(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        super(loadPackageParam);
    }

    @Override
    protected void onCanReadConfig() {
        this.startHook();
    }

    protected void startHook(){
        Class<?> clazz = XposedHelpers.findClass("com.android.server.am.ActivityManagerService",loadPackageParam.classLoader);
        final Method[] declareMethods = clazz.getDeclaredMethods();
        Method targetMethod = null;
        for(Method method : declareMethods){
            if(method.getName().equals("broadcastIntentLocked")){
                if(targetMethod == null || targetMethod.getParameterTypes().length < method.getParameterTypes().length){
                    targetMethod = method;
                }
            }
        }
        if(targetMethod != null){
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    int intent_args_index = 0;
                    int appOp_args_index = 0;
                    if(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q){
                        intent_args_index = 2;
                        appOp_args_index = 9;
                    }else if(Build.VERSION.SDK_INT == Build.VERSION_CODES.R){
                        intent_args_index = 3;
                        appOp_args_index = 10;
                    }else if(Build.VERSION.SDK_INT == 31){
                        intent_args_index = 3;
                        appOp_args_index = 11;
                    }else if(Build.VERSION.SDK_INT == 32){
                        intent_args_index = 3;
                        appOp_args_index = 11;
                    }else{
                        sendUpdateNotification("未适配的安卓版本: " + Build.VERSION.SDK_INT, "fcmfix将不会工作");
                        return;
                    }
                    Intent intent = (Intent) methodHookParam.args[intent_args_index];
                    if(intent != null && intent.getPackage() != null && intent.getFlags() != Intent.FLAG_INCLUDE_STOPPED_PACKAGES && "com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())){
                        String target;
                        if (intent.getComponent() != null) {
                            target = intent.getComponent().getPackageName();
                        } else {
                            target = intent.getPackage();
                        }
                        if(targetIsAllow(target)){
                            int i = ((Integer)methodHookParam.args[appOp_args_index]).intValue();
                            if (i == -1) {
                                methodHookParam.args[appOp_args_index] = Integer.valueOf(11);
                            }
                            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                            printLog("Send Forced Start Broadcast: " + target);
                        }
                    }
                }
            });
        }
    }
}
